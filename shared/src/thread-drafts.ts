/** A message of a Gmail thread as the draft rule sees it. */
export type GmailThreadMessage = {
  /** Gmail message id */
  id: string;
  /** whether Gmail has it as a draft (the `DRAFT` label) */
  draft: boolean;
};

/** Where a draft this device holds stands. */
export type LocalDraftState =
  /** being written, or kept to finish later */
  | "draft"
  /** thrown away; Gmail's copy may not be deleted yet */
  | "deleted"
  /** sent from this device; Gmail hasn't shown the sent message yet */
  | "sending";

/** A draft of the thread this device holds. */
export type LocalThreadDraft = {
  /** the draft's id on this device */
  localId: string;
  /** Gmail message id of the draft as last saved there; null before it reaches Gmail */
  messageId: string | null;
  /** where it stands */
  state: LocalDraftState;
  /** when it last changed, epoch milliseconds */
  updatedAt: number;
};

/** One entry of a thread as it shows. */
export type ThreadEntry =
  /** a sent or received message, Gmail's copy */
  | { kind: "message"; id: string }
  /** a draft only Gmail has */
  | { kind: "draft"; id: string }
  /** a draft (or a message being sent) as this device holds it */
  | { kind: "local"; localId: string; sending: boolean };

/**
 * The entries a thread shows, in order, from Gmail's messages and this device's drafts.
 *
 * Gmail's messages keep their order (`messages` oldest first). A Gmail draft that a local draft
 * saved (same message id) shows the local one in its place, or nothing when it was deleted. A sent
 * message Gmail has is shown as Gmail's, whatever the device holds for it. Local drafts Gmail
 * doesn't have yet (not deleted) go at the end, oldest change first. A draft in the Outbox (its
 * Gmail message id in `outbox`, see `outboxDrafts`) shows nowhere here, Gmail's copy or the
 * device's, unless the device holds it as sending.
 */
export function threadEntries(
  messages: readonly GmailThreadMessage[],
  local: readonly LocalThreadDraft[],
  outbox: ReadonlySet<string> = new Set(),
): ThreadEntry[] {
  const byMessage = new Map<string, LocalThreadDraft>();
  for (const draft of local) {
    if (draft.messageId !== null) byMessage.set(draft.messageId, draft);
  }
  const placed = new Set<string>();
  const entries: ThreadEntry[] = [];
  for (const message of messages) {
    const held = byMessage.get(message.id);
    if (held !== undefined) placed.add(held.localId);
    if (!message.draft) {
      entries.push({ kind: "message", id: message.id });
    } else if (held === undefined) {
      if (!outbox.has(message.id)) {
        entries.push({ kind: "draft", id: message.id });
      }
    } else if (shows(held, outbox)) {
      entries.push(localEntry(held));
    }
  }
  const unplaced = local
    .filter((draft) => !placed.has(draft.localId) && shows(draft, outbox))
    .sort(
      (left, right) =>
        left.updatedAt - right.updatedAt ||
        (left.localId < right.localId
          ? -1
          : left.localId > right.localId
            ? 1
            : 0),
    );
  for (const draft of unplaced) entries.push(localEntry(draft));
  return entries;
}

function shows(draft: LocalThreadDraft, outbox: ReadonlySet<string>): boolean {
  if (draft.state === "deleted") return false;
  return (
    draft.state === "sending" ||
    draft.messageId === null ||
    !outbox.has(draft.messageId)
  );
}

function localEntry(draft: LocalThreadDraft): ThreadEntry {
  return {
    kind: "local",
    localId: draft.localId,
    sending: draft.state === "sending",
  };
}

const SNIPPET_LENGTH = 200;

/** A draft's first words for its line in the thread: its markdown body as plain text. */
export function draftSnippet(markdown: string): string {
  return markdown
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[*_~`#>\\]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, SNIPPET_LENGTH);
}

/**
 * The thread whose draft opens in place of a thread a tap on its row, or Enter on it, just opened:
 * in Drafts a row opens its draft in the composer, a reply's too, and the thread stays closed. Null
 * outside Drafts, for any other step (a thread opened from outside the list opens as usual), or
 * when the step opened nothing new.
 */
export function draftsOpening(
  inDrafts: boolean,
  event: { type: string },
  openBefore: string | null,
  openAfter: string | null,
): string | null {
  const fromRow = event.type === "tap" || event.type === "enter";
  return inDrafts && fromRow && openAfter !== null && openAfter !== openBefore
    ? openAfter
    : null;
}
