// How a device brings its saved copy up to date from Gmail's history (`history.list`): label
// changes apply to saved messages directly, new mail on a saved thread is read message by message
// (a message never changes once sent, so the rest stays), threads entering the saved set are read
// whole (a new draft too, at any age), new mail on threads kept for no other reason is checked for encryption (encrypted mail is
// always kept), and threads whose messages were all deleted go. Android :core mail/HistoryApply.kt is the
// same; both run shared/testdata/history-apply-fixtures.json.

/** A message as a history record carries it. */
export type HistoryMessage = {
  /** Gmail message id */
  id: string;
  /** its thread */
  threadId: string;
  /** its labels, on an added message */
  labelIds?: readonly string[];
};

/** One `history.list` record, reduced to what the saved copy needs. */
export type HistoryRecord = {
  /** messages added */
  messagesAdded?: readonly { message: HistoryMessage }[];
  /** messages deleted for good */
  messagesDeleted?: readonly { message: HistoryMessage }[];
  /** labels added to messages */
  labelsAdded?: readonly {
    message: HistoryMessage;
    labelIds?: readonly string[];
  }[];
  /** labels removed from messages */
  labelsRemoved?: readonly {
    message: HistoryMessage;
    labelIds?: readonly string[];
  }[];
};

/** A saved thread's messages: message id to its label ids. */
export type SavedMessages = Readonly<Record<string, readonly string[]>>;

/** What history means for the saved copy. */
export type HistoryChanges = {
  /** saved threads whose labels or messages changed, with every message's labels after (new ones too) */
  relabeled: Record<string, Record<string, string[]>>;
  /** saved threads' new messages to read one by one and add to their saved bodies, in the order history added them */
  added: Record<string, string[]>;
  /** saved threads whose every message was deleted */
  removed: string[];
  /**
   * threads to read whole: new mail on a newly kept thread, one entering the inbox, or a label
   * change on a saved thread's message the copy doesn't know
   */
  fetch: string[];
  /** other threads history named, whose list rows may be out of date */
  touched: string[];
  /**
   * threads among `touched` with new mail (or out of spam or trash) that no other reason keeps:
   * read only far enough to tell whether they are encrypted, and read whole when they are
   */
  check: string[];
};

const ENTERING = "INBOX";
// Labels that bring a thread into the saved set at any age.
const KEPT: ReadonlySet<string> = new Set([ENTERING, "DRAFT"]);
const HIDDEN: ReadonlySet<string> = new Set(["SPAM", "TRASH"]);

/**
 * Apply history `records`, oldest first, to the saved threads `saved` returns (undefined for one
 * not saved). With `days` 0 only the inbox, drafts and snoozed threads are kept, so new mail
 * elsewhere isn't read. Each id is in at most one of the lists, in the order history first named it.
 */
export function applyHistory(
  records: readonly HistoryRecord[],
  saved: (threadId: string) => SavedMessages | undefined,
  days: number,
): HistoryChanges {
  const working = new Map<string, Map<string, string[]>>();
  const added = new Map<string, string[]>();
  const changed = new Set<string>();
  const fetch = new Set<string>();
  const touched = new Set<string>();
  const check = new Set<string>();
  const order: string[] = [];
  const seen = new Set<string>();

  const note = (threadId: string) => {
    if (!seen.has(threadId)) {
      seen.add(threadId);
      order.push(threadId);
    }
  };
  const messagesOf = (threadId: string): Map<string, string[]> | null => {
    const known = working.get(threadId);
    if (known !== undefined) return known;
    const stored = saved(threadId);
    if (stored === undefined) return null;
    const copy = new Map(
      Object.entries(stored).map(([id, labels]) => [id, [...labels]]),
    );
    working.set(threadId, copy);
    return copy;
  };
  const read = (threadId: string) => {
    fetch.add(threadId);
    added.delete(threadId);
    touched.delete(threadId);
    check.delete(threadId);
  };
  const touch = (threadId: string) => {
    if (!fetch.has(threadId)) touched.add(threadId);
  };
  const relabel = (
    message: HistoryMessage,
    labelIds: readonly string[],
    adding: boolean,
  ) => {
    const threadId = message.threadId;
    note(threadId);
    if (fetch.has(threadId)) return;
    const messages = messagesOf(threadId);
    if (messages === null) {
      const entering = adding
        ? labelIds.some((label) => KEPT.has(label))
        : days > 0 && labelIds.some((label) => HIDDEN.has(label));
      if (entering) read(threadId);
      else touch(threadId);
      if (!entering && !adding && labelIds.some((label) => HIDDEN.has(label))) {
        check.add(threadId);
      }
      return;
    }
    const labels = messages.get(message.id);
    if (labels === undefined) {
      read(threadId);
      return;
    }
    const next = adding
      ? [...labels, ...labelIds.filter((label) => !labels.includes(label))]
      : labels.filter((label) => !labelIds.includes(label));
    if (next.length !== labels.length) {
      messages.set(message.id, next);
      changed.add(threadId);
    }
  };

  for (const record of records) {
    for (const { message } of record.messagesAdded ?? []) {
      const threadId = message.threadId;
      note(threadId);
      if (fetch.has(threadId)) continue;
      const labels = message.labelIds ?? [];
      const messages = messagesOf(threadId);
      if (messages !== null) {
        if (!messages.has(message.id)) {
          messages.set(message.id, [...labels]);
          added.set(threadId, [...(added.get(threadId) ?? []), message.id]);
          changed.add(threadId);
        }
        continue;
      }
      const keeps =
        labels.some((label) => KEPT.has(label)) ||
        (days > 0 && !labels.some((label) => HIDDEN.has(label)));
      if (keeps) {
        read(threadId);
      } else {
        touch(threadId);
        if (!labels.some((label) => HIDDEN.has(label))) check.add(threadId);
      }
    }
    for (const { message, labelIds } of record.labelsAdded ?? []) {
      relabel(message, labelIds ?? [], true);
    }
    for (const { message, labelIds } of record.labelsRemoved ?? []) {
      relabel(message, labelIds ?? [], false);
    }
    for (const { message } of record.messagesDeleted ?? []) {
      const threadId = message.threadId;
      note(threadId);
      if (fetch.has(threadId)) continue;
      const messages = messagesOf(threadId);
      if (messages === null) touch(threadId);
      else if (messages.delete(message.id)) {
        changed.add(threadId);
        const fresh = added.get(threadId)?.filter((id) => id !== message.id);
        if (fresh !== undefined && fresh.length > 0) added.set(threadId, fresh);
        else added.delete(threadId);
      }
    }
  }

  const relabeled: Record<string, Record<string, string[]>> = {};
  const addedOut: Record<string, string[]> = {};
  const removed: string[] = [];
  for (const threadId of order) {
    if (fetch.has(threadId) || !changed.has(threadId)) continue;
    const messages = working.get(threadId)!;
    if (messages.size === 0) removed.push(threadId);
    else {
      relabeled[threadId] = Object.fromEntries(messages);
      const fresh = added.get(threadId);
      if (fresh !== undefined) addedOut[threadId] = fresh;
    }
  }
  return {
    relabeled,
    added: addedOut,
    removed,
    fetch: order.filter((threadId) => fetch.has(threadId)),
    touched: order.filter((threadId) => touched.has(threadId)),
    check: order.filter(
      (threadId) => check.has(threadId) && touched.has(threadId),
    ),
  };
}

/**
 * The order to read `fetch` (threads to read whole, from `applyHistory`): those history put in the
 * inbox (new mail carrying INBOX, or INBOX added) first, then the rest, each most recently named
 * first, so the inbox's newest mail is saved before older and other mail.
 */
export function readOrder(
  records: readonly HistoryRecord[],
  fetch: readonly string[],
): string[] {
  const inbox = new Set<string>();
  const lastNamed = new Map<string, number>();
  records.forEach((record, position) => {
    for (const { message } of record.messagesAdded ?? []) {
      lastNamed.set(message.threadId, position);
      if (message.labelIds?.includes(ENTERING)) inbox.add(message.threadId);
    }
    for (const { message, labelIds } of record.labelsAdded ?? []) {
      lastNamed.set(message.threadId, position);
      if (labelIds?.includes(ENTERING)) inbox.add(message.threadId);
    }
    for (const { message } of [
      ...(record.labelsRemoved ?? []),
      ...(record.messagesDeleted ?? []),
    ]) {
      lastNamed.set(message.threadId, position);
    }
  });
  const rank = (threadId: string) => (inbox.has(threadId) ? 0 : 1);
  return [...fetch].sort(
    (left, right) =>
      rank(left) - rank(right) ||
      (lastNamed.get(right) ?? -1) - (lastNamed.get(left) ?? -1),
  );
}

/** A thread's labels: every label on any of its messages, in first-seen order. */
export function threadLabels(messages: SavedMessages): string[] {
  const labels = new Set<string>();
  for (const messageLabels of Object.values(messages)) {
    for (const label of messageLabels) labels.add(label);
  }
  return [...labels];
}
