import { createHash } from "node:crypto";
import { addressKey } from "../../../shared/src/compose/plain-address.js";
import {
  type GmailApi,
  type GmailDraftHeaders,
  GmailError,
  type GmailRawDraft,
  LABEL,
} from "./gmail-api.js";
import {
  type BlockReason,
  type OutboxEntry,
  type OutboxStatus,
  planOutboxWrite,
  type RetryReason,
  type SendFailure,
  type SendRoute,
  type SendTask,
  sendHopTime,
  sendTaskId,
  type WaitingSend,
} from "./outbox-task.js";
import {
  decodeRaw,
  encodeRaw,
  fromAddress,
  headerProblem,
  headerValue,
  newMessageId,
  ownAddressProblem,
  prepareForSmtp,
  splitMessage,
} from "./raw-message.js";
import { type Log, mapLimit, messageOf, redact } from "./util.js";

/** How long a claim holds before another attempt may assume the first one died. */
export const CLAIM_LEASE_MS = 5 * 60 * 1000;

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;

/**
 * How early a retry task may fire and still count as due. Cloud Tasks can run a task a little
 * before its schedule time, and a retry that found itself early could only queue itself again under
 * its own (still running) task id, which Cloud Tasks drops.
 */
export const CLAIM_SLACK_MS = 30 * 1000;

/** How long one run holds a sent copy for filing before another may take it over. */
export const FILING_LEASE_MS = 2 * MINUTE_MS;

/** How far past due a waiting send, a retry or an unfiled copy must be before the sweep steps in. */
export const SWEEP_MARGIN_MS = 10 * MINUTE_MS;

/** Most sends or copies one sweep handles; the next sweep picks up the rest. */
export const SWEEP_BATCH = 100;

/** How long a kept Sent copy waits for Google access to come back before it is deleted unfiled. */
export const COPY_KEEP_WITHOUT_ACCESS_MS = 14 * 24 * HOUR_MS;

/** How long the sweep leaves a kept copy alone after finding its owner without Google access. */
const NO_ACCESS_RECHECK_MS = 6 * HOUR_MS;

/** How long the sweep leaves a kept copy alone after filing it failed. */
const FILING_RETRY_MS = HOUR_MS;

/** Most sends or copies a sweep works on at once. */
const SWEEP_CONCURRENCY = 10;

/**
 * Wait before automatic retry n (1-based), in minutes. There are only as many retries as entries:
 * after the last one fails too, the send ends as `failed` (`unavailable`) for the user to send again.
 */
export const RETRY_DELAYS_MINUTES: readonly number[] = [1, 5, 30];

/**
 * How long a Resend idempotency key is trusted after a handover whose outcome is unknown. Resend
 * keeps keys for 24 hours; the hour short of that covers clock skew and a slow attempt.
 */
export const RESEND_KEY_WINDOW_MS = 23 * HOUR_MS;

/** The header Resend reads an idempotency key from on SMTP. */
export const IDEMPOTENCY_HEADER = "Resend-Idempotency-Key";

/**
 * A Resend idempotency key for one handover: a hash of the user, the outbox doc and the attempt's
 * start, so nothing a client chose reaches the header.
 */
export function idempotencyKeyFor(
  uid: string,
  outboxId: string,
  seed: number,
): string {
  const digest = createHash("sha256")
    .update(JSON.stringify([uid, outboxId, seed]))
    .digest("hex");
  return `inbox-${digest}`;
}

/**
 * The attempt record a client's "Send again" must keep, so a resend after an unknown outcome
 * still never sends twice. A client rewrites the whole doc, dropping these; the trigger puts them
 * back before queueing the send.
 */
export interface CarriedAttempt {
  /** route of the earlier attempts */
  via: SendRoute | null;
  /** Gmail message id of the draft as handed to `drafts.send` */
  draftMessageId: string | null;
  /** `Message-ID` header that went out */
  messageIdHeader: string | null;
  /** start of the earliest unknown Resend handover */
  uncertainSince: number | null;
  /** Resend `Date` that goes with the key */
  messageDate: number | null;
  /** Resend idempotency key */
  idempotencyKey: string | null;
}

/**
 * What a rewrite to `waiting` must get back from the entry it replaced, or null when nothing. Only
 * a `failed`, `blocked` or `waiting` entry for the same draft hands anything on, and only to an entry
 * that has no attempt record of its own. The Resend key and `Date` go along only while an earlier
 * handover's outcome is unknown; after `uncertain` they are dropped too: the user was told sending
 * again may deliver it twice, and the old key would only be refused.
 */
export function carriedAttempt(
  before: OutboxEntry | undefined,
  after: OutboxEntry | undefined,
): CarriedAttempt | null {
  if (
    before === undefined ||
    after?.status !== "waiting" ||
    !["failed", "blocked", "waiting"].includes(before.status) ||
    before.draftId !== after.draftId ||
    after.via !== null ||
    after.draftMessageId !== null ||
    after.messageIdHeader !== null ||
    after.uncertainSince !== null ||
    after.idempotencyKey !== null
  ) {
    return null;
  }
  const keepKey =
    before.uncertainSince !== null &&
    !(before.status === "failed" && before.errorCode === "uncertain");
  const carried: CarriedAttempt = {
    via: before.via,
    draftMessageId: before.draftMessageId,
    messageIdHeader: before.messageIdHeader,
    uncertainSince: keepKey ? before.uncertainSince : null,
    messageDate: keepKey ? before.messageDate : null,
    idempotencyKey: keepKey ? before.idempotencyKey : null,
  };
  return Object.values(carried).every((value) => value === null)
    ? null
    : carried;
}

/** Outcome of trying to take an outbox entry for sending. */
export type Claim =
  /** the entry was waiting, or retrying and due, and is now `sending`; `entry` is as written */
  | { kind: "claimed"; entry: OutboxEntry }
  /** an earlier attempt claimed it and its lease ran out; now ours */
  | { kind: "expired"; entry: OutboxEntry }
  /** sent through Resend but not yet filed in Sent */
  | { kind: "file"; entry: OutboxEntry }
  /** retrying, but its next attempt isn't due yet */
  | { kind: "early"; entry: OutboxEntry }
  /** another attempt holds it */
  | { kind: "busy"; entry: OutboxEntry }
  /** gone, rescheduled, or already done */
  | { kind: "stale" };

/**
 * Decide a claim from the entry as stored: `waiting` for this time, or `retrying` once its next
 * attempt is due (within `CLAIM_SLACK_MS`), is claimed; `sending` is busy while its lease runs, then taken over as expired;
 * `sent` through Resend but not filed is handed back for filing; anything else, or a different
 * send time, is stale. A device's claim of a send now is held and taken over the same way. A claimed or expired entry comes back as the claim writes it: `sending`,
 * `claimedAt` now, `firstAttemptAt` set, and for an expired one, `attempts` one more.
 */
export function decideClaim(
  entry: OutboxEntry | undefined,
  sendAt: number,
  now: number,
): Claim {
  if (entry === undefined || entry.sendAt !== sendAt) return { kind: "stale" };
  const taken: OutboxEntry = {
    ...entry,
    status: "sending",
    claimedAt: now,
    firstAttemptAt: entry.firstAttemptAt ?? now,
  };
  switch (entry.status) {
    case "waiting":
      return { kind: "claimed", entry: taken };
    case "retrying":
      return entry.nextAttemptAt !== null &&
        now < entry.nextAttemptAt - CLAIM_SLACK_MS
        ? { kind: "early", entry }
        : { kind: "claimed", entry: taken };
    case "sending":
      // A run that died mid-attempt counts as one, so a send that kills every run ends.
      return entry.claimedAt !== null && now - entry.claimedAt < CLAIM_LEASE_MS
        ? { kind: "busy", entry }
        : {
            kind: "expired",
            entry: { ...taken, attempts: entry.attempts + 1 },
          };
    case "sent":
      return entry.via === "resend" && !entry.filed
        ? { kind: "file", entry }
        : { kind: "stale" };
    default:
      return { kind: "stale" };
  }
}

/** What the server records once a message went. */
export interface SentRecord {
  /** route */
  via: SendRoute;
  /** when it went (for Resend, its `Date`), epoch milliseconds */
  sentAt: number;
  /** Gmail message id of the sent message (or its Sent copy), when known */
  messageId: string | null;
  /** Gmail thread id, when known */
  threadId: string | null;
  /** `Message-ID` header, when known */
  messageIdHeader: string | null;
  /** whether the Sent copy is filed (always true for Gmail) */
  filed: boolean;
  /** recipients the SMTP server refused, if any */
  rejected: string[];
}

/** What the server records just before handing a message over. */
export interface AttemptRecord {
  /** route */
  via: SendRoute;
  /** now, epoch milliseconds */
  inFlightAt: number;
  /** the draft's Gmail message id (Gmail) */
  draftMessageId: string | null;
  /** the `Message-ID` header that goes out, when known */
  messageIdHeader: string | null;
  /** the `Date` that goes out (Resend) */
  messageDate: number | null;
  /** the idempotency key that goes out (Resend) */
  idempotencyKey: string | null;
  /** start of the earliest unknown Resend handover, carried over from a dead attempt */
  uncertainSince: number | null;
}

/** What the server records when an attempt didn't go and another is queued. */
export interface RetryRecord {
  /** route, when chosen */
  via: SendRoute | null;
  /** attempts so far, this one included */
  attempts: number;
  /** when the next attempt is due, epoch milliseconds */
  nextAttemptAt: number;
  /** why this one didn't go */
  reason: RetryReason;
  /** readable English */
  message: string;
  /** start of the earliest unknown Resend handover, or null */
  uncertainSince: number | null;
  /** Resend `Date` and key to reuse next time; null to make new ones */
  messageDate: number | null;
  /** see `messageDate` */
  idempotencyKey: string | null;
}

/** What the server records when an attempt hit a problem retrying can't fix. */
export interface BlockRecord {
  /** route, when chosen */
  via: SendRoute | null;
  /** what it waits for */
  on: BlockReason;
  /** readable English */
  message: string;
  /** start of the earliest unknown Resend handover, or null */
  uncertainSince: number | null;
  /** Resend `Date` and key to reuse once resumed; null to make new ones */
  messageDate: number | null;
  /** see `messageDate` */
  idempotencyKey: string | null;
}

/** The Resend handover record kept on an entry between attempts. */
export interface ResendRecord {
  /** start of the earliest unknown Resend handover, or null */
  uncertainSince: number | null;
  /** Resend `Date` that goes with the key, or null */
  messageDate: number | null;
  /** Resend idempotency key, or null */
  idempotencyKey: string | null;
}

/**
 * The Resend handover of a draft, kept apart from its outbox doc (by draft), so a send of the same
 * draft from a doc made again after the first was deleted reuses its key and `Date` and Resend
 * drops it as a repeat.
 */
export interface DraftHandover {
  /** the Resend idempotency key */
  idempotencyKey: string;
  /** Resend `Date` that goes with the key */
  messageDate: number;
  /** `Message-ID` header that went out */
  messageIdHeader: string;
  /** when the key was first used, epoch milliseconds */
  startedAt: number;
}

/** A kept Sent copy, as the sweep finds it. */
export interface UnfiledCopy extends OutboxRef {
  /** when it was kept, epoch milliseconds */
  savedAt: number;
}

/** A send in the outbox, by where it lives. */
export interface OutboxRef {
  /** Firebase uid */
  uid: string;
  /** outbox doc id */
  outboxId: string;
}

/** The outbox reads and writes sending needs. */
export interface OutboxStore {
  /** The entry, or undefined when the doc is gone or malformed. */
  entry(uid: string, outboxId: string): Promise<OutboxEntry | undefined>;
  /** Decide a claim with `decideClaim` and write a claimed or expired entry as `sending` (a transaction). */
  claim(
    uid: string,
    outboxId: string,
    sendAt: number,
    now: number,
  ): Promise<Claim>;
  /** Record a handover about to start; false when the doc is gone (canceled). */
  markAttempt(
    uid: string,
    outboxId: string,
    record: AttemptRecord,
  ): Promise<boolean>;
  /** Move a `sending` entry to `retrying`; false when it is gone or no longer `sending`. */
  markRetrying(
    uid: string,
    outboxId: string,
    record: RetryRecord,
  ): Promise<boolean>;
  /** Move a `sending` entry to `blocked`; false when it is gone or no longer `sending`. */
  markBlocked(
    uid: string,
    outboxId: string,
    record: BlockRecord,
  ): Promise<boolean>;
  /** Blocked sends waiting for `on`: one user's, or (without `uid`) everyone's. */
  blockedSends(on: BlockReason, uid?: string): Promise<OutboxRef[]>;
  /**
   * Turn a send `blocked` on `on` back into `waiting` with its retry count reset (a transaction),
   * which makes the trigger queue it; false when it isn't blocked on that any more.
   */
  unblock(uid: string, outboxId: string, on: BlockReason): Promise<boolean>;
  /**
   * Put a carried attempt record back on a `waiting` entry for `draftId` that has none (a
   * transaction); false when the entry changed meanwhile.
   */
  restoreAttempt(
    uid: string,
    outboxId: string,
    draftId: string,
    carried: CarriedAttempt,
  ): Promise<boolean>;
  /** Record that it went; no-op when the doc is gone. */
  markSent(uid: string, outboxId: string, record: SentRecord): Promise<void>;
  /** Record that the Resend copy is filed and the draft gone. */
  markFiled(
    uid: string,
    outboxId: string,
    messageId: string | null,
    threadId: string | null,
  ): Promise<void>;
  /**
   * Record a failure; no-op when the doc is gone. `resend`, when given, replaces the stored Resend
   * handover record, so a later "Send again" reuses a key only where it must.
   */
  markFailed(
    uid: string,
    outboxId: string,
    via: SendRoute | null,
    code: SendFailure,
    message: string,
    resend?: ResendRecord,
  ): Promise<void>;
  /** The uid that holds `addresses/{local}` once its setup is active, or undefined. */
  activeAddressOwner(local: string): Promise<string | undefined>;
  /**
   * Keep the exact Sent copy of a message that went through Resend until it is filed, only while
   * its outbox doc exists (checked in a transaction); false when it doesn't, and nothing is kept.
   */
  saveSentCopy(uid: string, outboxId: string, copy: SentCopy): Promise<boolean>;
  /**
   * Take a kept Sent copy for filing for `FILING_LEASE_MS` (a transaction): the copy, `busy` while
   * another run holds it, or undefined when there is none.
   */
  claimSentCopy(
    uid: string,
    outboxId: string,
    now: number,
  ): Promise<SentCopy | "busy" | undefined>;
  /** Forget a kept Sent copy once filed. */
  deleteSentCopy(uid: string, outboxId: string): Promise<void>;
  /**
   * Kept Sent copies: one user's saved before `before`, or (without `uid`) everyone's due for
   * another try before `before`, soonest due first, at most `SWEEP_BATCH`.
   */
  unfiledCopies(before: number, uid?: string): Promise<UnfiledCopy[]>;
  /** Leave a kept copy out of the sweep until `until`. */
  deferSentCopy(uid: string, outboxId: string, until: number): Promise<void>;
  /** Whether the user still has an account (their `private/{uid}` doc exists). */
  accountExists(uid: string): Promise<boolean>;
  /** The Resend handover kept for a draft, if any. */
  draftHandover(
    uid: string,
    draftId: string,
  ): Promise<DraftHandover | undefined>;
  /**
   * Keep a draft's Resend handover, only while the outbox doc exists (a transaction); false when
   * it doesn't.
   */
  keepDraftHandover(
    uid: string,
    outboxId: string,
    draftId: string,
    handover: DraftHandover,
  ): Promise<boolean>;
  /** Forget a draft's Resend handover: any, or only the one under `idempotencyKey`. */
  dropDraftHandover(
    uid: string,
    draftId: string,
    idempotencyKey?: string,
  ): Promise<void>;
  /**
   * Waiting sends whose `sendAt`, retrying ones whose `nextAttemptAt`, and sending ones whose
   * claim's lease ended (`claimedAt` + `CLAIM_LEASE_MS`), before `dueBefore`.
   */
  overdueSends(dueBefore: number): Promise<OverdueSend[]>;
}

/**
 * The exact message filed in Sent after it went through Resend (as sent, plus `Bcc`), kept on the
 * server only from the send until it is filed, so filing never depends on the draft.
 */
export interface SentCopy {
  /** the message bytes */
  raw: Buffer;
  /** Gmail draft id, deleted once filed */
  draftId: string;
  /** Gmail thread to file it in, when known */
  threadId: string | null;
  /** its `Message-ID` header */
  messageIdHeader: string;
  /** when it was kept, epoch milliseconds */
  savedAt: number;
}

/** A send whose task should have run by now, as the sweep finds it. */
export interface OverdueSend extends OutboxRef {
  /** `waiting` past its send time, `retrying` past its next attempt, or `sending` past its lease */
  status: "waiting" | "retrying" | "sending";
  /** the entry's send time, epoch milliseconds */
  sendAt: number;
  /** attempts so far */
  attempts: number;
}

/**
 * An SMTP failure. `outcome` is `refused` when the server answered with an error (nothing went)
 * and `unknown` when the connection broke where the message may already have been accepted;
 * `permanent` marks a refusal retrying won't fix; `conflict` marks Resend's answer that the
 * idempotency key was already used for different content; `authFailed` marks a refused login, which
 * only fixing the server's credentials cures.
 */
export class SmtpError extends Error {
  /** whether retrying is pointless */
  readonly permanent: boolean;
  /** whether the server's answer is known */
  readonly outcome: "refused" | "unknown";
  /** whether Resend refused a reused key with different content */
  readonly conflict: boolean;
  /** whether it is a rate or quota limit */
  readonly rateLimited: boolean;
  /** whether the server refused our credentials */
  readonly authFailed: boolean;

  /** Create an SMTP error */
  constructor(
    message: string,
    permanent: boolean,
    options: {
      outcome?: "refused" | "unknown";
      conflict?: boolean;
      rateLimited?: boolean;
      authFailed?: boolean;
    } = {},
  ) {
    super(message);
    this.name = "SmtpError";
    this.permanent = permanent;
    this.outcome = options.outcome ?? "refused";
    this.conflict = options.conflict ?? false;
    this.rateLimited = options.rateLimited ?? false;
    this.authFailed = options.authFailed ?? false;
  }
}

/** SMTP commands after which nothing of the message has been handed over yet. */
const BEFORE_DATA = /^(CONN|EHLO|HELO|LHLO|STARTTLS|AUTH|MAIL FROM|RCPT TO)/i;

/**
 * Classify a nodemailer error. With a reply code, the server answered and nothing went: 5xx is
 * permanent unless it names a rate or quota limit. An authentication failure (535 or nodemailer's
 * `EAUTH`) is a setup problem, marked `authFailed`. Without a code, a failure before `DATA` sent
 * nothing; anything else may have been accepted. Resend's idempotency answers are matched by their
 * names.
 */
export function smtpErrorOf(error: unknown): SmtpError {
  const { message, responseCode, command, response, code } = error as {
    message?: string;
    responseCode?: number;
    command?: string;
    response?: string;
    code?: string;
  };
  const text = `${message ?? String(error)} ${response ?? ""}`;
  if (responseCode === 535 || code === "EAUTH") {
    return new SmtpError(message ?? text, false, { authFailed: true });
  }
  const rateLimited = /rate.?limit|quota|daily.?limit|too many/i.test(text);
  if (/invalid_idempotent_request/i.test(text)) {
    return new SmtpError(message ?? text, true, { conflict: true });
  }
  if (/concurrent_idempotent_requests/i.test(text)) {
    return new SmtpError(message ?? text, false, { outcome: "unknown" });
  }
  if (responseCode !== undefined) {
    return new SmtpError(message ?? text, responseCode >= 500 && !rateLimited, {
      rateLimited,
    });
  }
  const before = typeof command === "string" && BEFORE_DATA.test(command);
  return new SmtpError(message ?? text, false, {
    outcome: before ? "refused" : "unknown",
    rateLimited,
  });
}

/** Sends a finished message over SMTP. */
export interface SmtpSender {
  /** Connect and log in without sending; throws an `SmtpError` when that fails. */
  verify(): Promise<void>;
  /** Transmit `message` to the envelope; resolves with the recipients the server accepted and refused. */
  send(
    envelope: { from: string; to: string[] },
    message: Buffer,
  ): Promise<{ accepted: string[]; rejected: string[] }>;
}

/** The Cloud Tasks queue of `outboxSend`. */
export interface SendQueue {
  /** Queue a task to fire at `at`; a task with the same id already queued counts as done. */
  enqueue(task: SendTask, id: string, at: Date): Promise<void>;
  /** Delete a queued task; a missing one counts as done. */
  remove(id: string): Promise<void>;
}

/** Everything the send handlers talk to, injectable for tests. */
export interface SendDeps {
  /** Firestore */
  store: OutboxStore;
  /** Gmail for a user, or undefined when their grant is gone */
  gmailFor(uid: string): Promise<GmailApi | undefined>;
  /** Resend SMTP, or undefined when no API key is configured */
  smtp: SmtpSender | undefined;
  /** Cloud Tasks */
  queue: SendQueue;
  /** domain whose addresses go through Resend (`hafa.cc`) */
  resendDomain: string;
  /** the clock, epoch milliseconds */
  now(): number;
  /** logger */
  log: Log;
}

/** Result of a send task, for logs and tests. */
export type SendResult =
  | "stale"
  | "rescheduled"
  | "busy"
  | "retrying"
  | "blocked"
  | "sent"
  | "filed"
  | "failed";

const GMAIL_RATE_REASONS = new Set([
  "rateLimitExceeded",
  "userRateLimitExceeded",
  "quotaExceeded",
  "dailyLimitExceeded",
]);

/** Why an error is worth another attempt, or null when it is a refusal retrying won't fix. */
function retryReason(error: unknown): RetryReason | null {
  if (error instanceof GmailError) {
    if (
      error.status === 429 ||
      (error.status === 403 && GMAIL_RATE_REASONS.has(error.reason ?? ""))
    ) {
      return "rate-limited";
    } else if (
      error.status === 401 ||
      error.status === 408 ||
      error.status >= 500
    ) {
      return "server";
    } else {
      return null;
    }
  } else if (error instanceof SmtpError) {
    if (error.rateLimited) {
      return "rate-limited";
    } else if (error.permanent) {
      return null;
    } else {
      return error.outcome === "unknown" ? "network" : "server";
    }
  } else {
    return "network";
  }
}

/** The wait before automatic retry `attempts` (1-based); past the last, the last one's. */
export function retryDelayMs(attempts: number): number {
  const index = Math.min(
    Math.max(attempts, 1) - 1,
    RETRY_DELAYS_MINUTES.length - 1,
  );
  return RETRY_DELAYS_MINUTES[index]! * MINUTE_MS;
}

/** An outbox doc's state as the write trigger sees it. */
export interface OutboxState {
  /** status */
  status: OutboxStatus;
  /** schedule */
  waiting: WaitingSend;
  /** the whole entry, when read */
  entry?: OutboxEntry;
}

/**
 * Replace an outbox doc's send tasks after it changed. A rewrite to `waiting` first gets back the
 * attempt record of the entry it replaced (`carriedAttempt`), so its send can't go twice.
 */
export async function handleOutboxWrite(
  deps: SendDeps,
  uid: string,
  outboxId: string,
  before: OutboxState | null,
  after: OutboxState | null,
): Promise<void> {
  const plan = planOutboxWrite(uid, outboxId, before, after, deps.now());
  const carried =
    plan.schedule === null || after?.entry === undefined
      ? null
      : carriedAttempt(before?.entry, after.entry);
  if (carried !== null && after?.entry !== undefined) {
    await deps.store.restoreAttempt(
      uid,
      outboxId,
      after.entry.draftId,
      carried,
    );
  }
  for (const task of plan.cancel) {
    try {
      await deps.queue.remove(sendTaskId(task));
    } catch (error) {
      deps.log.warn("send task delete failed", {
        uid,
        outboxId,
        message: messageOf(error),
      });
    }
  }
  if (plan.schedule !== null) await enqueueSend(deps, plan.schedule);
}

async function enqueueSend(deps: SendDeps, task: SendTask): Promise<void> {
  await deps.queue.enqueue(
    task,
    sendTaskId(task),
    new Date(Math.max(deps.now(), sendHopTime(task))),
  );
}

function retryTask(task: SendTask, attempt: number): SendTask {
  return { ...task, hop: Math.max(task.hop, 1), attempt };
}

async function enqueueRetry(
  deps: SendDeps,
  task: SendTask,
  attempt: number,
  at: number,
  salt = "",
): Promise<void> {
  const next = retryTask(task, attempt);
  await deps.queue.enqueue(next, sendTaskId(next, salt), new Date(at));
}

type Route =
  | { kind: "route"; via: SendRoute }
  | { kind: "refuse"; code: SendFailure; message: string };

async function chooseRoute(
  deps: SendDeps,
  uid: string,
  gmail: GmailApi,
  from: string,
): Promise<Route> {
  const [local, domain] = from.split("@");
  if (domain === deps.resendDomain) {
    const owner =
      local === undefined
        ? undefined
        : await deps.store.activeAddressOwner(local);
    return owner === uid
      ? { kind: "route", via: "resend" }
      : {
          kind: "refuse",
          code: "not-owned",
          message: `${from} is not this account's active address`,
        };
  }
  const aliases = await gmail.listSendAs();
  const allowed = aliases.some(
    (alias) =>
      alias.sendAsEmail.toLowerCase() === from &&
      (alias.isPrimary === true || alias.verificationStatus === "accepted"),
  );
  return allowed
    ? { kind: "route", via: "gmail" }
    : {
        kind: "refuse",
        code: "not-owned",
        message: `${from} is not a Gmail send-as address of this account`,
      };
}

/**
 * Handle a firing send task.
 *
 * A leg short of the send time only queues the next leg. Otherwise the entry is claimed (it must be
 * `waiting` for this time, or `retrying` with its next attempt due), the draft read, its `From`
 * checked against the entry and the addresses the user owns, and the message sent: through Gmail
 * (`drafts.send`) for the Gmail address and verified send-as aliases, or through Resend SMTP for
 * `@hafa.cc` with a `Resend-Idempotency-Key`, after which the copy is filed in the thread with
 * `SENT` and the draft deleted.
 *
 * A failure retrying might fix moves the entry to `retrying` and queues the next attempt after a
 * short growing wait (`RETRY_DELAYS_MINUTES`); once those few retries are used up, the send ends as
 * `failed` (`unavailable`) for the user to send again. A problem only the user or the server's owner
 * can fix (Google access gone, Resend not set up or refusing its credentials) moves it to
 * `blocked` instead, until `resumeBlocked` turns it back into `waiting`. Before an attempt that
 * follows one whose outcome is unknown, Gmail is asked whether the message went (the draft still
 * existing means it didn't; otherwise the sent message is looked up); Resend is sent the identical
 * message under the same key, which Resend drops as a repeat for 24 hours, so past
 * `RESEND_KEY_WINDOW_MS` such a send fails as `uncertain` instead. Refusals retrying can't fix end
 * as `failed` at once.
 */
export async function handleSendTask(
  deps: SendDeps,
  task: SendTask,
): Promise<SendResult> {
  const { uid, outboxId } = task;
  if (sendHopTime(task) < task.sendAt) {
    const entry = await deps.store.entry(uid, outboxId);
    if (entry?.status !== "waiting" || entry.sendAt !== task.sendAt) {
      return "stale";
    }
    await enqueueSend(deps, { ...task, hop: task.hop + 1 });
    return "rescheduled";
  }

  const claim = await deps.store.claim(uid, outboxId, task.sendAt, deps.now());
  switch (claim.kind) {
    case "stale":
      return "stale";
    case "early": {
      // Salted: this task may itself be the retry, whose id is taken while it runs.
      const at = claim.entry.nextAttemptAt ?? deps.now();
      await enqueueRetry(deps, task, claim.entry.attempts, at, `early:${at}`);
      return "retrying";
    }
    case "busy": {
      const resume = retryTask(task, claim.entry.attempts);
      const claimedAt = claim.entry.claimedAt ?? deps.now();
      await deps.queue.enqueue(
        resume,
        sendTaskId(resume, `lease:${claimedAt}`),
        new Date(Math.max(deps.now(), claimedAt + CLAIM_LEASE_MS + 1000)),
      );
      return "busy";
    }
    case "file": {
      const gmail = await deps.gmailFor(uid);
      if (gmail === undefined) {
        throw new Error("Google access is gone; filing waits for sign-in");
      }
      const filed = await fileSentCopy(deps, gmail, uid, outboxId);
      if (filed === "busy") {
        throw new Error("another run is filing this copy; check again later");
      }
      if (filed === "missing") {
        // The sweep dropped the copy unfiled (no Google access for too long): nothing is left to
        // file, but the sent message's draft must still leave Drafts.
        await deleteDraftQuietly(gmail, claim.entry.draftId);
        await deps.store.markFiled(uid, outboxId, null, null);
        await deps.store.dropDraftHandover(uid, claim.entry.draftId);
        deps.log.info("no Sent copy left to file", { uid, outboxId });
        return "stale";
      }
      return "filed";
    }
    case "expired":
      if (claim.entry.deviceClaim !== null) {
        deps.log.warn("taking over a device's send", { uid, outboxId });
      }
      return claim.entry.attempts > RETRY_DELAYS_MINUTES.length
        ? giveUpAfterDeadRuns(deps, task, claim.entry)
        : attempt(deps, task, claim.entry, true);
    default:
      return attempt(deps, task, claim.entry, false);
  }
}

/**
 * End a send whose runs kept dying mid-attempt (out of memory, or past the time limit) as `failed`
 * (`unavailable`), keeping what a later "Send again" needs to never send twice.
 */
async function giveUpAfterDeadRuns(
  deps: SendDeps,
  task: SendTask,
  entry: OutboxEntry,
): Promise<SendResult> {
  const { uid, outboxId } = task;
  const resend = entry.via === "resend";
  const uncertainSince =
    entry.uncertainSince ?? (resend ? entry.inFlightAt : null);
  const keep = resend && uncertainSince !== null;
  await deps.store.markFailed(
    uid,
    outboxId,
    entry.via,
    "unavailable",
    entry.inFlightAt === null
      ? `Sending stopped partway ${entry.attempts} times`
      : `Sending stopped partway ${entry.attempts} times; one of them may have gone through`,
    {
      uncertainSince,
      messageDate: keep ? entry.messageDate : null,
      idempotencyKey: keep ? entry.idempotencyKey : null,
    },
  );
  deps.log.warn("send failed", {
    uid,
    outboxId,
    code: "unavailable",
    deadRuns: true,
  });
  return "failed";
}

async function attempt(
  deps: SendDeps,
  task: SendTask,
  claimed: OutboxEntry,
  expired: boolean,
): Promise<SendResult> {
  const { uid, outboxId } = task;
  const now = deps.now();
  let entry: OutboxEntry =
    expired && claimed.via === "resend" && claimed.inFlightAt !== null
      ? {
          ...claimed,
          uncertainSince: claimed.uncertainSince ?? claimed.inFlightAt,
        }
      : claimed;

  const fail = async (
    via: SendRoute | null,
    code: SendFailure,
    message: string,
  ): Promise<SendResult> => {
    await deps.store.markFailed(uid, outboxId, via, code, message);
    deps.log.warn("send failed", { uid, outboxId, code });
    return "failed";
  };

  const retry = async (
    via: SendRoute | null,
    reason: RetryReason,
    message: string,
    options: { unknownSince?: number } = {},
  ): Promise<SendResult> => {
    const attempts = entry.attempts + 1;
    const uncertainSince = entry.uncertainSince ?? options.unknownSince ?? null;
    let nextAttemptAt = now + retryDelayMs(attempts);
    if (via === "resend" && uncertainSince !== null) {
      const keyDeadline = uncertainSince + RESEND_KEY_WINDOW_MS;
      if (keyDeadline <= now + MINUTE_MS) {
        return fail(
          via,
          "uncertain",
          `Sending through Resend may or may not have gone through and can't be retried safely: ${message}`,
        );
      }
      nextAttemptAt = Math.min(nextAttemptAt, keyDeadline - MINUTE_MS);
    }
    const resend = handoverToKeep(via, uncertainSince);
    if (attempts > RETRY_DELAYS_MINUTES.length) {
      const text =
        uncertainSince === null
          ? `Still failing after ${attempts} tries: ${message}`
          : `Still failing after ${attempts} tries, one of which may have gone through: ${message}`;
      await deps.store.markFailed(
        uid,
        outboxId,
        via,
        "unavailable",
        text,
        resend,
      );
      deps.log.warn("send failed", { uid, outboxId, code: "unavailable" });
      return "failed";
    }
    const moved = await deps.store.markRetrying(uid, outboxId, {
      via,
      attempts,
      nextAttemptAt,
      reason,
      message,
      ...resend,
    });
    if (!moved) return "stale";
    await enqueueRetry(deps, task, attempts, nextAttemptAt);
    deps.log.info("send will retry", {
      uid,
      outboxId,
      attempts,
      reason,
      nextAttemptAt,
    });
    return "retrying";
  };

  /** What to keep of the Resend handover: its key and `Date` only while its outcome is unknown. */
  function handoverToKeep(
    via: SendRoute | null,
    uncertainSince: number | null,
  ): ResendRecord {
    const keep = via === "resend" && uncertainSince !== null;
    return {
      uncertainSince,
      messageDate: keep ? entry.messageDate : null,
      idempotencyKey: keep ? entry.idempotencyKey : null,
    };
  }

  const block = async (
    via: SendRoute | null,
    on: BlockReason,
    message: string,
  ): Promise<SendResult> => {
    const moved = await deps.store.markBlocked(uid, outboxId, {
      via,
      on,
      message,
      ...handoverToKeep(via, entry.uncertainSince),
    });
    if (!moved) return "stale";
    deps.log.warn("send blocked", { uid, outboxId, on });
    return "blocked";
  };

  const retryOrFail = (
    via: SendRoute | null,
    error: unknown,
  ): Promise<SendResult> => {
    const reason = retryReason(error);
    return reason === null
      ? fail(via, "rejected", messageOf(error))
      : retry(via, reason, messageOf(error));
  };

  let gmail: GmailApi | undefined;
  try {
    gmail = await deps.gmailFor(uid);
  } catch (error) {
    return retry(entry.via, "server", messageOf(error));
  }
  if (gmail === undefined) {
    return block(
      entry.via,
      "sign-in",
      "Google access was revoked; sign in again",
    );
  }

  // The draft's From must be the doc's, so its route is looked up alongside the draft rather than
  // after it.
  const expectedFrom = addressKey(entry.from);
  const routing = chooseRoute(deps, uid, gmail, expectedFrom).then(
    (route) => ({ route, error: null }),
    (error: unknown) => ({ route: null, error }),
  );
  // Headers alone first: a Gmail send never needs the body, which can run to tens of megabytes.
  let draftHeaders: GmailDraftHeaders;
  try {
    draftHeaders = await gmail.getDraftHeaders(entry.draftId);
  } catch (error) {
    if (error instanceof GmailError && error.status === 404) {
      return draftGone(deps, task, entry, gmail, fail, retryOrFail);
    } else {
      return retryOrFail(entry.via, error);
    }
  }
  const from = fromAddress(draftHeaders.message.payload?.headers ?? []);
  if (from === undefined || from !== expectedFrom) {
    return fail(
      null,
      "from-mismatch",
      `The draft is from ${from ?? "no one"}, not ${entry.from}`,
    );
  }
  const draftProblem = headerProblem(
    draftHeaders.message.payload?.headers ?? [],
  );
  if (draftProblem !== null) return fail(null, "rejected", draftProblem);

  const routed = await routing;
  if (routed.route === null) return retryOrFail(entry.via, routed.error);
  const { route } = routed;
  if (route.kind === "refuse") return fail(null, route.code, route.message);

  if (route.via === "gmail") {
    const draftMessageIdHeader =
      headerValue(draftHeaders.message.payload?.headers ?? [], "Message-ID") ??
      null;
    const draftMessageId = draftHeaders.message.id;
    const started = await deps.store.markAttempt(uid, outboxId, {
      via: "gmail",
      inFlightAt: now,
      draftMessageId,
      messageIdHeader: draftMessageIdHeader,
      messageDate: null,
      idempotencyKey: null,
      uncertainSince: null,
    });
    if (!started) return "stale";
    try {
      const sent = await gmail.sendDraft(entry.draftId);
      await deps.store.markSent(uid, outboxId, {
        via: "gmail",
        sentAt: deps.now(),
        messageId: sent.id,
        threadId: sent.threadId,
        messageIdHeader: draftMessageIdHeader,
        filed: true,
        rejected: [],
      });
      return "sent";
    } catch (error) {
      if (error instanceof GmailError && error.status === 404) {
        return draftGone(
          deps,
          task,
          {
            ...entry,
            via: "gmail",
            draftMessageId,
            messageIdHeader: draftMessageIdHeader,
          },
          gmail,
          fail,
          retryOrFail,
        );
      }
      return retryOrFail("gmail", error);
    }
  }

  if (deps.smtp === undefined) {
    return block("resend", "resend", "Sending through Resend is not set up");
  }
  let draft: GmailRawDraft;
  try {
    draft = await gmail.getDraftRaw(entry.draftId);
  } catch (error) {
    if (error instanceof GmailError && error.status === 404) {
      return draftGone(deps, task, entry, gmail, fail, retryOrFail);
    } else {
      return retryOrFail(entry.via, error);
    }
  }
  const raw = decodeRaw(draft.message.raw);
  const split = splitMessage(raw);
  if (split.strayLineBreak) {
    return fail(
      null,
      "rejected",
      "The draft's header has a stray line break, so it can't be sent safely",
    );
  }
  const sentProblem =
    headerProblem(split.headers) ??
    ownAddressProblem(split.headers, from, deps.resendDomain);
  if (sentProblem !== null) return fail(null, "rejected", sentProblem);
  // The bytes that go out are checked too: the draft may have changed since its headers were read.
  if (fromAddress(split.headers) !== from) {
    return fail(
      null,
      "from-mismatch",
      `The draft is no longer from ${entry.from}`,
    );
  }
  // A doc made again for a draft whose earlier doc was deleted mid-retry carries no key; the
  // draft's own record still does.
  if (entry.idempotencyKey === null || entry.messageDate === null) {
    const kept = await deps.store.draftHandover(uid, entry.draftId);
    if (kept !== undefined && now - kept.startedAt < RESEND_KEY_WINDOW_MS) {
      entry = {
        ...entry,
        idempotencyKey: kept.idempotencyKey,
        messageDate: kept.messageDate,
        messageIdHeader: kept.messageIdHeader,
        uncertainSince: entry.uncertainSince ?? kept.startedAt,
      };
    }
  }
  if (
    entry.uncertainSince !== null &&
    now >= entry.uncertainSince + RESEND_KEY_WINDOW_MS
  ) {
    return fail(
      "resend",
      "uncertain",
      "An earlier attempt may or may not have gone through, and it is too late to retry it safely",
    );
  }
  const reuse = entry.idempotencyKey !== null && entry.messageDate !== null;
  const messageDate = reuse ? (entry.messageDate ?? now) : now;
  const idempotencyKey = reuse
    ? (entry.idempotencyKey ?? "")
    : idempotencyKeyFor(uid, outboxId, now);
  const prepared = prepareForSmtp(
    raw,
    messageDate,
    entry.messageIdHeader ?? newMessageId(deps.resendDomain),
    [[IDEMPOTENCY_HEADER, idempotencyKey]],
  );
  const started = await deps.store.markAttempt(uid, outboxId, {
    via: "resend",
    inFlightAt: now,
    draftMessageId: null,
    messageIdHeader: prepared.messageId,
    messageDate,
    idempotencyKey,
    uncertainSince: entry.uncertainSince,
  });
  if (!started) return "stale";
  const kept = await deps.store.keepDraftHandover(
    uid,
    outboxId,
    entry.draftId,
    {
      idempotencyKey,
      messageDate,
      messageIdHeader: prepared.messageId,
      startedAt: reuse ? (entry.uncertainSince ?? now) : now,
    },
  );
  if (!kept) return "stale";
  entry = {
    ...entry,
    messageDate,
    idempotencyKey,
    messageIdHeader: prepared.messageId,
  };
  // Nothing went, so a later send of this draft may go as new.
  const forgetHandover = () =>
    deps.store.dropDraftHandover(uid, entry.draftId, idempotencyKey);
  let result: { accepted: string[]; rejected: string[] };
  try {
    result = await deps.smtp.send(prepared.envelope, prepared.outgoing);
  } catch (error) {
    if (error instanceof SmtpError && error.conflict) {
      // The key is spent on other content; "Send again" goes as new, as the user is told.
      await forgetHandover();
      return fail(
        "resend",
        "uncertain",
        "Resend already took a different version of this message; an earlier attempt may have gone through",
      );
    }
    if (error instanceof SmtpError && error.authFailed) {
      await forgetHandover();
      return block(
        "resend",
        "resend",
        `Resend refused the server's credentials: ${messageOf(error)}`,
      );
    }
    const unknown = !(
      error instanceof SmtpError && error.outcome === "refused"
    );
    if (!unknown) await forgetHandover();
    const reason = retryReason(error);
    if (reason === null) {
      return fail("resend", "rejected", messageOf(error));
    } else {
      return retry(
        "resend",
        reason,
        messageOf(error),
        unknown ? { unknownSince: now } : {},
      );
    }
  }
  if (result.accepted.length === 0) {
    await forgetHandover();
    return fail("resend", "rejected", "Every recipient was refused");
  }
  const copy: SentCopy = {
    raw: prepared.sentCopy,
    draftId: entry.draftId,
    threadId: draft.message.threadId,
    messageIdHeader: prepared.messageId,
    savedAt: now,
  };
  if (!(await deps.store.saveSentCopy(uid, outboxId, copy))) {
    // The doc went while the message was out (the account is being deleted): file it now if
    // Gmail still lets us, and keep nothing.
    try {
      await insertSentCopy(gmail, copy);
      await deleteDraftQuietly(gmail, entry.draftId);
    } catch (error) {
      deps.log.warn("filing a copy whose doc is gone failed", {
        uid,
        outboxId,
        message: redact(messageOf(error)),
      });
    }
    return "sent";
  }
  await deps.store.markSent(uid, outboxId, {
    via: "resend",
    sentAt: messageDate,
    messageId: null,
    threadId: draft.message.threadId,
    messageIdHeader: prepared.messageId,
    filed: false,
    rejected: result.rejected,
  });
  try {
    await fileSentCopy(deps, gmail, uid, outboxId);
  } catch (error) {
    // The copy is kept, so the draft is no longer needed; deleting it now keeps it from being
    // edited or sent again while filing waits for a retry.
    await deleteDraftQuietly(gmail, entry.draftId).catch(() => {});
    throw error;
  }
  return "sent";
}

/**
 * The draft is gone. When an earlier Gmail attempt handed it to `drafts.send`, look for the sent
 * message (by the draft's Gmail message id, which the sent message keeps, then by `Message-ID` in
 * Sent) and record it as sent; otherwise the user deleted it, and the send fails.
 */
async function draftGone(
  deps: SendDeps,
  task: SendTask,
  entry: OutboxEntry,
  gmail: GmailApi,
  fail: (
    via: SendRoute | null,
    code: SendFailure,
    message: string,
  ) => Promise<SendResult>,
  retryOrFail: (via: SendRoute | null, error: unknown) => Promise<SendResult>,
): Promise<SendResult> {
  const { uid, outboxId } = task;
  if (entry.via === "gmail") {
    try {
      const found = await sentMessage(gmail, entry);
      if (found !== undefined) {
        await deps.store.markSent(uid, outboxId, {
          via: "gmail",
          sentAt: deps.now(),
          messageId: found.id,
          threadId: found.threadId,
          messageIdHeader: entry.messageIdHeader,
          filed: true,
          rejected: [],
        });
        return "sent";
      }
    } catch (error) {
      return retryOrFail("gmail", error);
    }
  }
  return fail(
    entry.via,
    "draft-missing",
    entry.uncertainSince === null
      ? "The draft no longer exists"
      : "The draft no longer exists; an earlier attempt may have gone through",
  );
}

async function sentMessage(
  gmail: GmailApi,
  entry: OutboxEntry,
): Promise<{ id: string; threadId: string } | undefined> {
  if (entry.draftMessageId !== null) {
    try {
      const message = await gmail.getMessage(entry.draftMessageId);
      if (message.labelIds?.includes(LABEL.sent) === true) return message;
    } catch (error) {
      if (!(error instanceof GmailError && error.status === 404)) throw error;
    }
  }
  if (entry.messageIdHeader !== null) {
    return sentCopyIn(gmail, entry.messageIdHeader);
  } else {
    return undefined;
  }
}

/**
 * File the kept copy of a message sent through Resend: insert it into its thread with `SENT` (unless
 * an earlier run already did), delete the draft, mark the entry filed (if the doc is still there)
 * and forget the copy. `missing` when no copy is kept, `busy` while another run files it. Errors
 * propagate; the copy stays until filing succeeds.
 */
async function fileSentCopy(
  deps: Pick<SendDeps, "store" | "now">,
  gmail: GmailApi,
  uid: string,
  outboxId: string,
): Promise<"filed" | "busy" | "missing"> {
  const copy = await deps.store.claimSentCopy(uid, outboxId, deps.now());
  if (copy === undefined) return "missing";
  if (copy === "busy") return "busy";
  const filed =
    (await sentCopyIn(gmail, copy.messageIdHeader)) ??
    (await insertSentCopy(gmail, copy));
  await deleteDraftQuietly(gmail, copy.draftId);
  await deps.store.markFiled(uid, outboxId, filed.id, filed.threadId);
  await deps.store.deleteSentCopy(uid, outboxId);
  await deps.store.dropDraftHandover(uid, copy.draftId);
  return "filed";
}

async function sentCopyIn(
  gmail: GmailApi,
  messageIdHeader: string,
): Promise<{ id: string; threadId: string } | undefined> {
  const [found] = await gmail.listMessages(
    `rfc822msgid:${messageIdHeader.replace(/^<|>$/g, "")} in:sent`,
    1,
  );
  return found;
}

/** Insert into the draft's thread; when Gmail won't take that thread (gone with the draft), alone. */
async function insertSentCopy(
  gmail: GmailApi,
  copy: SentCopy,
): Promise<{ id: string; threadId: string }> {
  const raw = encodeRaw(copy.raw);
  if (copy.threadId !== null) {
    try {
      return await gmail.insertMessage(raw, copy.threadId, [LABEL.sent]);
    } catch (error) {
      if (
        !(
          error instanceof GmailError &&
          (error.status === 404 || error.status === 400)
        )
      ) {
        throw error;
      }
    }
  }
  return gmail.insertMessage(raw, undefined, [LABEL.sent]);
}

async function deleteDraftQuietly(
  gmail: GmailApi,
  draftId: string,
): Promise<void> {
  try {
    await gmail.deleteDraft(draftId);
  } catch (error) {
    if (!(error instanceof GmailError && error.status === 404)) throw error;
  }
}

/**
 * File the Sent copies still kept: one user's (after they sign in again), or everyone's due (the
 * sweep, for filings whose task gave up; a copy is first due `SWEEP_MARGIN_MS` after it was kept).
 * The sweep puts off a copy it can't file, so stuck copies never hold up the rest: by
 * `NO_ACCESS_RECHECK_MS` while its owner has no Google access, by `FILING_RETRY_MS` after a
 * failure. It deletes a copy whose account is gone, and one whose owner has had no Google access
 * for `COPY_KEEP_WITHOUT_ACCESS_MS` since it was kept. Returns how many were filed.
 */
export async function resumeFiling(
  deps: SendDeps,
  uid?: string,
): Promise<number> {
  const now = deps.now();
  const refs = await deps.store.unfiledCopies(now, uid);
  const gmailByUser = new Map<string, Promise<GmailApi | undefined>>();
  const gmailOf = (owner: string) => {
    let gmail = gmailByUser.get(owner);
    if (gmail === undefined) {
      gmail = deps.gmailFor(owner);
      gmailByUser.set(owner, gmail);
    }
    return gmail;
  };
  const outcomes = await mapLimit(refs, SWEEP_CONCURRENCY, async (ref) => {
    try {
      const gmail = await gmailOf(ref.uid);
      if (gmail !== undefined) {
        return await fileSentCopy(deps, gmail, ref.uid, ref.outboxId);
      } else if (
        !(await deps.store.accountExists(ref.uid)) ||
        now - ref.savedAt >= COPY_KEEP_WITHOUT_ACCESS_MS
      ) {
        await deps.store.deleteSentCopy(ref.uid, ref.outboxId);
        deps.log.info("kept Sent copy deleted unfiled", {
          uid: ref.uid,
          outboxId: ref.outboxId,
        });
        return "dropped";
      } else {
        await deps.store.deferSentCopy(
          ref.uid,
          ref.outboxId,
          now + NO_ACCESS_RECHECK_MS,
        );
        return "skipped";
      }
    } catch (error) {
      deps.log.warn("filing the Sent copy failed", {
        ...ref,
        message: redact(messageOf(error)),
      });
      await deps.store
        .deferSentCopy(ref.uid, ref.outboxId, now + FILING_RETRY_MS)
        .catch(() => {});
      return "failed";
    }
  });
  const filed = outcomes.filter((outcome) => outcome === "filed").length;
  if (filed > 0) deps.log.info("Sent copies filed", { uid, filed });
  return filed;
}

/**
 * Queue again the sends whose task should have run more than `SWEEP_MARGIN_MS` ago but didn't
 * (its queueing failed for good, or it gave up): `waiting` past `sendAt`, `retrying` past
 * `nextAttemptAt`, `sending` past its lease (a device that claimed a send now and died, when no
 * task came to find it held). Each gets a fresh task id; a send that is in fact still queued just
 * finds itself claimed, and a dead claim is taken over. Returns how many were queued.
 */
export async function requeueOverdueSends(deps: SendDeps): Promise<number> {
  const now = deps.now();
  const overdue = await deps.store.overdueSends(now - SWEEP_MARGIN_MS);
  const outcomes = await mapLimit(overdue, SWEEP_CONCURRENCY, async (send) => {
    const task: SendTask = {
      uid: send.uid,
      outboxId: send.outboxId,
      sendAt: send.sendAt,
      writtenAt: send.sendAt,
      hop: 1,
      attempt: send.status === "waiting" ? 0 : send.attempts,
    };
    try {
      await deps.queue.enqueue(
        task,
        sendTaskId(task, `sweep:${now}`),
        new Date(now),
      );
      return true;
    } catch (error) {
      deps.log.warn("queuing an overdue send failed", {
        uid: send.uid,
        outboxId: send.outboxId,
        message: messageOf(error),
      });
      return false;
    }
  });
  const queued = outcomes.filter(Boolean).length;
  if (queued > 0) deps.log.warn("overdue sends queued again", { queued });
  return queued;
}

/**
 * Turn sends blocked on `on` back into `waiting` (one user's with `uid`), so each goes again and
 * gets its retries afresh. Returns how many were resumed.
 */
export async function resumeBlocked(
  deps: Pick<SendDeps, "store" | "log">,
  on: BlockReason,
  uid?: string,
): Promise<number> {
  return unblockAll(deps, on, await deps.store.blockedSends(on, uid), uid);
}

async function unblockAll(
  deps: Pick<SendDeps, "store" | "log">,
  on: BlockReason,
  blocked: readonly OutboxRef[],
  uid: string | undefined,
): Promise<number> {
  const outcomes = await mapLimit(blocked, SWEEP_CONCURRENCY, (ref) =>
    deps.store.unblock(ref.uid, ref.outboxId, on),
  );
  const resumed = outcomes.filter(Boolean).length;
  if (resumed > 0) deps.log.info("sends resumed", { on, uid, resumed });
  return resumed;
}

/**
 * Resume sends blocked on Resend once it works again: when any are blocked, check that the server
 * can log in to Resend's SMTP (only then), and if so turn them back into `waiting`. Returns how many
 * were resumed.
 */
export async function resumeResendSends(deps: SendDeps): Promise<number> {
  const blocked = await deps.store.blockedSends("resend");
  if (blocked.length === 0 || deps.smtp === undefined) return 0;
  try {
    await deps.smtp.verify();
  } catch (error) {
    deps.log.warn("Resend still unusable", {
      blocked: blocked.length,
      message: redact(messageOf(error)),
    });
    return 0;
  }
  return unblockAll(deps, "resend", blocked, undefined);
}
