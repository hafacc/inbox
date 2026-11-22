// How a device paces its Gmail calls (design.md, "Google access"): how many run at once, which
// waiting call goes next, and how long to back off after Google says to slow down; and when the
// device shows itself offline, from how its Gmail calls went. Android :core gmail/GmailPace.kt is
// the same; both run shared/testdata/gmail-pace-fixtures.json.

/** Most Gmail calls one device runs at once. */
export const MAX_CONCURRENT = 4;
/** The pause after the first usage limit in a row, before jitter. */
export const FIRST_BACKOFF_MS = 1_000;
/** The longest pause, before jitter, however many limits in a row. */
export const MAX_BACKOFF_MS = 32_000;

/** The longest `Retry-After` heeded, in seconds: a longer one would hold every call, sends too. */
export const MAX_RETRY_AFTER_SECONDS = 60;
/** Times a read is tried again after a usage limit before its error reaches the caller. */
export const READ_RETRIES = 4;
/** Most saved-copy reads one device runs at once. */
export const MAX_SAVING = 2;
/**
 * The least time between the starts of two saved-copy reads: at most 4 a second, about 40 of
 * Gmail's 250 quota units a second for each user (shared by all their devices), so the rest stay
 * free for what the user asks.
 */
export const SAVING_SPACING_MS = 250;
/**
 * How long saved-copy reads stay held after a usage limit's pause ends, so what the user asks
 * goes alone while Gmail's quota refills.
 */
export const SAVING_HOLD_MS = 30_000;

/** Reasons Google gives a 403 for limits that lift on their own, unlike a refusal. */
const LIMIT_REASONS: ReadonlySet<string> = new Set([
  "rateLimitExceeded",
  "userRateLimitExceeded",
  "RATE_LIMIT_EXCEEDED",
]);

/**
 * How urgent a call is: a user's action, what is on screen, a background prefetch or refetch, or
 * a read that fills the saved copy (which yields to every other call and is paced).
 */
export type CallPriority = "action" | "visible" | "background" | "saving";

const PRIORITY_RANK: Readonly<Record<CallPriority, number>> = {
  action: 0,
  visible: 1,
  background: 2,
  saving: 3,
};

/** A call waiting for its turn. */
export type WaitingCall = {
  /** the caller's handle for it */
  id: string;
  /** how urgent it is */
  priority: CallPriority;
  /** when it asked, as a running count; earlier goes first among equals */
  order: number;
};

/** Whether a Google API error status and reason are a usage limit, which passes if tried later. */
export function isUsageLimit(status: number, reason: string | null): boolean {
  return (
    status === 429 ||
    (status === 403 && reason !== null && LIMIT_REASONS.has(reason))
  );
}

/**
 * The pause after the `strike`-th usage limit in a row (1 for the first): Google's `Retry-After`
 * when it gives one (at most {@link MAX_RETRY_AFTER_SECONDS}), else {@link FIRST_BACKOFF_MS} doubling up to {@link MAX_BACKOFF_MS}. Either is
 * stretched by `jitter` (0 up to 1, random in the apps) by up to half, so devices and calls that
 * hit the limit together don't come back together.
 */
export function backoffMs(
  strike: number,
  retryAfterSeconds: number | null,
  jitter: number,
): number {
  const base =
    retryAfterSeconds !== null && retryAfterSeconds >= 0
      ? Math.min(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS) * 1000
      : Math.min(
          MAX_BACKOFF_MS,
          FIRST_BACKOFF_MS * 2 ** Math.max(0, Math.min(strike - 1, 16)),
        );
  const spread = Math.min(Math.max(jitter, 0), 1);
  return Math.round(base * (1 + spread / 2));
}

/**
 * Whether a call that just hit a usage limit on try `attempt` (0 for the first) is tried again: a
 * read up to {@link READ_RETRIES} times; a write never here, since the offline queue retries it.
 */
export function retriesAfterLimit(isRead: boolean, attempt: number): boolean {
  return isRead && attempt < READ_RETRIES;
}

/** What the device's calls are doing now, as the choice of the next one needs it. */
export type PaceState = {
  /** calls running */
  running: number;
  /** of those, saved-copy reads (started as "saving") */
  runningSaving: number;
  /** when calls may go again after a usage limit, epoch milliseconds; 0 or past when they may go now */
  pausedUntil: number;
  /** when the last saved-copy read started, epoch milliseconds; 0 for never */
  savingStartedAt: number;
  /** when saved-copy reads may go again after a usage limit (see {@link savingHeldUntil}); 0 or past when they may */
  savingHeldUntil: number;
};

/** When saved-copy reads may go again after a usage limit whose pause ends at `pausedUntil`. */
export function savingHeldUntil(pausedUntil: number): number {
  return pausedUntil + SAVING_HOLD_MS;
}

/** The next turn: the waiting call to start now, if any, and else when to look again, if a clock (not a call ending) frees one. */
export type Turn = {
  /** the id of the call to start now, or null */
  id: string | null;
  /** when a waiting call may start though none can now, epoch milliseconds, or null */
  wakeAt: number | null;
};

/**
 * The next turn. None while {@link MAX_CONCURRENT} run, or until a pause ends (`pausedUntil` after
 * `now`); else the most urgent waiting call, the earliest among equals. A saved-copy read
 * ("saving") yields to every other call, waiting or running, runs at most {@link MAX_SAVING} at
 * once, starts at least {@link SAVING_SPACING_MS} after the one before, and waits out the hold
 * after a usage limit (`savingHeldUntil`).
 */
export function nextCall(
  waiting: readonly WaitingCall[],
  state: PaceState,
  now: number,
): Turn {
  if (waiting.length === 0 || state.running >= MAX_CONCURRENT) {
    return { id: null, wakeAt: null };
  }
  if (state.pausedUntil > now) return { id: null, wakeAt: state.pausedUntil };
  let best: WaitingCall | null = null;
  for (const call of waiting) {
    if (
      best === null ||
      PRIORITY_RANK[call.priority] < PRIORITY_RANK[best.priority] ||
      (PRIORITY_RANK[call.priority] === PRIORITY_RANK[best.priority] &&
        call.order < best.order)
    ) {
      best = call;
    }
  }
  if (best!.priority !== "saving") return { id: best!.id, wakeAt: null };
  if (
    state.running > state.runningSaving ||
    state.runningSaving >= MAX_SAVING
  ) {
    return { id: null, wakeAt: null };
  }
  const ready = Math.max(
    state.savingStartedAt + SAVING_SPACING_MS,
    state.savingHeldUntil,
  );
  return ready > now
    ? { id: null, wakeAt: ready }
    : { id: best!.id, wakeAt: null };
}

/** Something that tells the app whether Gmail can be reached. */
export type ConnectionEvent =
  /** a Gmail call came back with an HTTP status, error or not */
  | { kind: "answered"; status: number }
  /** a Gmail call got no answer: no network, the host couldn't be found or connected to */
  | { kind: "unanswered" }
  /** a Gmail call was given up after its time limit, which a slow link does too */
  | { kind: "timedOut" }
  /** the device gained a network */
  | { kind: "networkUp" }
  /** the device has no network left */
  | { kind: "networkDown" };

/** Whether the app shows itself offline after `event`, given whether it did before. */
export function offlineAfter(
  offline: boolean,
  event: ConnectionEvent,
): boolean {
  switch (event.kind) {
    case "answered":
    case "networkUp":
      return false;
    case "unanswered":
    case "networkDown":
      return true;
    case "timedOut":
      return offline;
  }
}
