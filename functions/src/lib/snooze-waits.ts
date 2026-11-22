import {
  type DocumentReference,
  FieldPath,
  FieldValue,
} from "firebase-admin/firestore";

/**
 * One phone's entry in `users/{uid}/meta/snoozeWaits`, keyed by its push token: the snooze it waits
 * on (both fields missing when none) and when it worked that out.
 */
export interface SnoozeWait {
  /** the snoozed item it waits on: a Gmail thread id, or a todo's {@link todoItemId} */
  itemId?: string;
  /** when that snooze ends, a local wall time (`YYYY-MM-DDTHH:mm:ss`) */
  snoozeUntil?: string;
  /** when the phone wrote this, in epoch milliseconds (server time); it reads the snoozes again after, so the entry accounts for every snooze written before it */
  updatedAt: number;
}

/** Whether a stored entry looks like a {@link SnoozeWait}, before reading it as one. */
function isWait(value: unknown): value is {
  updatedAt: { toMillis(): number };
  itemId?: unknown;
  snoozeUntil?: unknown;
} {
  const updatedAt = (value as { updatedAt?: { toMillis?: unknown } } | null)
    ?.updatedAt;
  return typeof updatedAt?.toMillis === "function";
}

/** Read the `waits` map of a stored `snoozeWaits` doc; entries of another shape are left out. */
export function readWaits(
  data: { waits?: unknown } | undefined,
): Map<string, SnoozeWait> {
  const waits = new Map<string, SnoozeWait>();
  for (const [token, value] of Object.entries(data?.waits ?? {})) {
    if (!isWait(value)) continue;
    const { itemId, snoozeUntil } = value;
    waits.set(token, {
      ...(typeof itemId === "string" && typeof snoozeUntil === "string"
        ? { itemId, snoozeUntil }
        : {}),
      updatedAt: value.updatedAt.toMillis(),
    });
  }
  return waits;
}

/**
 * The phones (push tokens) to ping after `itemId`'s snooze became `snoozeUntil` (null: removed) in
 * a write at `writtenAt`: one waiting on that item whose time no longer matches, and one that, when it
 * last worked out its wait, hadn't seen this write and waits on something later or on nothing. Local
 * wall times compare as strings; no time zone is involved.
 */
export function phonesToPing(
  waits: ReadonlyMap<string, SnoozeWait>,
  itemId: string,
  snoozeUntil: string | null,
  writtenAt: number,
): string[] {
  const tokens: string[] = [];
  for (const [token, wait] of waits) {
    const ping =
      wait.itemId === itemId
        ? wait.snoozeUntil !== snoozeUntil
        : snoozeUntil !== null &&
          writtenAt > wait.updatedAt &&
          (wait.snoozeUntil === undefined || snoozeUntil < wait.snoozeUntil);
    if (ping) tokens.push(token);
  }
  return tokens;
}

/** The tokens of `waits` with no device doc among `deviceTokens`: phones that signed out or were dropped. */
export function orphanWaits(
  waits: ReadonlyMap<string, SnoozeWait>,
  deviceTokens: readonly string[],
): string[] {
  const devices = new Set(deviceTokens);
  return [...waits.keys()].filter((token) => !devices.has(token));
}

/** The snooze item id of a todo: `todo:` and its id, apart from Gmail's thread ids. */
export function todoItemId(todoId: string): string {
  return `todo:${todoId}`;
}

/**
 * A stored todo's snooze as phones wait on it: its `snoozeUntil`, or null when it is deleted,
 * done or not snoozed.
 */
export function todoSnoozeUntil(
  data:
    | { snoozeUntil?: unknown; deleted?: unknown; state?: unknown }
    | undefined,
): string | null {
  if (data === undefined || data.deleted === true || data.state === "DONE") {
    return null;
  } else {
    return typeof data.snoozeUntil === "string" ? data.snoozeUntil : null;
  }
}

/**
 * Drop the entries of `tokens` from a `snoozeWaits` doc (a transaction), leaving a doc the owner
 * deleted meanwhile deleted.
 */
export async function dropWaits(
  waitsRef: DocumentReference,
  tokens: readonly string[],
): Promise<void> {
  const [firstToken, ...otherTokens] = tokens;
  if (firstToken === undefined) return;
  await waitsRef.firestore.runTransaction(async (transaction) => {
    if (!(await transaction.get(waitsRef)).exists) return;
    transaction.update(
      waitsRef,
      new FieldPath("waits", firstToken),
      FieldValue.delete(),
      ...otherTokens.flatMap((token) => [
        new FieldPath("waits", token),
        FieldValue.delete(),
      ]),
    );
  });
}
