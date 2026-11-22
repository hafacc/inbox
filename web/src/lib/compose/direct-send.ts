import { addressKey } from "@inbox/shared/compose";
import {
  DIRECT_HANDOVER_MAX_MS,
  DIRECT_SEND_TIMEOUT_MS,
  directSendFailure,
  draftSender,
  type GmailFailure,
  type HeaderLine,
} from "@inbox/shared/direct-send";
import { GmailError } from "../gmail/request";

// The rules' limit on a refusal's message.
const REFUSAL_TEXT_MAX = 2000;

/** What the device records when it claims a send now (the server's attempt record). */
export type DeviceClaim = {
  /** the draft's Gmail message id, which the sent message keeps */
  draftMessageId: string;
  /** the draft's `Message-ID` header, when it has one */
  messageIdHeader: string | null;
  /** a random id of this claim */
  deviceClaim: string;
};

/** What sending a send now from the device talks to, injectable for tests. */
export type DirectSendDeps = {
  /** the draft's Gmail message id and headers (`drafts.get`, metadata) */
  readDraft(draftId: string): Promise<{ id: string; headers: HeaderLine[] }>;
  /**
   * claim the outbox doc in a transaction, deciding again on the doc as stored
   * (`directSendDecision`); false when it isn't this device's to send, or the claim failed
   */
  claim(claim: DeviceClaim): Promise<boolean>;
  /** `drafts.send` */
  sendDraft(draftId: string): Promise<{ id: string; threadId: string }>;
  /** record the doc this device claimed under `deviceClaim` as sent */
  recordSent(
    sent: { id: string; threadId: string },
    deviceClaim: string,
  ): Promise<void>;
  /** record the doc this device claimed under `deviceClaim` as refused (`failed`, `rejected`) */
  recordRefused(message: string, deviceClaim: string): Promise<void>;
  /** a fresh claim id */
  newClaimId(): string;
  /** the clock, epoch milliseconds */
  now(): number;
};

/** How a send now from the device ended. */
export type DirectSendResult =
  /** sent and recorded */
  | "sent"
  /** Gmail refused it for good; recorded as failed */
  | "refused"
  /** not claimed: the server's task sends it as usual */
  | "server"
  /**
   * claimed, but its outcome is the server's to settle once the lease ends, unless Gmail's answer
   * comes after `DIRECT_SEND_TIMEOUT_MS` and is recorded first
   */
  | "left";

/** The status and reason of a failed Gmail call, or null when it got no answer. */
export function gmailFailureOf(error: unknown): GmailFailure {
  return error instanceof GmailError
    ? { status: error.status, reason: error.reason }
    : null;
}

// Gmail's answer to `drafts.send`, or what it failed with.
type SendAnswer =
  | { sent: { id: string; threadId: string } }
  | { error: unknown };

// Settles as null once `DIRECT_SEND_TIMEOUT_MS` passes without Gmail's answer.
function withinSendTimeout<Result>(
  pending: Promise<Result>,
): Promise<Result | null> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), DIRECT_SEND_TIMEOUT_MS);
  });
  return Promise.race([pending, timedOut]).finally(() => clearTimeout(timer));
}

// Records Gmail's answer under the claim id as the server would, however late it came.
async function recordAnswer(
  deps: DirectSendDeps,
  answer: SendAnswer,
  deviceClaim: string,
): Promise<DirectSendResult> {
  if ("sent" in answer) {
    // Queued by Firestore when offline; if the server took over meanwhile, it finds the message
    // sent and the rules refuse this write.
    await deps
      .recordSent(answer.sent, deviceClaim)
      .catch((error) => console.error("recording a send failed", error));
    return "sent";
  } else if (directSendFailure(gmailFailureOf(answer.error)) === "rejected") {
    const { error } = answer;
    await deps
      .recordRefused(
        (error instanceof Error ? error.message : String(error)).slice(
          0,
          REFUSAL_TEXT_MAX,
        ),
        deviceClaim,
      )
      .catch((recordError) =>
        console.error("recording a refused send failed", recordError),
      );
    return "refused";
  } else {
    return "left";
  }
}

/**
 * Send a send now from the device once its undo window has ended and `directSendDecision` said so:
 * read the draft's headers and check its From is the doc's, as the server does; claim the doc
 * (`sending`, with the handover recorded); hand the draft to Gmail unless the claim took longer
 * than `DIRECT_HANDOVER_MAX_MS`; then record the result under its claim id as the server would.
 * Nothing is retried: a failure other than a clean refusal leaves the doc `sending` for the
 * server's takeover, which looks the message up before sending, so it never goes twice. With no
 * answer within `DIRECT_SEND_TIMEOUT_MS`, it returns `left` but still records Gmail's answer when
 * it comes, which the rules accept only while this device's claim stands.
 */
export async function sendDirectly(
  deps: DirectSendDeps,
  send: { draftId: string; from: string },
): Promise<DirectSendResult> {
  let draft: { id: string; headers: HeaderLine[] };
  try {
    draft = await deps.readDraft(send.draftId);
  } catch {
    return "server";
  }
  if (draftSender(draft.headers) !== addressKey(send.from)) {
    return "server";
  }
  const messageIdHeader =
    draft.headers.find((header) => /^message-id$/i.test(header.name))?.value ??
    null;
  const started = deps.now();
  const deviceClaim = deps.newClaimId();
  const claimed = await deps
    .claim({ draftMessageId: draft.id, messageIdHeader, deviceClaim })
    .catch(() => false);
  if (!claimed) {
    return "server";
  } else if (deps.now() - started > DIRECT_HANDOVER_MAX_MS) {
    return "left";
  }
  const answer: Promise<SendAnswer> = deps.sendDraft(send.draftId).then(
    (sent) => ({ sent }),
    (error: unknown) => ({ error }),
  );
  const onTime = await withinSendTimeout(answer);
  if (onTime === null) {
    void answer.then((late) => recordAnswer(deps, late, deviceClaim));
    return "left";
  } else {
    return recordAnswer(deps, onTime, deviceClaim);
  }
}
