/**
 * How a queued message should go once its draft is in Gmail. `outboxId` is the outbox doc to
 * rewrite when the message was already scheduled (or failed), else null for a new doc.
 */
export type PendingSend =
  // Send with undo: `queuedAt` is when Send was pressed; the send goes the undo delay after it.
  | { kind: "now"; outboxId: string | null; queuedAt: number }
  | {
      kind: "at";
      sendAt: number;
      chosenAt: number | null;
      outboxId: string | null;
    };

/** A draft save waiting for Gmail, kept on the device until it goes. */
export type DraftJob = {
  /** the composer's local id */
  localId: string;
  /** Gmail draft id once created */
  draftId: string | null;
  /** thread for a reply or forward */
  threadId: string | null;
  /** sender address, for the outbox */
  from: string;
  /** the message, base64url, as Gmail takes it */
  raw: string;
  /** bumped on every save, so a save that finished late doesn't drop a newer one */
  version: number;
  /** what to do after saving, if the user sent it */
  send: PendingSend | null;
  /**
   * an encrypted send's Bcc copies (docs/compose-contract.md "Encrypted sends with Bcc"), each its
   * own draft, saved after the main one and before the outbox docs; absent when there are none
   */
  copies?: CopyDraft[];
};

/** One Bcc copy of an encrypted send waiting for its draft. */
export type CopyDraft = {
  /** the message, base64url */
  raw: string;
  /** its Gmail draft once saved */
  draftId: string | null;
  /** the copy's recipient, lower-cased */
  to?: string;
  /** the draft this copy had before an edit, updated in place rather than made anew */
  updates?: string;
  /** the outbox doc this copy had before an edit, kept */
  outboxId?: string;
};

/** A save to apply now: create when there's no draft yet, else update. */
export type DraftOp =
  | { kind: "create"; job: DraftJob }
  | { kind: "update"; job: DraftJob; draftId: string };

/** The Gmail call a job needs. */
export function opFor(job: DraftJob): DraftOp {
  return job.draftId === null
    ? { kind: "create", job }
    : { kind: "update", job, draftId: job.draftId };
}

/**
 * Queue a newer save of a composer's draft. It replaces any queued one, keeping the draft id
 * already known and a send already asked for.
 */
export function queueSave(
  queued: DraftJob | undefined,
  next: Omit<DraftJob, "version">,
): DraftJob {
  return {
    ...next,
    draftId: next.draftId ?? queued?.draftId ?? null,
    send: next.send ?? queued?.send ?? null,
    version: (queued?.version ?? 0) + 1,
  };
}

/**
 * After Gmail saved version `savedVersion` as `draftId`: the job is done (null) when nothing newer
 * was queued meanwhile, else the newer one, now knowing the draft id.
 */
export function afterSave(
  current: DraftJob,
  savedVersion: number,
  draftId: string,
): DraftJob | null {
  if (current.version === savedVersion) return null;
  return { ...current, draftId };
}

/** Whether a Gmail error on a draft update means the draft is gone and should be made again. */
export function draftGone(status: number): boolean {
  return status === 404;
}
