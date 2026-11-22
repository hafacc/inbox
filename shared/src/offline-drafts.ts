// Drafts offline: the saved copy holds every thread with a draft (offline-set.ts), but Gmail's
// messages don't say which draft they are, and drafts.update needs the draft id. The saved copy
// keeps Gmail's drafts listing beside it, listed again whenever a saved draft message has no
// draft id, so a draft opens in the composer offline. Once the saved copy is filled, Drafts is
// built from it and the device's own drafts, online or not: a new message not in Gmail yet has a
// row of its own by its local id, and a draft the device knows went (its outbox doc said `sent`)
// stays out until the saved copy drops it. Web uses it directly; Android ports it to :core (OfflineDrafts.kt).
// Both run shared/testdata/offline-draft-fixtures.json.

import {
  type GmailDraftRef,
  type OutboxDraftDoc,
  outboxDrafts,
  type SendingDraft,
} from "./outbox-drafts";
import type { LocalDraftState } from "./thread-drafts";

const DRAFT = "DRAFT";

/** A draft message the saved copy holds. */
export type SavedDraftMessage = {
  /** Gmail message id */
  messageId: string;
  /** Gmail thread id of its saved thread */
  threadId: string;
};

/** Gmail's drafts as the saved copy last listed them. */
export type SavedDraftRefs = {
  /** the listing, newest first */
  refs: GmailDraftRef[];
  /** saved draft messages the listing didn't name, so listing again for them alone is skipped */
  unmatched: string[];
};

/** Nothing listed yet. */
export const NO_SAVED_DRAFT_REFS: SavedDraftRefs = { refs: [], unmatched: [] };

/** A draft this device holds, as Drafts sees it. */
export type OfflineLocalDraft = {
  /** its id on this device */
  localId: string;
  /** the Gmail thread it is in, when known */
  threadId: string | null;
  /** its Gmail message id as last saved there, when it was */
  messageId: string | null;
  /** where it stands */
  state: LocalDraftState;
};

/** What Drafts is built from. */
export type OfflineDraftsInput = {
  /** the saved copy's draft messages */
  saved: readonly SavedDraftMessage[];
  /** the saved listing of Gmail's drafts */
  refs: readonly GmailDraftRef[];
  /** outbox docs, any status */
  docs: readonly OutboxDraftDoc[];
  /** drafts this device is sending */
  sending: readonly SendingDraft[];
  /** drafts the device knows went (see `sentDrafts`) */
  sent: readonly GmailDraftRef[];
  /** drafts this device holds */
  local: readonly OfflineLocalDraft[];
};

/** A saved draft's parts as offline editing sees them. */
export type OfflineDraftParts = {
  /** whether it is encrypted (its raw is needed to decrypt it) */
  encrypted: boolean;
  /** whether the saved copy has every header the composer reads back (Bcc included) */
  headersKnown: boolean;
  /** its attachments and inline images: whether the saved copy holds each one's bytes */
  files: readonly { held: boolean }[];
};

/** The draft messages in saved threads' records (thread id to message id to labels). */
export function savedDraftMessages(
  threads: Iterable<
    readonly [string, Readonly<Record<string, readonly string[]>>]
  >,
): SavedDraftMessage[] {
  const found: SavedDraftMessage[] = [];
  for (const [threadId, messages] of threads) {
    for (const [messageId, labels] of Object.entries(messages)) {
      if (labels.includes(DRAFT)) found.push({ messageId, threadId });
    }
  }
  return found;
}

/** Whether Gmail's drafts should be listed again: a saved draft message is new to the listing. */
export function draftsListingWanted(
  saved: readonly SavedDraftMessage[],
  known: SavedDraftRefs,
): boolean {
  const named = new Set(known.refs.map((ref) => ref.messageId));
  const unmatched = new Set(known.unmatched);
  return saved.some(
    (message) =>
      !named.has(message.messageId) && !unmatched.has(message.messageId),
  );
}

/** What to keep after listing Gmail's drafts (`listed`, newest first). */
export function afterDraftsListing(
  listed: readonly GmailDraftRef[],
  saved: readonly SavedDraftMessage[],
): SavedDraftRefs {
  const named = new Set(listed.map((ref) => ref.messageId));
  return {
    refs: [...listed],
    unmatched: saved
      .map((message) => message.messageId)
      .filter((messageId) => !named.has(messageId))
      .sort(),
  };
}

/**
 * The drafts the device knows went, kept until the saved copy no longer holds them as drafts:
 * `previous` plus every draft a `sent` doc names whose message `known` gives (the saved listing
 * and this device's sends), less those whose message isn't a saved draft any more.
 */
export function sentDrafts(
  previous: readonly GmailDraftRef[],
  docs: readonly OutboxDraftDoc[],
  known: readonly GmailDraftRef[],
  saved: readonly SavedDraftMessage[],
): GmailDraftRef[] {
  const savedIds = new Set(saved.map((message) => message.messageId));
  const byDraft = new Map<string, GmailDraftRef>();
  for (const ref of known) {
    if (!byDraft.has(ref.draftId)) byDraft.set(ref.draftId, ref);
  }
  const next = new Map<string, GmailDraftRef>();
  for (const record of previous) next.set(record.messageId, record);
  for (const doc of docs) {
    if (doc.status !== "sent") continue;
    const ref = byDraft.get(doc.draftId);
    if (ref !== undefined) next.set(ref.messageId, ref);
  }
  return [...next.values()]
    .filter((record) => savedIds.has(record.messageId))
    .sort((left, right) =>
      left.messageId < right.messageId
        ? -1
        : left.messageId > right.messageId
          ? 1
          : 0,
    );
}

/** Saved draft messages that show nowhere: held by the Outbox, or known to have gone. */
export function offlineHiddenDrafts(
  input: Omit<OfflineDraftsInput, "local">,
): Set<string> {
  const outbox = outboxDrafts(input.refs, input.docs, input.sending);
  const hidden = new Set(outbox.messageIds);
  for (const record of input.sent) hidden.add(record.messageId);
  return hidden;
}

// Saved draft messages the device's own drafts replace: thrown away or being sent here.
function replacedHere(local: readonly OfflineLocalDraft[]): Set<string> {
  const replaced = new Set<string>();
  for (const draft of local) {
    if (draft.messageId !== null && draft.state !== "draft") {
      replaced.add(draft.messageId);
    }
  }
  return replaced;
}

/**
 * The threads Drafts shows offline, sorted: each saved thread with a draft message that isn't
 * hidden (`offlineHiddenDrafts`) or thrown away or sent here, and each thread a draft of this
 * device's is kept in.
 */
export function offlineDraftThreads(input: OfflineDraftsInput): string[] {
  const hidden = offlineHiddenDrafts(input);
  const replaced = replacedHere(input.local);
  const threads = new Set<string>();
  for (const message of input.saved) {
    if (!hidden.has(message.messageId) && !replaced.has(message.messageId)) {
      threads.add(message.threadId);
    }
  }
  for (const draft of input.local) {
    if (
      draft.state === "draft" &&
      draft.threadId !== null &&
      (draft.messageId === null || !hidden.has(draft.messageId))
    ) {
      threads.add(draft.threadId);
    }
  }
  return [...threads].sort();
}

/**
 * This device's drafts in no Gmail thread yet, by local id, sorted: a new message kept as a draft
 * whose thread isn't known, unless its message (once saved to Gmail) is hidden
 * (`offlineHiddenDrafts`) or already a saved draft message, whose thread shows it.
 */
export function localOnlyDrafts(input: OfflineDraftsInput): string[] {
  const hidden = offlineHiddenDrafts(input);
  const saved = new Set(input.saved.map((message) => message.messageId));
  return input.local
    .filter(
      (draft) =>
        draft.state === "draft" &&
        draft.threadId === null &&
        (draft.messageId === null ||
          (!hidden.has(draft.messageId) && !saved.has(draft.messageId))),
    )
    .map((draft) => draft.localId)
    .sort();
}

/**
 * The saved draft to open offline: the newest the listing names whose message is `messageId`, or
 * that is in one of `threadIds`, among those that show (as in `offlineDraftThreads`); null when
 * the saved copy can't say which draft it is.
 */
export function offlineDraftOf(
  input: OfflineDraftsInput,
  target: { messageId?: string; threadIds?: readonly string[] },
): GmailDraftRef | null {
  const hidden = offlineHiddenDrafts(input);
  const replaced = replacedHere(input.local);
  const savedThread = new Map<string, string>();
  for (const message of input.saved) {
    savedThread.set(message.messageId, message.threadId);
  }
  const threads = new Set(target.threadIds ?? []);
  for (const ref of input.refs) {
    const threadId = savedThread.get(ref.messageId);
    if (
      threadId === undefined ||
      hidden.has(ref.messageId) ||
      replaced.has(ref.messageId)
    ) {
      continue;
    }
    if (ref.messageId === target.messageId || threads.has(threadId)) {
      return { ...ref, threadId };
    }
  }
  return null;
}

/**
 * Whether a saved draft can be edited offline: Gmail's drafts.update replaces the whole draft, so
 * only one whose saved copy holds all of it (not encrypted, every header, every file's bytes).
 */
export function offlineEditable(parts: OfflineDraftParts): boolean {
  return (
    !parts.encrypted &&
    parts.headersKnown &&
    parts.files.every((file) => file.held)
  );
}
