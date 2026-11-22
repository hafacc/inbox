import { logger } from "firebase-functions/v2";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onTaskDispatched } from "firebase-functions/v2/tasks";
import {
  GOOGLE_CLIENT_SECRET,
  isStaleEvent,
  RESEND_API_KEY,
} from "./config.js";
import {
  handleOutboxWrite,
  handleSendTask,
  type OutboxState,
  resumeResendSends,
} from "./lib/outbox.js";
import { outboxFromDoc, parseSendTask } from "./lib/outbox-task.js";
import { liveSendDeps, resendSmtp } from "./outbox-deps.js";

/**
 * Times Cloud Tasks re-runs a task whose handler threw (a crash, a Firestore error, a failed Sent
 * filing). Send retries don't use this: the handler queues each retry as its own task.
 */
const TASK_MAX_ATTEMPTS = 12;

interface OutboxSnapshot {
  exists: boolean;
  data(): Record<string, unknown> | undefined;
  updateTime?: { toMillis(): number };
}

function stateOf(snapshot: OutboxSnapshot): OutboxState | null {
  if (!snapshot.exists) return null;
  const entry = outboxFromDoc(snapshot.data());
  if (entry === undefined) {
    return null;
  } else {
    return {
      status: entry.status,
      waiting: {
        sendAt: entry.sendAt,
        writtenAt: snapshot.updateTime?.toMillis() ?? 0,
      },
      entry,
    };
  }
}

/**
 * Keeps one send task per waiting outbox entry: queues it on create, reschedule, "Send again" or
 * resume (restoring the attempt record a rewrite dropped), deletes it on undo. Retried when it
 * throws (every step is safe to repeat); `sweepStuck` catches what retries don't.
 */
export const outboxChanged = onDocumentWritten(
  { document: "users/{uid}/outbox/{outboxId}", retry: true },
  async (event) => {
    const change = event.data;
    if (change === undefined || isStaleEvent(event.time)) return;
    await handleOutboxWrite(
      liveSendDeps(),
      event.params.uid,
      event.params.outboxId,
      stateOf(change.before),
      stateOf(change.after),
    );
  },
);

/**
 * Fires at an entry's `sendAt`, and again for each retry: if it still says to, sends the Gmail
 * draft through Gmail or, for `@hafa.cc`, through Resend and files the copy in Sent. A send more
 * than 29 days out arrives in legs; each earlier leg only queues the next.
 */
export const outboxSend = onTaskDispatched(
  {
    secrets: [GOOGLE_CLIENT_SECRET, RESEND_API_KEY],
    retryConfig: {
      maxAttempts: TASK_MAX_ATTEMPTS,
      minBackoffSeconds: 60,
      maxBackoffSeconds: 3600,
    },
    rateLimits: { maxConcurrentDispatches: 10 },
    timeoutSeconds: 120,
    // A Resend send holds a draft of up to Gmail's size limit several times over.
    memory: "1GiB",
  },
  async (request) => {
    const task = parseSendTask(request.data);
    if (task === undefined) {
      logger.warn("malformed send task");
      return;
    }
    const result = await handleSendTask(liveSendDeps(resendSmtp()), task);
    logger.info("outbox send", { result, outboxId: task.outboxId });
  },
);

/**
 * Every 30 minutes, resumes sends blocked on Resend once it works again. It costs one empty query
 * while none are blocked; when some are, it logs in to Resend's SMTP once and, if that works, turns
 * them back into `waiting`. Setting or fixing `RESEND_API_KEY` needs a redeploy, after which the
 * next run picks them up.
 */
export const resumeResend = onSchedule(
  { schedule: "every 30 minutes", secrets: [RESEND_API_KEY] },
  async () => {
    const resumed = await resumeResendSends(liveSendDeps(resendSmtp()));
    if (resumed > 0) logger.info("Resend sends resumed", { resumed });
  },
);
