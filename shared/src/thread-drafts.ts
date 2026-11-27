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

/** What the device knows of its drafts, for `heldDraftStates`. */
export type HeldDraftsInput = {
  /** drafts the device keeps a record of, with whether the record is a delete waiting for Gmail */
  kept: readonly { localId: string; deleted: boolean }[];
  /** drafts thrown away here: in their undo window, or their delete in Gmail not done */
  thrown: readonly string[];
  /** drafts being sent from here, until Gmail shows the sent message */
  sending: readonly string[];
};

/** A draft the device holds, as `heldDraftStates` gives it. */
export type HeldDraftState = {
  /** the draft's id on this device */
  localId: string;
  /** where it stands */
  state: LocalDraftState;
  /** whether the device keeps a record of it (else only this session's memory knows it) */
  kept: boolean;
};

/**
 * The drafts this device holds and where each stands. Every kept record is one: deleted when it
 * is a delete or was thrown away here, else sending when it is being sent, else a draft. A draft
 * with no record is held only while thrown away (deleted) or sending, so it is never a draft:
 * only a draft whose record the device still keeps can be listed and opened. Kept records come
 * first in their order, then the rest, thrown away before sending, each once.
 */
export function heldDraftStates(input: HeldDraftsInput): HeldDraftState[] {
  const thrown = new Set(input.thrown);
  const sending = new Set(input.sending);
  const seen = new Set<string>();
  const held: HeldDraftState[] = [];
  for (const record of input.kept) {
    if (seen.has(record.localId)) continue;
    seen.add(record.localId);
    const state: LocalDraftState =
      record.deleted || thrown.has(record.localId)
        ? "deleted"
        : sending.has(record.localId)
          ? "sending"
          : "draft";
    held.push({ localId: record.localId, state, kept: true });
  }
  for (const localId of [...input.thrown, ...input.sending]) {
    if (seen.has(localId)) continue;
    seen.add(localId);
    const state = thrown.has(localId) ? "deleted" : "sending";
    held.push({ localId, state, kept: false });
  }
  return held;
}
