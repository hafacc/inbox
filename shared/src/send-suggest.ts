// Learned schedule-send suggestions: the snooze engine's keys, counts and ranking, fed by their own
// stats doc, with send-shaped defaults and the "Vary scheduled send times" shift. Pure functions only.

import {
  type DevicePartition,
  labelParts,
  type RowIcon,
  rankDeep,
  resolveKey,
  rowIcon,
} from "./snooze-suggest";

export {
  negated,
  sharedFromWire,
  type VoteDeltas,
  voteDeltas,
} from "./snooze-stats-wire";
export {
  type CommitResult,
  type CommitUndoSnapshot,
  commit,
  type DevicePartition,
  emptyPartition,
  formatClock,
  type LastRow,
  lastRow,
  type QuickTime,
  quickTimeEpoch,
  quickTimes,
  type SnoozeSource as SendSource,
  undoCommit,
} from "./snooze-suggest";

const MINUTE_MS = 60 * 1000;

/** Rows the schedule-send menu shows, above the last custom time and "Pick date & time". */
export const SEND_SHOWN_ROWS = 4;

/** Built-in suggestions, in priority order: next 08:00, next 13:00, Monday 08:00. */
export const SEND_DEFAULT_KEYS: readonly string[] = [
  "D0@0800",
  "D0@1300",
  "Wd1@0800",
];

/** A default is left out when another row lands closer than this. */
export const DEFAULT_GAP_MS = 30 * MINUTE_MS;

/** How long undo send waits before the message goes; never varied. */
export const UNDO_SEND_DELAY_MS = 10_000;

/** How long after its send time a message may stay unsent before the Outbox is listed. */
export const OUTBOX_GRACE_MS = 20_000;

/** The parts of an outbox doc that decide whether the Outbox is listed. */
export type OutboxTiming = {
  /** `waiting`, `sending`, `retrying`, `blocked`, `sent` or `failed` */
  status: string;
  /** when it goes, epoch milliseconds */
  sendAt: number;
};

/**
 * When the Outbox should be listed in the menu: `now` if it should be now, the later moment it
 * will be if nothing changes, or null while nothing is held up.
 *
 * A send that goes through undo send and then out at once never lists it. Something failed,
 * blocked or retrying lists it at once; a send scheduled past the undo delay lists it at once;
 * one still unsent `OUTBOX_GRACE_MS` after its time (30 s after pressing send) lists it then.
 */
export function outboxShownAt(
  docs: Iterable<OutboxTiming>,
  now: number,
): number | null {
  let earliest: number | null = null;
  for (const { status, sendAt } of docs) {
    if (status === "sent") continue;
    const at =
      status === "failed" ||
      status === "blocked" ||
      status === "retrying" ||
      sendAt - now > UNDO_SEND_DELAY_MS
        ? now
        : Math.max(now, sendAt + OUTBOX_GRACE_MS);
    if (earliest === null || at < earliest) earliest = at;
  }
  return earliest;
}

/** Default largest shift, in whole minutes either way, for "Vary scheduled send times". */
export const DEFAULT_MAX_SHIFT_MINUTES = 10;

/** Smallest largest shift the setting's slider offers. */
export const MIN_MAX_SHIFT_MINUTES = 1;

/** Biggest largest shift the setting's slider offers. */
export const MAX_MAX_SHIFT_MINUTES = 30;

/** A shifted time must still be at least this far ahead of now. */
const MIN_LEAD_MS = MINUTE_MS;

/** Label of the menu item that opens the date and time picker. */
export const PICK_DATE_TIME_LABEL = "Pick date & time…";

/** One schedule-send suggestion. */
export type SendRow = {
  /** when it sends, epoch milliseconds, before any shift */
  time: number;
  /** label without its time, e.g. "Tomorrow morning" */
  text: string;
  /** icon, as for snooze rows */
  icon: RowIcon;
  /** engine key, passed back to `commit` as `pickedKey` */
  keyId: string;
  /** learned weight; 0 for a default */
  score: number;
  /** whether it came from the user's history rather than the defaults */
  learned: boolean;
};

/** A suggestion as offered: the time the user chose and the time it will actually send. */
export type ShiftedTime = {
  /** the unshifted suggestion; the only time that feeds `sendStats` */
  chosenAt: number;
  /** what the menu shows and `outbox.sendAt` holds */
  sendAt: number;
};

/** "Vary scheduled send times" as stored in `users/{uid}.settings`. */
export type VarySetting = {
  /** whether suggested times are shifted (`settings.varySendTimes`, on when absent) */
  enabled: boolean;
  /** largest shift either way, whole minutes in 1..30 (`settings.varySendMaxMinutes`, 10 when absent) */
  maxMinutes: number;
};

/**
 * Read the vary setting from `users/{uid}.settings`: `varySendTimes` is on unless it is exactly
 * `false`; `varySendMaxMinutes` is rounded to a whole number and clamped to 1..30, and anything
 * that isn't a finite number reads as 10.
 */
export function varySetting(
  settings: Record<string, unknown> | undefined | null,
): VarySetting {
  const enabled = settings?.varySendTimes !== false;
  const stored = settings?.varySendMaxMinutes;
  const maxMinutes =
    typeof stored === "number" && Number.isFinite(stored)
      ? Math.min(
          MAX_MAX_SHIFT_MINUTES,
          Math.max(MIN_MAX_SHIFT_MINUTES, Math.round(stored)),
        )
      : DEFAULT_MAX_SHIFT_MINUTES;
  return { enabled, maxMinutes };
}

/** Firestore path of the learned send-time counts: `users/{uid}/meta/sendStats`, same format as `snoozeStats`. */
export function sendStatsPath(uid: string): [string, ...string[]] {
  return ["users", uid, "meta", "sendStats"];
}

/**
 * The schedule-send menu's rows.
 *
 * Learned rows come first in the engine's greedy order; the defaults in `SEND_DEFAULT_KEYS` fill
 * the remaining places, skipping any that would land within `DEFAULT_GAP_MS` of a row already
 * taken. The result is in time order. Labels and icons are the snooze menu's.
 */
export function sendRows(
  partitions: DevicePartition[],
  now: number,
  count = SEND_SHOWN_ROWS,
): SendRow[] {
  const rows: SendRow[] = rankDeep(partitions, now, count).map((row) => ({
    ...row,
    learned: true,
  }));
  const nowDate = new Date(now);
  for (const keyId of SEND_DEFAULT_KEYS) {
    if (rows.length >= count) break;
    const time = resolveKey(keyId, nowDate);
    if (time === null) continue;
    if (rows.some((row) => Math.abs(row.time - time) < DEFAULT_GAP_MS)) {
      continue;
    }
    rows.push({
      time,
      text: labelParts(keyId, time, nowDate).text,
      icon: rowIcon(keyId, time, nowDate),
      keyId,
      score: 0,
      learned: false,
    });
  }
  return rows.sort((first, second) => first.time - second.time);
}

/**
 * The shift for one uniform draw in [0, 1): a whole number of minutes in -max..-1 or 1..max, never
 * 0, so a varied time never lands on the suggestion itself. The 2·max choices split [0, 1) evenly
 * and map in order: with max 10, [0, 0.05) is -10, …, [0.45, 0.5) is -1, [0.5, 0.55) is +1, …,
 * [0.95, 1) is +10.
 */
export function shiftMinutes(
  unit: number,
  maxMinutes = DEFAULT_MAX_SHIFT_MINUTES,
): number {
  const choices = 2 * maxMinutes;
  const index = Math.min(choices - 1, Math.max(0, Math.floor(unit * choices)));
  return index < maxMinutes ? index - maxMinutes : index - maxMinutes + 1;
}

/**
 * Shift each suggested time for "Vary scheduled send times" by up to `maxMinutes` (the setting's
 * `maxMinutes`) either way, drawing one number from `random` per time, in order. A backward shift that would leave less than a minute before sending flips
 * forward instead. Only suggestions are varied; times typed into the picker, the last custom time
 * and undo send are used as they are.
 */
export function varySendTimes(
  times: readonly number[],
  now: number,
  random: () => number,
  maxMinutes = DEFAULT_MAX_SHIFT_MINUTES,
): ShiftedTime[] {
  return times.map((chosenAt) => {
    let minutes = shiftMinutes(random(), maxMinutes);
    if (chosenAt + minutes * MINUTE_MS <= now + MIN_LEAD_MS) {
      minutes = Math.abs(minutes);
    }
    return { chosenAt, sendAt: chosenAt + minutes * MINUTE_MS };
  });
}

/** The same times unshifted, for when the setting is off. */
export function unvariedSendTimes(times: readonly number[]): ShiftedTime[] {
  return times.map((time) => ({ chosenAt: time, sendAt: time }));
}

/**
 * A seeded random source (mulberry32) returning numbers in [0, 1). Seed it once per menu opening
 * so the shown times hold still while the menu is open; Android ports the same arithmetic.
 */
export function seededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let mixed = state;
    mixed = Math.imul(mixed ^ (mixed >>> 15), mixed | 1);
    mixed ^= mixed + Math.imul(mixed ^ (mixed >>> 7), mixed | 61);
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
}
