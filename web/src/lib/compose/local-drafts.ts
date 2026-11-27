import type { Mailbox } from "@inbox/shared/compose";
import {
  draftSnippet,
  type LocalDraftState,
  type LocalThreadDraft,
} from "@inbox/shared/thread-drafts";
import { type ComposeSnapshot, isBlank } from "./compose-model";
import type { PendingDelete } from "./composer-store";

/** A draft this device holds, as its thread shows it. */
export type LocalDraft = LocalThreadDraft & {
  /** the thread a reply or forward belongs to */
  threadId: string | null;
  /** the Gmail thread the draft is in once saved there, a new message's own one too */
  draftThreadId?: string | null;
  /** sender address */
  from: string;
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
  /** `Bcc` */
  bcc: Mailbox[];
  /** subject */
  subject: string;
  /** the body's first words */
  snippet: string;
};

/** The local draft a composer's contents make. */
export function localDraftOf(
  snapshot: ComposeSnapshot,
  state: LocalDraftState,
  updatedAt: number,
): LocalDraft {
  return {
    localId: snapshot.localId,
    messageId: snapshot.messageId,
    state,
    updatedAt,
    threadId: snapshot.threadId,
    draftThreadId: snapshot.draftThreadId ?? null,
    from: snapshot.from,
    to: snapshot.to,
    cc: snapshot.cc,
    bcc: snapshot.bcc,
    subject: snapshot.subject,
    snippet: draftSnippet(snapshot.markdown),
  };
}

/** The local draft of a thrown-away draft whose Gmail copy is still to be deleted: hidden from its thread. */
export function deletedDraftOf(pending: PendingDelete): LocalDraft {
  return {
    localId: pending.localId,
    messageId: pending.messageId,
    state: "deleted",
    updatedAt: 0,
    threadId: pending.threadId,
    from: "",
    to: [],
    cc: [],
    bcc: [],
    subject: "",
    snippet: "",
  };
}

/**
 * How many drafts haven't reached Gmail: queued saves and sends, and thrown-away drafts whose
 * delete hasn't gone through.
 */
export function unsyncedDrafts(
  jobIds: Iterable<string>,
  drafts: Iterable<LocalDraft>,
): number {
  const waiting = new Set(jobIds);
  for (const draft of drafts) {
    if (draft.state === "deleted") waiting.add(draft.localId);
  }
  return waiting.size;
}

/**
 * Whether putting a composer away throws it away rather than keeping it as a draft: it is blank and
 * Gmail has no draft of it (`draftId`), so a save of it still queued or under way goes with it.
 */
export function closeThrowsAway(
  snapshot: ComposeSnapshot,
  draftId: string | null,
): boolean {
  return isBlank(snapshot) && draftId === null;
}

/**
 * The kept composers with nothing worth keeping, to forget when read: blank, never saved to Gmail,
 * with no save queued (`queued`) and not open anywhere (`open`).
 */
export function emptyComposers(
  kept: Iterable<[string, ComposeSnapshot]>,
  queued: (localId: string) => boolean,
  open: (localId: string) => boolean,
): string[] {
  return [...kept].flatMap(([localId, snapshot]) =>
    closeThrowsAway(snapshot, snapshot.draftId) &&
    !queued(localId) &&
    !open(localId)
      ? [localId]
      : [],
  );
}

/**
 * Every distinct composer of `localId` among `composers` (the one on screen, parked, put away
 * behind a pill), so Gmail's ids for its draft reach each one.
 */
export function composersOf<Held extends { localId: string }>(
  localId: string,
  composers: Iterable<Held | null>,
): Held[] {
  const found: Held[] = [];
  for (const composer of composers) {
    if (composer?.localId === localId && !found.includes(composer)) {
      found.push(composer);
    }
  }
  return found;
}
