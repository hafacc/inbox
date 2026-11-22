// How long an ended snooze still counts, and the check for newer mail on snoozed threads: where its
// history scan starts, whether a scan cut short goes on, and which of its findings still hold. Web
// and Android (:core Wake.kt) both run shared/testdata/wake-fixtures.json.

/** How long after a snooze ends the thread still sorts by that end, shows in the inbox and is checked. */
export const LAPSE_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

/** Whether a snooze (epoch ms, or null for none) is still ahead or ended within {@link LAPSE_WINDOW_MS}. */
export function inLapseWindow(
  snoozeUntil: number | null,
  now: number,
): boolean {
  return snoozeUntil !== null && snoozeUntil > now - LAPSE_WINDOW_MS;
}

/** Whether a snooze ended within {@link LAPSE_WINDOW_MS}, so the thread is back in the inbox. */
export function recentlyLapsed(
  snoozeUntil: number | null,
  now: number,
): boolean {
  return (
    snoozeUntil !== null &&
    snoozeUntil <= now &&
    inLapseWindow(snoozeUntil, now)
  );
}

/** Compare two Gmail history ids, which are decimal strings too long for a double. */
export function compareHistoryIds(left: string, right: string): number {
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  if (leftValue === rightValue) return 0;
  return leftValue < rightValue ? -1 : 1;
}

/** The lowest of some history ids, or null when there are none. */
export function lowestHistoryId(ids: Iterable<string>): string | null {
  let lowest: string | null = null;
  for (const id of ids) {
    if (lowest === null || compareHistoryIds(id, lowest) < 0) lowest = id;
  }
  return lowest;
}

/** Where a check's history scan starts, and whether it goes on with the scan cut short before. */
export type ScanStart = {
  /** the history id the scan reads from */
  start: string;
  /** whether it goes on from the earlier scan's next page, keeping what that one read */
  resume: boolean;
};

/**
 * Where the scan for newer mail on threads snoozed at `watermarks` (thread id to history id) starts,
 * given where a scan cut short by its page limit started (null when none was). That scan goes on
 * while it started at or below every watermark; otherwise a new one starts at the lowest. Null when
 * there is nothing to check.
 */
export function scanStart(
  watermarks: ReadonlyMap<string, string>,
  unfinishedStart: string | null,
): ScanStart | null {
  const lowest = lowestHistoryId(watermarks.values());
  if (lowest === null) return null;
  if (
    unfinishedStart !== null &&
    compareHistoryIds(unfinishedStart, lowest) <= 0
  ) {
    return { start: unfinishedStart, resume: true };
  } else {
    return { start: lowest, resume: false };
  }
}

/**
 * The threads a finished scan found newer mail on that it still speaks for: those whose watermark
 * is the same now (`current`) as when the scan read it (`scanned`). A thread snoozed again
 * meanwhile has a new watermark, so it stays snoozed until the next scan.
 */
export function stillFound(
  found: ReadonlySet<string>,
  scanned: ReadonlyMap<string, string>,
  current: ReadonlyMap<string, string>,
): Set<string> {
  const kept = new Set<string>();
  for (const id of found) {
    const watermark = current.get(id);
    if (watermark !== undefined && watermark === scanned.get(id)) kept.add(id);
  }
  return kept;
}
