// Laying label changes still queued for Gmail over a row Gmail just sent, so a fresh copy doesn't
// undo them on screen. Web and Android (:core mail/QueuedRows.kt) both run
// shared/testdata/queued-row-fixtures.json.

import { inReplayOrder } from "./offline-queue";

/** Gmail's label for an unread message. */
const UNREAD = "UNREAD";

/** A label change waiting for Gmail, as laying it over a row reads it. */
export type QueuedLabelChange = {
  /** unique id, which orders changes numbered alike */
  id: string;
  /** position in the order the user made changes */
  seq: number;
  /** Gmail thread id */
  threadId: string;
  /** label ids it adds */
  add: readonly string[];
  /** label ids it removes */
  remove: readonly string[];
};

/** What a queued change touches of a row. */
export type QueuedRow = {
  /** Gmail thread id */
  id: string;
  /** its labels, sorted */
  labelIds: readonly string[];
  /** whether any of its messages is unread */
  unread: boolean;
};

/** A row's labels after a change: its removals, then its additions, sorted. */
export function labelsAfter(
  labelIds: readonly string[],
  change: Pick<QueuedLabelChange, "add" | "remove">,
): string[] {
  const out = new Set(labelIds);
  for (const label of change.remove) out.delete(label);
  for (const label of change.add) out.add(label);
  return [...out].sort();
}

/** A row's unread flag after a change: only a change to `UNREAD` itself moves it, and adding wins. */
export function unreadAfter(
  unread: boolean,
  change: Pick<QueuedLabelChange, "add" | "remove">,
): boolean {
  if (change.add.includes(UNREAD)) return true;
  if (change.remove.includes(UNREAD)) return false;
  return unread;
}

/**
 * `row` with every change in `queue` for its thread applied in replay order (`inReplayOrder`,
 * whatever order `queue` holds them in); `row` itself when none moves it.
 */
export function withQueued<Row extends QueuedRow>(
  row: Row,
  queue: readonly QueuedLabelChange[],
): Row {
  let labelIds: readonly string[] = row.labelIds;
  let unread = row.unread;
  for (const change of inReplayOrder(queue)) {
    if (change.threadId !== row.id) continue;
    labelIds = labelsAfter(labelIds, change);
    unread = unreadAfter(unread, change);
  }
  const same =
    unread === row.unread &&
    labelIds.length === row.labelIds.length &&
    labelIds.every((label, index) => label === row.labelIds[index]);
  return same ? row : { ...row, labelIds: [...labelIds], unread };
}
