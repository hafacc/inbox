import {
  type GmailHistoryRecord,
  type GmailMessage,
  LABEL,
} from "./gmail-api.js";

/** What Gmail publishes to Pub/Sub when a watched mailbox changes. */
export interface GmailPush {
  /** the mailbox's address */
  emailAddress: string;
  /** the mailbox's history id after the change */
  historyId: string;
}

/** What one batch of history records asks the server to do. */
export interface HistoryOutcome {
  /** new inbox messages from others, to notify about, oldest first */
  notifyMessageIds: string[];
  /** threads with a message read or taken out of the inbox, whose notifications phones clear */
  clearedThreadIds: string[];
}

/** Parse a Pub/Sub message body from Gmail, returning undefined when malformed. */
export function parseGmailPush(data: unknown): GmailPush | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const { emailAddress, historyId } = data as Record<string, unknown>;
  if (typeof emailAddress !== "string" || emailAddress === "") {
    return undefined;
  }
  const id =
    typeof historyId === "number" ? String(historyId) : (historyId ?? "");
  if (typeof id !== "string" || !/^\d+$/.test(id)) {
    return undefined;
  } else {
    return { emailAddress: emailAddress.toLowerCase(), historyId: id };
  }
}

/** Compare two Gmail history ids, which are decimal strings too long for a double. */
export function compareHistoryIds(left: string, right: string): number {
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  if (leftValue === rightValue) {
    return 0;
  } else {
    return leftValue < rightValue ? -1 : 1;
  }
}

/** Whether a message with these labels is new inbox mail from someone else. */
export function isNotifiable(labelIds: readonly string[]): boolean {
  return (
    labelIds.includes(LABEL.inbox) &&
    !labelIds.includes(LABEL.draft) &&
    !labelIds.includes(LABEL.sent) &&
    !labelIds.includes(LABEL.spam) &&
    !labelIds.includes(LABEL.trash)
  );
}

/**
 * Messages that gained INBOX in these records. Gmail can report a message's current labels in
 * `messagesAdded` rather than those it arrived with, so one of these arrived without INBOX.
 */
function messagesGainingInbox(
  records: readonly GmailHistoryRecord[],
): Set<string> {
  const gained = new Set<string>();
  for (const record of records) {
    for (const { message, labelIds } of record.labelsAdded ?? []) {
      if (labelIds?.includes(LABEL.inbox)) gained.add(message.id);
    }
  }
  return gained;
}

/**
 * The labels a message arrived with, as far as these records tell: without INBOX when a record adds
 * it later.
 */
function arrivalLabels(
  message: GmailMessage,
  gainedInbox: ReadonlySet<string>,
): string[] {
  const labels = message.labelIds ?? [];
  return gainedInbox.has(message.id)
    ? labels.filter((label) => label !== LABEL.inbox)
    : labels;
}

/**
 * Decide what `history.list` records ask for: notify new inbox mail from others, judged by each
 * message's labels as it arrived; and clear the notifications of threads where a message lost
 * `UNREAD` or `INBOX` (read or archived elsewhere), but for threads this batch notifies about.
 */
export function analyzeHistory(
  records: readonly GmailHistoryRecord[],
): HistoryOutcome {
  const gainedInbox = messagesGainingInbox(records);
  const notify = new Set<string>();
  const notifiedThreads = new Set<string>();
  const cleared = new Set<string>();
  for (const record of records) {
    for (const { message } of record.messagesAdded ?? []) {
      if (isNotifiable(arrivalLabels(message, gainedInbox))) {
        notify.add(message.id);
        notifiedThreads.add(message.threadId);
      }
    }
    for (const { message, labelIds } of record.labelsRemoved ?? []) {
      if (labelIds?.includes(LABEL.unread) || labelIds?.includes(LABEL.inbox)) {
        cleared.add(message.threadId);
      }
    }
  }
  return {
    notifyMessageIds: [...notify],
    clearedThreadIds: [...cleared].filter((id) => !notifiedThreads.has(id)),
  };
}

/**
 * Decide the same from recent inbox messages, for when the stored history id is older than Gmail
 * keeps: notify messages that arrived after the last sync (none when there was none). Without
 * history only current labels are known, so they stand in for those the message arrived with.
 */
export function analyzeRecent(
  messages: readonly GmailMessage[],
  lastSyncAt: number | undefined,
): HistoryOutcome {
  const notify = new Set<string>();
  const oldestFirst = [...messages].sort(
    (left, right) =>
      Number(left.internalDate ?? 0) - Number(right.internalDate ?? 0),
  );
  for (const message of oldestFirst) {
    const arrivedAt = Number(message.internalDate ?? 0);
    if (
      lastSyncAt !== undefined &&
      arrivedAt > lastSyncAt &&
      isNotifiable(message.labelIds ?? [])
    ) {
      notify.add(message.id);
    }
  }
  return { notifyMessageIds: [...notify], clearedThreadIds: [] };
}
