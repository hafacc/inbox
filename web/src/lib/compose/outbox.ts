import type { Mailbox } from "@inbox/shared/compose";
import { heldSendIds } from "@inbox/shared/outbox-held";
import { UNDO_SEND_DELAY_MS } from "@inbox/shared/send-suggest";
import { formatWakeTime } from "@inbox/shared/time-format";
import type { DraftJob, PendingSend } from "./draft-queue";
import type { LocalDraft } from "./local-drafts";

/** Where an outbox doc is in its life (docs/compose-contract.md). */
export type OutboxStatus =
  | "waiting"
  | "sending"
  | "retrying"
  | "blocked"
  | "sent"
  | "failed";

/** What a blocked send waits for. */
export type BlockedOn = "sign-in" | "resend";

/** Why a send failed for good, as the server records it. */
export type OutboxErrorCode =
  | "draft-missing"
  | "from-mismatch"
  | "not-owned"
  | "rejected"
  | "uncertain"
  | "unavailable";

/** Why the last attempt of a retrying send didn't go. */
export type RetryReasonCode = "network" | "rate-limited" | "server";

/** An outbox doc as this app reads it, times in epoch milliseconds. */
export type OutboxDoc = {
  /** doc id */
  id: string;
  /** Gmail draft id */
  draftId: string;
  /** sender address */
  from: string;
  /** status */
  status: OutboxStatus;
  /** when it goes */
  sendAt: number;
  /** the unshifted suggestion picked, or null */
  chosenAt: number | null;
  /** the draft's thread, or (once sent) the sent message's */
  threadId: string | null;
  /** when it went, once sent */
  sentAt: number | null;
  /** how it went out, once known */
  via: "gmail" | "resend" | null;
  /** false until a Resend copy is filed in Sent */
  filed: boolean | null;
  /** addresses the mail server refused */
  rejected: string[];
  /** why it failed */
  error: { code: OutboxErrorCode | string; message: string } | null;
  /** while retrying, when the next attempt is */
  nextAttemptAt: number | null;
  /** why the last attempt didn't go */
  lastError: { code: RetryReasonCode | string; message: string } | null;
  /** while blocked, what it waits for */
  blockedOn: BlockedOn | null;
};

/** The fields a client writes, times in epoch milliseconds. */
export type OutboxWrite = {
  draftId: string;
  from: string;
  sendAt: number;
  chosenAt: number | null;
  threadId: string | null;
};

type TimestampLike = { toMillis: () => number };

function millis(value: unknown): number | null {
  if (value && typeof (value as TimestampLike).toMillis === "function") {
    return (value as TimestampLike).toMillis();
  }
  return null;
}

const STATUSES: readonly OutboxStatus[] = [
  "waiting",
  "sending",
  "retrying",
  "blocked",
  "sent",
  "failed",
];

function codeAndMessage(
  value: unknown,
  fallback: string,
): { code: string; message: string } | null {
  if (!value || typeof value !== "object") return null;
  const { code, message } = value as { code?: unknown; message?: unknown };
  return { code: String(code ?? fallback), message: String(message ?? "") };
}

/** Read an outbox doc's data; null when it lacks what the list needs. */
export function outboxFromData(
  id: string,
  data: Record<string, unknown>,
): OutboxDoc | null {
  const status = data.status as OutboxStatus;
  const sendAt = millis(data.sendAt);
  if (!STATUSES.includes(status) || sendAt === null) return null;
  if (typeof data.draftId !== "string" || typeof data.from !== "string") {
    return null;
  }
  return {
    id,
    draftId: data.draftId,
    from: data.from,
    status,
    sendAt,
    chosenAt: millis(data.chosenAt),
    sentAt: millis(data.sentAt),
    threadId: typeof data.threadId === "string" ? data.threadId : null,
    via: data.via === "gmail" || data.via === "resend" ? data.via : null,
    filed: typeof data.filed === "boolean" ? data.filed : null,
    rejected: Array.isArray(data.rejected)
      ? data.rejected.filter(
          (entry): entry is string => typeof entry === "string",
        )
      : [],
    error: codeAndMessage(data.error, "unavailable"),
    nextAttemptAt: millis(data.nextAttemptAt),
    lastError: codeAndMessage(data.lastError, "server"),
    blockedOn:
      data.blockedOn === "sign-in" || data.blockedOn === "resend"
        ? data.blockedOn
        : null,
  };
}

/** What the list shows for a failed send and what it offers. */
export type FailureView = {
  /** the reason, in plain English */
  message: string;
  /** whether "Send again" makes sense */
  canRetry: boolean;
  /** whether the draft can still be opened */
  canEdit: boolean;
};

const FAILURE_TEXT: Record<OutboxErrorCode, string> = {
  "draft-missing": "The draft was deleted before it could go.",
  "from-mismatch": "The draft's From address changed.",
  "not-owned": "You can't send from this address.",
  rejected: "The mail server refused it.",
  uncertain:
    "It may have been delivered. Check Sent first: sending again sends it again.",
  unavailable: "It kept failing, so it stopped trying.",
};

/** The reason and actions for a failed send. */
export function failureView(error: OutboxDoc["error"]): FailureView {
  const code = (error?.code ?? "unavailable") as OutboxErrorCode;
  const known = FAILURE_TEXT[code] as string | undefined;
  // The server's message is already readable English; for these it says the most.
  const message =
    (code === "rejected" || code === "unavailable") && error?.message
      ? error.message
      : (known ?? (error?.message || "It couldn't be sent."));
  return {
    message,
    canRetry: code !== "draft-missing" && code !== "not-owned",
    canEdit: code !== "draft-missing",
  };
}

/** The doc "Send again" or "send now" writes: waiting, going after the undo delay. */
export function resendWrite(doc: OutboxDoc, now: number): OutboxWrite {
  return {
    draftId: doc.draftId,
    from: doc.from,
    sendAt: now + UNDO_SEND_DELAY_MS,
    chosenAt: null,
    threadId: doc.threadId,
  };
}

const RETRY_TEXT: Record<RetryReasonCode, string> = {
  network: "Couldn't reach the mail server.",
  "rate-limited": "Sending limit reached.",
  server: "The mail server had a problem.",
};

const BLOCKED_TEXT: Record<BlockedOn, string> = {
  "sign-in": "Waiting for you to sign in again",
  resend: "Sending from @hafa.cc isn't set up yet",
};

/** The line for a blocked send (it goes by itself once fixed); null when the doc isn't blocked. */
export function blockedText(doc: OutboxDoc): string | null {
  if (doc.status !== "blocked") return null;
  return doc.blockedOn === null
    ? "Waiting until it can be sent"
    : BLOCKED_TEXT[doc.blockedOn];
}

/** What the outbox shows for a send that hasn't gone yet but is still being tried. */
export type RetryView = {
  /** `Not sent yet · trying again at HH:mm` */
  headline: string;
  /** why the last try didn't go, in plain English */
  reason: string;
  /** whether canceling now might be too late (the last try's outcome isn't known) */
  mayHaveGone: boolean;
};

/** The line for a retrying send; null when the doc isn't retrying. */
export function retryView(doc: OutboxDoc, now: number): RetryView | null {
  if (doc.status !== "retrying") return null;
  const code = doc.lastError?.code ?? "server";
  const known = RETRY_TEXT[code as RetryReasonCode] as string | undefined;
  return {
    headline:
      doc.nextAttemptAt === null
        ? "Not sent yet · trying again soon"
        : `Not sent yet · trying again at ${formatWakeTime(doc.nextAttemptAt, now)}`,
    reason: doc.lastError?.message || known || "It hasn't gone yet.",
    mayHaveGone: code === "network",
  };
}

/** Whether a doc's message hasn't gone yet: waiting, sending, retrying, blocked or failed. */
export function isPending(doc: OutboxDoc): boolean {
  return doc.status !== "sent";
}

/** Whether a doc is past its time and being sent or retried. */
export function isUnderWay(doc: OutboxDoc, now: number): boolean {
  return (
    doc.status === "sending" ||
    doc.status === "retrying" ||
    (doc.status === "waiting" && doc.sendAt <= now)
  );
}

/** Whether a doc can be sent again by hand: failed or blocked. */
export function canSendAgain(doc: OutboxDoc): boolean {
  return doc.status === "failed" || doc.status === "blocked";
}

function listRank(doc: OutboxDoc, now: number): number {
  if (doc.status === "failed") return 0;
  if (doc.status === "blocked") return 1;
  return isUnderWay(doc, now) ? 2 : 3;
}

/**
 * Docs the Outbox lists, everything not sent yet: failed first, then blocked, then those being
 * sent or retried, then waiting by send time.
 */
export function outboxDocs(
  docs: Iterable<OutboxDoc>,
  now: number,
): OutboxDoc[] {
  return [...docs]
    .filter(isPending)
    .sort(
      (left, right) =>
        listRank(left, now) - listRank(right, now) ||
        left.sendAt - right.sendAt,
    );
}

/** Whether a doc is done with and can be deleted: sent, and (through Resend) filed in Sent. */
export function isFinished(doc: OutboxDoc): boolean {
  return doc.status === "sent" && doc.filed !== false;
}

/** Whether the composer must not change a draft: its send is under way (`sending` or `retrying`). */
export function isLocked(doc: OutboxDoc | null): boolean {
  return doc?.status === "sending" || doc?.status === "retrying";
}

/**
 * The unsent send of a draft: one under way first, then waiting, then blocked, then failed; null
 * when the draft isn't queued.
 */
export function outboxForDraft(
  docs: Iterable<OutboxDoc>,
  draftId: string | null,
): OutboxDoc | null {
  if (draftId === null) return null;
  let waiting: OutboxDoc | null = null;
  let blocked: OutboxDoc | null = null;
  let failed: OutboxDoc | null = null;
  for (const doc of docs) {
    if (doc.draftId !== draftId) continue;
    if (isLocked(doc)) return doc;
    if (doc.status === "waiting") waiting ??= doc;
    if (doc.status === "blocked") blocked ??= doc;
    if (doc.status === "failed") failed ??= doc;
  }
  return waiting ?? blocked ?? failed;
}

/** The doc that puts a waiting send back as it was, as undoing a "send now" of it does. */
export function waitingWrite(doc: OutboxDoc): OutboxWrite | null {
  if (doc.status !== "waiting") return null;
  return {
    draftId: doc.draftId,
    from: doc.from,
    sendAt: doc.sendAt,
    chosenAt: doc.chosenAt,
    threadId: doc.threadId,
  };
}

/**
 * A waiting send to rewrite after its draft was made again under a new id (the old one was gone
 * when saving), keeping its time; null when nothing needs to change.
 */
export function movedDraftWrite(
  doc: OutboxDoc,
  draftId: string,
): OutboxWrite | null {
  const write = waitingWrite(doc);
  return write === null || write.draftId === draftId
    ? null
    : { ...write, draftId };
}

/** How early before a scheduled send an edit must reach Gmail, since the server reads the draft then. */
export const SAVE_LEAD_MS = 15_000;

/**
 * How long an edit waits before its draft saves: `autosaveMs` normally, but never past
 * `SAVE_LEAD_MS` before a scheduled `sendAt`, so steady typing can't hold the save back.
 */
export function autosaveDelay(
  now: number,
  sendAt: number | null,
  autosaveMs: number,
): number {
  if (sendAt === null) return autosaveMs;
  return Math.max(0, Math.min(autosaveMs, sendAt - SAVE_LEAD_MS - now));
}

/** An outbox message's row: recipients, subject and snippet. */
export type DraftPreview = {
  to: Mailbox[];
  // a Bcc copy's one recipient (docs/compose-contract.md "Encrypted sends with Bcc")
  bcc: Mailbox[];
  subject: string;
  snippet: string;
};

/** A send held on this device: queued, with no outbox doc yet (offline, say). */
export type HeldSend = {
  /** the composer's local id */
  localId: string;
  /** sender address */
  from: string;
  /** when it is meant to go: when Send was pressed plus the undo delay, or the scheduled time */
  sendAt: number;
  /** whether it was sent with undo rather than scheduled */
  now: boolean;
  /** recipients, subject and snippet, from the device's copy; null when it has none */
  preview: DraftPreview | null;
};

/** The sends among `jobs` held on this device, given the docs written for them and those heard of. */
export function heldSends(
  jobs: Iterable<DraftJob>,
  docsWritten: ReadonlyMap<string, PendingSend>,
  docIds: Iterable<string>,
  localDrafts: ReadonlyMap<string, LocalDraft>,
): HeldSend[] {
  const queued = [...jobs].flatMap((job) =>
    job.send === null ? [] : [{ ...job, send: job.send }],
  );
  const held = new Set(
    heldSendIds(
      queued.map((job) => ({
        localId: job.localId,
        outboxId: job.send.outboxId,
        docWritten: docsWritten.get(job.localId) === job.send,
      })),
      docIds,
    ),
  );
  return queued
    .filter((job) => held.has(job.localId))
    .map((job) => {
      const local = localDrafts.get(job.localId);
      return {
        localId: job.localId,
        from: job.from,
        sendAt:
          job.send.kind === "now"
            ? job.send.queuedAt + UNDO_SEND_DELAY_MS
            : job.send.sendAt,
        now: job.send.kind === "now",
        preview:
          local === undefined
            ? null
            : {
                to: [...local.to, ...local.cc],
                bcc: local.bcc,
                subject: local.subject,
                snippet: local.snippet,
              },
      };
    });
}
