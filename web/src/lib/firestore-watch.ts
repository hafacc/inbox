import {
  type DocumentReference,
  type DocumentSnapshot,
  type FirestoreError,
  onSnapshot,
  type Query,
  type QuerySnapshot,
  type Unsubscribe,
} from "firebase/firestore";

const BASE_RETRY_DELAY_MS = 1_000;
const MAX_RETRY_DELAY_MS = 30_000;

/** A Firestore listener that listens again after an error ends it. */
export type FirestoreWatch = {
  /** stop listening for good */
  stop: () => void;
  /** listen again now when an error ended it, instead of waiting out the backoff */
  retryNow: () => void;
};

/** What `retryingListener` needs of a snapshot: whether it came from the device's cache. */
type Snapshot = { metadata: { fromCache: boolean } };

/** Timers `retryingListener` waits with; the browser's by default. */
export type RetryTimers = {
  set: (callback: () => void, ms: number) => unknown;
  clear: (timer: unknown) => void;
};

const BROWSER_TIMERS: RetryTimers = {
  set: (callback, ms) => setTimeout(callback, ms),
  clear: (timer) => clearTimeout(timer as ReturnType<typeof setTimeout>),
};

/**
 * Follow a Firestore document or query, calling `onNext` with each snapshot. An error ends a
 * Firestore listener, so this listens again after a backoff (1 s doubling to 30 s, back to 1 s
 * after a snapshot from the server); `what` names it in logs.
 */
export function watchFirestore(
  target: DocumentReference,
  onNext: (snapshot: DocumentSnapshot) => void,
  what: string,
): FirestoreWatch;
export function watchFirestore(
  target: Query,
  onNext: (snapshot: QuerySnapshot) => void,
  what: string,
): FirestoreWatch;
export function watchFirestore(
  target: DocumentReference | Query,
  onNext: (snapshot: never) => void,
  what: string,
): FirestoreWatch {
  if (target.type === "document") {
    const reference = target as DocumentReference;
    return retryingListener<DocumentSnapshot>(
      (next, failed) => onSnapshot(reference, next, failed),
      onNext as (snapshot: DocumentSnapshot) => void,
      what,
    );
  } else {
    const query = target as Query;
    return retryingListener<QuerySnapshot>(
      (next, failed) => onSnapshot(query, next, failed),
      onNext as (snapshot: QuerySnapshot) => void,
      what,
    );
  }
}

/** Run `listen` (an `onSnapshot` call) as `watchFirestore` does, listening again after each error. */
export function retryingListener<Next extends Snapshot>(
  listen: (
    onNext: (snapshot: Next) => void,
    onError: (error: FirestoreError) => void,
  ) => Unsubscribe,
  onNext: (snapshot: Next) => void,
  what: string,
  timers: RetryTimers = BROWSER_TIMERS,
): FirestoreWatch {
  let unsubscribe: Unsubscribe | null = null;
  let retryTimer: unknown = null;
  let attempt = 0;
  let stopped = false;
  const start = () => {
    unsubscribe = listen(
      (snapshot) => {
        if (!snapshot.metadata.fromCache) attempt = 0;
        onNext(snapshot);
      },
      (error) => {
        console.error(`${what} listener error`, error);
        unsubscribe = null;
        if (stopped || retryTimer !== null) return;
        const delay = Math.min(
          MAX_RETRY_DELAY_MS,
          BASE_RETRY_DELAY_MS * 2 ** attempt,
        );
        attempt++;
        retryTimer = timers.set(() => {
          retryTimer = null;
          if (!stopped) start();
        }, delay);
      },
    );
  };
  start();
  return {
    stop: () => {
      stopped = true;
      unsubscribe?.();
      unsubscribe = null;
      if (retryTimer !== null) timers.clear(retryTimer);
      retryTimer = null;
    },
    retryNow: () => {
      if (stopped || retryTimer === null) return;
      timers.clear(retryTimer);
      retryTimer = null;
      start();
    },
  };
}
