// A scheduled send stays a Gmail draft until it goes (the server sends from the draft), but it
// shows only in the Outbox: Drafts, its count and a thread's drafts leave out every draft an outbox
// doc not yet sent names. Gmail's listing of drafts lags: a send this device queued or just wrote,
// and a doc newer than the listing, would show in Drafts and the Outbox at once until it comes
// back, so those are left out by what the device knows of them. Web uses it directly; Android
// ports it to :core (OutboxDrafts.kt). Both run shared/testdata/outbox-draft-fixtures.json.

/** A Gmail draft as `drafts.list` gives it. */
export type GmailDraftRef = {
  /** Gmail draft id */
  draftId: string;
  /** Gmail message id of its message */
  messageId: string;
  /** Gmail thread id of its message */
  threadId: string;
};

/** An outbox doc as this rule sees it. */
export type OutboxDraftDoc = {
  /** the Gmail draft it sends */
  draftId: string;
  /** `waiting`, `sending`, `retrying`, `blocked`, `sent` or `failed` */
  status: string;
  /** the draft's Gmail thread, when the doc names it */
  threadId?: string | null;
};

/** A draft this device is sending: queued here, or its outbox doc just written. */
export type SendingDraft = {
  /** its Gmail draft id, once saved there */
  draftId: string | null;
  /** its Gmail message id as last saved, when known */
  messageId: string | null;
  /** its Gmail thread, when known */
  threadId: string | null;
};

/** The Gmail drafts the Outbox holds. */
export type OutboxDrafts = {
  /** Gmail message ids of the drafts, hidden among a thread's drafts */
  messageIds: ReadonlySet<string>;
  /** threads whose every draft is in the Outbox, left out of Drafts and its count */
  threadIds: ReadonlySet<string>;
};

/** No drafts in the Outbox. */
export const NO_OUTBOX_DRAFTS: OutboxDrafts = {
  messageIds: new Set(),
  threadIds: new Set(),
};

/** The draft ids outbox docs name that the outbox still holds: every doc but a sent one. */
export function outboxDraftIds(docs: Iterable<OutboxDraftDoc>): Set<string> {
  const ids = new Set<string>();
  for (const doc of docs) {
    if (doc.status !== "sent") ids.add(doc.draftId);
  }
  return ids;
}

/**
 * Which of Gmail's drafts the Outbox holds: those outbox docs (`docs`) or this device's sends
 * (`sending`) name. `drafts` is Gmail's last listing and `listedFor` the drafts the Outbox held
 * when it was made. A held draft that listing leaves out but wasn't made for (it is newer) still
 * leaves Drafts, by the thread the device or its doc names; one it was made for is gone.
 */
export function outboxDrafts(
  drafts: readonly GmailDraftRef[],
  docs: Iterable<OutboxDraftDoc>,
  sending: readonly SendingDraft[] = [],
  listedFor: ReadonlySet<string> = new Set(),
): OutboxDrafts {
  const docList = [...docs];
  const held = outboxDraftIds(docList);
  for (const send of sending) {
    if (send.draftId !== null) held.add(send.draftId);
  }
  const messageIds = new Set<string>();
  const outboxThreads = new Set<string>();
  const draftThreads = new Set<string>();
  const listed = new Set<string>();
  for (const draft of drafts) {
    listed.add(draft.draftId);
    if (held.has(draft.draftId)) {
      messageIds.add(draft.messageId);
      outboxThreads.add(draft.threadId);
    } else {
      draftThreads.add(draft.threadId);
    }
  }
  // A draft not yet saved isn't in Drafts at all.
  const unlisted = (draftId: string | null) =>
    draftId !== null && !listed.has(draftId) && !listedFor.has(draftId);
  for (const send of sending) {
    if (send.messageId !== null) messageIds.add(send.messageId);
    if (send.threadId !== null && unlisted(send.draftId)) {
      outboxThreads.add(send.threadId);
    }
  }
  for (const doc of docList) {
    if (doc.status === "sent" || !doc.threadId || !unlisted(doc.draftId)) {
      continue;
    }
    outboxThreads.add(doc.threadId);
  }
  const threadIds = new Set(
    [...outboxThreads].filter((threadId) => !draftThreads.has(threadId)),
  );
  return { messageIds, threadIds };
}

/** Drafts' count: Gmail's thread count for the `DRAFT` label less the threads only the Outbox holds. */
export function draftThreadCount(
  labelThreads: number,
  outbox: OutboxDrafts,
): number {
  return Math.max(0, labelThreads - outbox.threadIds.size);
}

/**
 * Whether a thread shows only in the Outbox: it has no message but drafts (`sentOrReceived` is
 * how many it has that aren't drafts) and every draft in it is one the Outbox holds.
 */
export function onlyInOutbox(
  threadId: string,
  sentOrReceived: number,
  outbox: OutboxDrafts,
): boolean {
  return sentOrReceived === 0 && outbox.threadIds.has(threadId);
}
