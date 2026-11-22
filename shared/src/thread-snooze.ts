// A snoozed thread's state, derived and never written when a snooze ends (latr's rule, though this
// code is this app's own, not a latr copy): its status, the date it sorts by, newer mail found by a
// history scan, and whether Gmail shows it archived elsewhere. Android :core mail/SnoozeState.kt is
// the same; both run shared/testdata/thread-snooze-fixtures.json.

import type { HistoryRecord } from "./history-apply";
import { compareHistoryIds } from "./wake";

const INBOX = "INBOX";
const DRAFT = "DRAFT";
const SENT = "SENT";
const SPAM = "SPAM";
const TRASH = "TRASH";

/** A `history.list` record with its history id, as the scan for newer mail reads it. */
export type ScannedRecord = HistoryRecord & {
  /** the record's history id */
  id: string;
};

/**
 * `users/{uid}/threads/{threadId}` as this app reads it. Stored, `snoozeUntil` is a local wall time
 * with no zone (`YYYY-MM-DDTHH:mm:ss`, as in latr), read in this device's zone.
 */
export type SnoozeDoc = {
  /** when the snooze ends, epoch milliseconds read in this device's zone; null when not snoozed */
  snoozeUntil: number | null;
  /** the thread's Gmail historyId when it was snoozed */
  snoozeWatermark: string | null;
};

/**
 * Where a thread stands with its snooze: `none` (never snoozed or cleared), `snoozed` (still
 * hidden), `lapsed` (the time passed with no new message), `woken` (a newer message arrived in the inbox).
 */
export type SnoozeStatus = "none" | "snoozed" | "lapsed" | "woken";

/** A thread's snooze status at `now`; derived, so nothing is written when a snooze ends. */
export function snoozeStatus(
  doc: SnoozeDoc | undefined,
  now: number,
  hasNewerMessage: boolean,
): SnoozeStatus {
  if (!doc || doc.snoozeUntil === null) return "none";
  if (hasNewerMessage) return "woken";
  if (doc.snoozeUntil > now) return "snoozed";
  return "lapsed";
}

/** Snoozed threads that got a message after their watermark, by where the message arrived. */
export type NewerMail = {
  /** threads a newer message arrived in the inbox in; their snoozes end */
  inInbox: Set<string>;
  /** threads a newer message arrived in outside the inbox (a Gmail filter skipped it); still snoozed */
  skippedInbox: Set<string>;
};

/**
 * Sort the messages `history.list` records added after each thread's watermark by whether they
 * arrived in the inbox.
 *
 * Labels are per message, so only the new message's own labels count. Gmail can report a message's
 * current labels rather than those it arrived with, so a message that gains INBOX in a later record
 * of the same scan arrived without it. Drafts, spam, trash and mail the account sent itself (unless
 * it landed in the inbox, a note to self) count as neither.
 */
export function newerMail(
  records: readonly ScannedRecord[],
  watermarks: ReadonlyMap<string, string>,
): NewerMail {
  const gainedInbox = new Set<string>();
  for (const record of records) {
    for (const { message, labelIds } of record.labelsAdded ?? []) {
      if (labelIds?.includes(INBOX)) gainedInbox.add(message.id);
    }
  }
  const inInbox = new Set<string>();
  const skippedInbox = new Set<string>();
  for (const record of records) {
    for (const { message } of record.messagesAdded ?? []) {
      const watermark = watermarks.get(message.threadId);
      if (watermark === undefined) continue;
      if (compareHistoryIds(record.id, watermark) <= 0) continue;
      const labels = message.labelIds ?? [];
      const arrivedInInbox =
        labels.includes(INBOX) && !gainedInbox.has(message.id);
      if (arrivedInInbox && !labels.includes(DRAFT)) {
        inInbox.add(message.threadId);
      } else if (
        ![DRAFT, SENT, SPAM, TRASH].some((label) => labels.includes(label))
      ) {
        skippedInbox.add(message.threadId);
      }
    }
  }
  return { inInbox, skippedInbox };
}

/** The date a thread sorts by in the inbox: its newest message, or when its snooze ended if later. */
export function inboxSortDate(
  lastDate: number,
  doc: SnoozeDoc | undefined,
  status: SnoozeStatus,
): number {
  if (status === "lapsed" && doc?.snoozeUntil != null) {
    return Math.max(lastDate, doc.snoozeUntil);
  }
  return lastDate;
}

/**
 * Whether a fresh Gmail copy shows a still-snoozed thread was archived elsewhere (in Gmail itself,
 * say), which counts as Done, so its snooze should be dropped. A snoozed thread stays in the inbox.
 *
 * Only for a snooze still in the future, only when this device has no change of its own on the
 * thread still on its way to Gmail, and only for a copy newer than the snooze: a copy at or below the
 * watermark predates the snooze (a thread snoozed from outside the inbox gains INBOX after it).
 */
export function snoozeArchivedElsewhere(
  doc: SnoozeDoc | undefined,
  gmailLabelIds: readonly string[],
  gmailHistoryId: string | undefined,
  now: number,
  hasLocalChange: boolean,
): boolean {
  if (!doc || doc.snoozeUntil === null || doc.snoozeUntil <= now) return false;
  if (hasLocalChange || gmailLabelIds.includes(INBOX)) return false;
  if (doc.snoozeWatermark === null || gmailHistoryId === undefined) {
    return false;
  }
  return compareHistoryIds(gmailHistoryId, doc.snoozeWatermark) > 0;
}
