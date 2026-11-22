import type { GmailHistoryRecord } from "./types";

// Gmail's label on draft messages.
const DRAFT = "DRAFT";

/**
 * Whether Gmail history records may have changed the account's drafts: a draft message added or
 * deleted (a save replaces the draft's message), or the draft label added to or taken off one. A
 * deleted message without its labels may have been a draft, so it counts.
 */
export function touchesDrafts(records: readonly GmailHistoryRecord[]): boolean {
  return records.some(
    (record) =>
      (record.messagesAdded ?? []).some(({ message }) =>
        message.labelIds?.includes(DRAFT),
      ) ||
      (record.messagesDeleted ?? []).some(
        ({ message }) =>
          message.labelIds === undefined || message.labelIds.includes(DRAFT),
      ) ||
      [...(record.labelsAdded ?? []), ...(record.labelsRemoved ?? [])].some(
        (change) => change.labelIds?.includes(DRAFT),
      ),
  );
}
