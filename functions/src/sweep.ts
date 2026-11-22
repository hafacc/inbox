import { logger } from "firebase-functions/v2";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { liveAddressDeps } from "./address-deps.js";
import { CLOUDFLARE_API_TOKEN, GOOGLE_CLIENT_SECRET } from "./config.js";
import { finishStuckReleases, resumeUnfinishedSetups } from "./lib/address.js";
import { requeueOverdueSends, resumeFiling } from "./lib/outbox.js";
import { liveSendDeps } from "./outbox-deps.js";

/**
 * Every 30 minutes, catches the work whose Cloud Task never got queued or gave up: queues sends
 * still `waiting` (or `retrying`) well past their time, files Sent copies of Resend mail still kept, takes @hafa.cc setups whose
 * check chain ran out as far as they go, and settles @hafa.cc names an account deletion left half
 * released. While nothing is stuck it costs six empty queries.
 */
export const sweepStuck = onSchedule(
  {
    schedule: "every 30 minutes",
    secrets: [GOOGLE_CLIENT_SECRET, CLOUDFLARE_API_TOKEN],
    timeoutSeconds: 300,
  },
  async () => {
    const sendDeps = liveSendDeps();
    const addressDeps = liveAddressDeps();
    const [sends, filed, addresses, released] = await Promise.all([
      requeueOverdueSends(sendDeps),
      resumeFiling(sendDeps),
      resumeUnfinishedSetups(addressDeps),
      finishStuckReleases(addressDeps),
    ]);
    if (sends + filed + addresses + released > 0) {
      logger.info("sweep", { sends, filed, addresses, released });
    }
  },
);
