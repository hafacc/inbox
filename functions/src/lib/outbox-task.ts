import { isHop, legTime, pendingHops, taskIdOf } from "./task-legs.js";
import { isFiniteNumber, millisOf } from "./util.js";

/**
 * Where an outbox entry is in its life. Clients write `waiting`; the server writes the rest, but for
 * a device sending a send now itself, which claims it (`sending`) and records how it ended.
 */
export type OutboxStatus =
  | "waiting"
  | "sending"
  | "retrying"
  | "blocked"
  | "sent"
  | "failed";

/** What a `blocked` send waits for, as `outbox.blockedOn`. */
export type BlockReason =
  /** Google access was revoked or no refresh token is stored; the next sign-in resumes it */
  | "sign-in"
  /** Resend isn't set up on the server or refused its credentials; a scheduled check resumes it */
  | "resend";

/** How a message left. */
export type SendRoute = "gmail" | "resend";

/** Why a send failed for good, as `outbox.error.code`. */
export type SendFailure =
  | "draft-missing"
  | "from-mismatch"
  | "not-owned"
  | "rejected"
  | "uncertain"
  | "unavailable";

/** Why the last attempt didn't go and another is coming, as `outbox.lastError.code`. */
export type RetryReason = "network" | "rate-limited" | "server";

/** The fields of `users/{uid}/outbox/{id}` the server reads. */
export interface OutboxEntry {
  /** state */
  status: OutboxStatus;
  /** Gmail draft id */
  draftId: string;
  /** sender address as the client chose it */
  from: string;
  /** when to send, epoch milliseconds */
  sendAt: number;
  /** when the server took the entry for sending, epoch milliseconds */
  claimedAt: number | null;
  /** route recorded once known */
  via: SendRoute | null;
  /** when it went, epoch milliseconds */
  sentAt: number | null;
  /** `Message-ID` header of mail sent through Resend */
  messageIdHeader: string | null;
  /** whether the Resend copy is filed in Sent and the draft deleted */
  filed: boolean;
  /** attempts that ended without sending */
  attempts: number;
  /** when the first attempt began, epoch milliseconds */
  firstAttemptAt: number | null;
  /** when the next attempt is due while `retrying`, epoch milliseconds */
  nextAttemptAt: number | null;
  /** when the current attempt began handing the message over, until its outcome is known */
  inFlightAt: number | null;
  /** start of the earliest Resend handover whose outcome is unknown */
  uncertainSince: number | null;
  /** Gmail message id of the draft as it was handed to `drafts.send` */
  draftMessageId: string | null;
  /** `Date` (epoch milliseconds) of the message being handed to Resend, kept with its key */
  messageDate: number | null;
  /** `Resend-Idempotency-Key` of the message being handed to Resend */
  idempotencyKey: string | null;
  /** what a `blocked` entry waits for */
  blockedOn: BlockReason | null;
  /** `error.code` of a `failed` entry */
  errorCode: string | null;
  /** the claim of a device sending a send now itself, until a server claim replaces it */
  deviceClaim: string | null;
}

/** Payload of a send task. */
export interface SendTask {
  /** Firebase uid */
  uid: string;
  /** outbox doc id */
  outboxId: string;
  /** the send time this task is for, epoch milliseconds */
  sendAt: number;
  /** when the scheduling write happened, epoch milliseconds */
  writtenAt: number;
  /** which leg of a send more than 29 days out this is, from 1 */
  hop: number;
  /** 0 for the scheduled send; n for the retry after n failed attempts */
  attempt: number;
}

/** A waiting entry's schedule, as the trigger sees it. */
export interface WaitingSend {
  /** the send time, epoch milliseconds */
  sendAt: number;
  /** when the doc was written, epoch milliseconds */
  writtenAt: number;
}

/** What to do with send tasks after an outbox doc changed. */
export interface SendWritePlan {
  /** legs of the old schedule to delete, if still queued */
  cancel: SendTask[];
  /** the first leg of the new schedule */
  schedule: SendTask | null;
}

const STATUSES: readonly OutboxStatus[] = [
  "waiting",
  "sending",
  "retrying",
  "blocked",
  "sent",
  "failed",
];

/** Read an outbox doc; undefined when it is malformed. */
export function outboxFromDoc(
  data: Record<string, unknown> | undefined,
): OutboxEntry | undefined {
  if (data === undefined) return undefined;
  const {
    status,
    draftId,
    from,
    via,
    messageIdHeader,
    filed,
    attempts,
    draftMessageId,
    idempotencyKey,
    blockedOn,
    error,
    deviceClaim,
  } = data;
  const errorCode =
    typeof error === "object" && error !== null
      ? (error as { code?: unknown }).code
      : undefined;
  const sendAt = millisOf(data.sendAt);
  if (
    !STATUSES.includes(status as OutboxStatus) ||
    typeof draftId !== "string" ||
    draftId === "" ||
    typeof from !== "string" ||
    sendAt === undefined
  ) {
    return undefined;
  }
  return {
    status: status as OutboxStatus,
    draftId,
    from,
    sendAt,
    claimedAt: millisOf(data.claimedAt) ?? null,
    via: via === "gmail" || via === "resend" ? via : null,
    sentAt: millisOf(data.sentAt) ?? null,
    messageIdHeader:
      typeof messageIdHeader === "string" ? messageIdHeader : null,
    filed: filed === true,
    attempts:
      isFiniteNumber(attempts) && attempts >= 0 ? Math.floor(attempts) : 0,
    firstAttemptAt: millisOf(data.firstAttemptAt) ?? null,
    nextAttemptAt: millisOf(data.nextAttemptAt) ?? null,
    inFlightAt: millisOf(data.inFlightAt) ?? null,
    uncertainSince: millisOf(data.uncertainSince) ?? null,
    draftMessageId: typeof draftMessageId === "string" ? draftMessageId : null,
    messageDate: millisOf(data.messageDate) ?? null,
    idempotencyKey: typeof idempotencyKey === "string" ? idempotencyKey : null,
    blockedOn:
      blockedOn === "sign-in" || blockedOn === "resend" ? blockedOn : null,
    errorCode: typeof errorCode === "string" ? errorCode : null,
    deviceClaim: typeof deviceClaim === "string" ? deviceClaim : null,
  };
}

/** When a task leg is scheduled to fire. */
export function sendHopTime(task: SendTask): number {
  return legTime(task.sendAt, task.writtenAt, task.hop);
}

/**
 * A stable task id for a leg or retry, so the trigger can delete it later without storing it and a
 * second enqueue of the same retry is a no-op. `salt` sets apart extra tasks for the same attempt
 * (the check after a held claim's lease).
 */
export function sendTaskId(task: SendTask, salt = ""): string {
  const parts: unknown[] = [
    "send",
    task.uid,
    task.outboxId,
    task.sendAt,
    task.writtenAt,
    task.hop,
  ];
  if (task.attempt !== 0 || salt !== "") parts.push(task.attempt, salt);
  return taskIdOf(parts);
}

/** The legs of a schedule that may still be queued at `now`. */
export function pendingSendTasks(
  uid: string,
  outboxId: string,
  waiting: WaitingSend,
  now: number,
): SendTask[] {
  const { sendAt, writtenAt } = waiting;
  return pendingHops(sendAt, writtenAt, now).map((hop) => ({
    uid,
    outboxId,
    sendAt,
    writtenAt,
    hop,
    attempt: 0,
  }));
}

/**
 * Plan task changes for a write to `users/{uid}/outbox/{id}`, given the doc's status and schedule
 * before and after. A new or changed waiting schedule gets a task; the old one's legs are deleted.
 * The server's own writes (`waiting` → `sending` → `sent`/`failed`/`blocked`, and `sending` ⇄
 * `retrying`) need no new task: the running task carries on or queues its own retry. A `blocked` or
 * `failed` entry turned back into `waiting` (by the server when what it waited for is fixed, or by
 * the client's "Send again") is scheduled like a new one.
 */
export function planOutboxWrite(
  uid: string,
  outboxId: string,
  before: { status: OutboxStatus; waiting: WaitingSend } | null,
  after: { status: OutboxStatus; waiting: WaitingSend } | null,
  now: number,
): SendWritePlan {
  const wasWaiting = before?.status === "waiting";
  const isWaiting = after?.status === "waiting";
  const sameTime =
    wasWaiting && isWaiting && before.waiting.sendAt === after.waiting.sendAt;
  return {
    cancel:
      wasWaiting && !sameTime && after?.status !== "sending"
        ? pendingSendTasks(uid, outboxId, before.waiting, now)
        : [],
    schedule:
      isWaiting && !sameTime
        ? {
            uid,
            outboxId,
            sendAt: after.waiting.sendAt,
            writtenAt: after.waiting.writtenAt,
            hop: 1,
            attempt: 0,
          }
        : null,
  };
}

/** Validate a send task payload, returning undefined when malformed. */
export function parseSendTask(data: unknown): SendTask | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const { uid, outboxId, sendAt, writtenAt, hop, attempt } = data as Record<
    string,
    unknown
  >;
  if (
    typeof uid !== "string" ||
    uid === "" ||
    typeof outboxId !== "string" ||
    outboxId === "" ||
    !isFiniteNumber(sendAt) ||
    !isFiniteNumber(writtenAt) ||
    !isHop(hop) ||
    !isFiniteNumber(attempt) ||
    !Number.isInteger(attempt) ||
    attempt < 0
  ) {
    return undefined;
  } else {
    return { uid, outboxId, sendAt, writtenAt, hop, attempt };
  }
}
