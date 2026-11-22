import { expect, test } from "bun:test";
import type { FirestoreError } from "firebase/firestore";
import { type RetryTimers, retryingListener } from "./firestore-watch";

type Snapshot = { metadata: { fromCache: boolean }; value: number };

function fakeTimers() {
  const pending = new Map<number, { callback: () => void; ms: number }>();
  let next = 0;
  const timers: RetryTimers = {
    set: (callback, ms) => {
      next++;
      pending.set(next, { callback, ms });
      return next;
    },
    clear: (timer) => pending.delete(timer as number),
  };
  const fire = () => {
    const [first] = [...pending];
    if (first === undefined) throw new Error("no timer waits");
    const [id, entry] = first;
    pending.delete(id);
    entry.callback();
    return entry.ms;
  };
  return { timers, pending, fire };
}

function fakeListener() {
  const listens: {
    next: (snapshot: Snapshot) => void;
    failed: (error: FirestoreError) => void;
    stopped: boolean;
  }[] = [];
  const listen = (
    next: (snapshot: Snapshot) => void,
    failed: (error: FirestoreError) => void,
  ) => {
    const entry = { next, failed, stopped: false };
    listens.push(entry);
    return () => {
      entry.stopped = true;
    };
  };
  const fail = () => listens.at(-1)?.failed({} as FirestoreError);
  return { listens, listen, fail };
}

test("listens again after an error, backing off until the server answers", () => {
  const { timers, fire } = fakeTimers();
  const { listens, listen, fail } = fakeListener();
  const seen: number[] = [];
  const originalError = console.error;
  console.error = () => {};
  try {
    retryingListener(
      listen,
      (snapshot) => seen.push(snapshot.value),
      "test",
      timers,
    );
    fail();
    expect(fire()).toBe(1_000);
    fail();
    expect(fire()).toBe(2_000);
    listens.at(-1)?.next({ metadata: { fromCache: false }, value: 7 });
    fail();
    expect(fire()).toBe(1_000);
    expect(listens.length).toBe(4);
    expect(seen).toEqual([7]);
  } finally {
    console.error = originalError;
  }
});

test("stop ends the listener and any wait to listen again; retryNow skips the wait", () => {
  const { timers, pending } = fakeTimers();
  const { listens, listen, fail } = fakeListener();
  const originalError = console.error;
  console.error = () => {};
  try {
    const watch = retryingListener(listen, () => {}, "test", timers);
    fail();
    watch.retryNow();
    expect([listens.length, pending.size]).toEqual([2, 0]);
    fail();
    watch.stop();
    expect(pending.size).toBe(0);
    watch.retryNow();
    expect(listens.length).toBe(2);
    const other = retryingListener(listen, () => {}, "test", timers);
    other.stop();
    expect(listens.at(-1)?.stopped).toBe(true);
  } finally {
    console.error = originalError;
  }
});
