import {
  type DevicePartition,
  KEY_RE,
  PICK_RE,
  type SetEntry,
  SLOT_RE,
  setHalfLifeDays,
  splitSetId,
  TOD_HALF_LIFE_DAYS,
} from "./snooze-suggest";

const DAY_MS = 24 * 60 * 60 * 1000;

// A user's shared counts are saved scaled up by how late each vote was cast, doubling every half-life from this instant, so a vote is a plain addition and no time is saved. Doubles overflow about 59 years on.
export const VOTE_EPOCH = Date.UTC(2026, 0, 1);

// The field of the shared counts doc holding set counts. `sets` holds counts scaled by an earlier half-life rule, which latr's apps still write and nothing here reads.
export const SHARED_SETS_FIELD = "setsV2";

// Amounts to add to the shared counts, in their scaled units.
export type VoteDeltas = {
  sets: Record<string, number>;
  tod: Record<string, number>;
};

function isRecord(raw: unknown): raw is Record<string, unknown> {
  return typeof raw === "object" && raw !== null;
}

function normalizeLastCustom(raw: unknown): DevicePartition["lastCustom"] {
  if (isRecord(raw) && Number.isFinite(raw.target) && Number.isFinite(raw.at)) {
    return { target: raw.target as number, at: raw.at as number };
  }
  return null;
}

function normalizePicks(raw: unknown): Record<string, number> {
  const out: Record<string, number> = {};
  if (!isRecord(raw)) return out;
  for (const [key, count] of Object.entries(raw)) {
    if (
      PICK_RE.test(key) &&
      Number.isSafeInteger(count) &&
      (count as number) > 0
    ) {
      out[key] = count as number;
    }
  }
  return out;
}

function scaled(entry: SetEntry | undefined, halfLifeDays: number): number {
  if (!entry) return 0;
  return entry.c * 2 ** ((entry.t - VOTE_EPOCH) / (halfLifeDays * DAY_MS));
}

function sharedCounters(
  raw: unknown,
  validId: (id: string) => boolean,
): Record<string, SetEntry> {
  const out: Record<string, SetEntry> = {};
  if (!isRecord(raw)) return out;
  for (const [id, count] of Object.entries(raw)) {
    if (validId(id) && Number.isFinite(count) && (count as number) > 0) {
      out[id] = { c: count as number, t: VOTE_EPOCH };
    }
  }
  return out;
}

/** Parses a learned counts doc (`users/{uid}/meta/snoozeStats`, or schedule send's own): each scaled count reads as that count at `VOTE_EPOCH`; counts at or below 0 are dropped. */
export function sharedFromWire(raw: unknown): DevicePartition {
  const record = isRecord(raw) ? raw : {};
  return {
    deviceId: "shared",
    sets: sharedCounters(record[SHARED_SETS_FIELD], (id) =>
      splitSetId(id).every((key) => KEY_RE.test(key)),
    ),
    tod: sharedCounters(record.tod, (slot) => SLOT_RE.test(slot)),
    lastCustom: normalizeLastCustom(record.lastCustom),
    picks: normalizePicks(record.picks),
  };
}

function counterDeltas(
  before: Record<string, SetEntry>,
  after: Record<string, SetEntry>,
  halfLifeOf: (id: string) => number,
): Record<string, number> {
  const out: Record<string, number> = {};
  for (const id of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (before[id] === after[id]) continue;
    const halfLife = halfLifeOf(id);
    const delta = scaled(after[id], halfLife) - scaled(before[id], halfLife);
    if (delta !== 0) out[id] = delta;
  }
  return out;
}

/** What to add to the shared counts to take them from `before` to `after`. */
export function voteDeltas(
  before: DevicePartition,
  after: DevicePartition,
): VoteDeltas {
  return {
    sets: counterDeltas(before.sets, after.sets, setHalfLifeDays),
    tod: counterDeltas(before.tod, after.tod, () => TOD_HALF_LIFE_DAYS),
  };
}

export function negated(deltas: VoteDeltas): VoteDeltas {
  const flip = (counts: Record<string, number>) =>
    Object.fromEntries(Object.entries(counts).map(([id, d]) => [id, -d]));
  return { sets: flip(deltas.sets), tod: flip(deltas.tod) };
}

/** `snoozePickLog/global` as 21 counts: index 0 is `none`, 1..20 the ranks; missing or non-integer fields read 0. */
export function globalPickCounts(raw: unknown): number[] {
  const data = isRecord(raw) ? raw : {};
  return Array.from({ length: 21 }, (_, position) => {
    const value = data[position === 0 ? "none" : `${position}`];
    return Number.isInteger(value) ? (value as number) : 0;
  });
}
