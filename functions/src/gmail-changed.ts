import { logger } from "firebase-functions/v2";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { checkQueue, firestoreAddresses } from "./address-deps.js";
import { GMAIL_TOPIC_NAME, GOOGLE_CLIENT_SECRET } from "./config.js";
import { liveDeps } from "./deps.js";
import { checkAfterMail } from "./lib/address.js";
import { gmailApi } from "./lib/gmail-api.js";
import { handleGmailPush, renewWatches, startWatch } from "./lib/gmail-push.js";
import { parseGmailPush } from "./lib/history.js";
import { messageOf } from "./lib/util.js";

/**
 * Handles Gmail's change feed on Pub/Sub topic `gmail`.
 *
 * Reads `history.list` from the user's stored history id and sends FCM notifications for new
 * unread inbox mail from others. When Gmail no longer has that history it looks at the last two days of inbox
 * mail instead. When mail arrived and the mailbox has a new @hafa.cc claim waiting on Cloudflare's
 * confirmation mail, it queues an immediate `addressCheck`. Retries are off: a lost push is caught
 * up by the next one.
 */
export const gmailChanged = onMessagePublished(
  { topic: GMAIL_TOPIC_NAME, secrets: [GOOGLE_CLIENT_SECRET] },
  async (event) => {
    let body: unknown;
    try {
      body = event.data.message.json;
    } catch {
      body = undefined;
    }
    const push = parseGmailPush(body);
    if (push === undefined) {
      logger.warn("malformed Gmail push");
      return;
    }
    const { result, mailArrived } = await handleGmailPush(liveDeps(), push);
    logger.info("gmail push", { result });
    if (result === "handled" && mailArrived) {
      try {
        await checkAfterMail(
          { store: firestoreAddresses, queue: checkQueue, now: Date.now },
          push.emailAddress,
        );
      } catch (error) {
        logger.warn("queuing address check failed", {
          message: messageOf(error),
        });
      }
    }
  },
);

/** Renews every user's Gmail watch daily; a watch lapses after 7 days. */
export const renewGmailWatch = onSchedule(
  {
    schedule: "every day 04:00",
    timeZone: "UTC",
    secrets: [GOOGLE_CLIENT_SECRET],
    timeoutSeconds: 540,
  },
  async () => {
    const renewed = await renewWatches(liveDeps());
    logger.info("gmail watches renewed", { renewed });
  },
);

/** Start the Gmail watch right after sign-in; a failure is logged, since the daily renewal retries. */
export async function startWatchAfterSignIn(
  uid: string,
  accessToken: string,
): Promise<void> {
  try {
    await startWatch(liveDeps(), uid, gmailApi(accessToken));
  } catch (error) {
    logger.warn("gmail watch failed", { uid, message: messageOf(error) });
  }
}
