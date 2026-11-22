import { logger } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onTaskDispatched } from "firebase-functions/v2/tasks";
import { signedInUid } from "./account-access.js";
import { liveAddressDeps } from "./address-deps.js";
import { CLOUDFLARE_API_TOKEN, GOOGLE_CLIENT_SECRET } from "./config.js";
import {
  AddressRefused,
  type AddressResult,
  checkAddress as checkClaim,
  claimAddress as claimName,
  formerAddress as formerAddressOf,
  handleCheckTask,
  parseCheckTask,
  type RefusalReason,
} from "./lib/address.js";

const SECRETS = [GOOGLE_CLIENT_SECRET, CLOUDFLARE_API_TOKEN];

const REFUSAL_CODE: Record<
  RefusalReason,
  "invalid-argument" | "already-exists" | "failed-precondition" | "not-found"
> = {
  length: "invalid-argument",
  characters: "invalid-argument",
  edges: "invalid-argument",
  punctuation: "invalid-argument",
  reserved: "invalid-argument",
  taken: "already-exists",
  "has-address": "already-exists",
  "had-address": "already-exists",
  "no-gmail": "failed-precondition",
  releasing: "failed-precondition",
  "not-configured": "failed-precondition",
  "no-address": "not-found",
};

async function refusalsAsErrors<Result>(
  run: () => Promise<Result>,
): Promise<Result> {
  try {
    return await run();
  } catch (error) {
    if (error instanceof AddressRefused) {
      throw new HttpsError(REFUSAL_CODE[error.reason], error.message, {
        reason: error.reason,
        ...(error.address === undefined ? {} : { address: error.address }),
      });
    }
    throw error;
  }
}

/**
 * Claims an @hafa.cc address for the signed-in user.
 *
 * Takes `{ name }`, checks it (see docs/address-contract.md), takes `addresses/{name}` in a
 * transaction, sets `users/{uid}.address`, adds the user's Gmail address as a Cloudflare Email
 * Routing destination and starts the checks that confirm it and create the forwarding rule.
 * Returns `{ address, setup }`; progress continues on `users/{uid}.addressSetup`. Calling it again
 * with the name the user holds resumes setup. An account gets one address for good, and a name
 * never passes to another account, even after its account is deleted; a deleted account that signs
 * in again may only take its old name back (`had-address` otherwise).
 */
export const claimAddress = onCall<unknown, Promise<AddressResult>>(
  { secrets: SECRETS },
  async (request) => {
    const uid = signedInUid(request);
    const name = (request.data as { name?: unknown } | null)?.name;
    return refusalsAsErrors(() => claimName(liveAddressDeps(), uid, name));
  },
);

/**
 * Answers `{ address }`: the address the signed-in user's account held before it was deleted, which
 * only it can take back, or null.
 */
export const formerAddress = onCall<
  unknown,
  Promise<{ address: string | null }>
>(async (request) => {
  const uid = signedInUid(request);
  return { address: await formerAddressOf(liveAddressDeps(), uid) };
});

/**
 * Re-checks the signed-in user's address setup now, sending a fresh confirmation mail when the last
 * one is over a day old; returns `{ address, setup }`.
 */
export const checkAddress = onCall<unknown, Promise<AddressResult>>(
  { secrets: SECRETS },
  async (request) => {
    const uid = signedInUid(request);
    return refusalsAsErrors(() => checkClaim(liveAddressDeps(), uid));
  },
);

/**
 * Checks a claim's setup: queued in a chain after claiming (15 seconds, then sparser for about a
 * day) and once right after new mail reaches a Gmail address whose claim is waiting on Cloudflare's
 * confirmation mail.
 */
export const addressCheck = onTaskDispatched(
  {
    secrets: SECRETS,
    retryConfig: { maxAttempts: 3, minBackoffSeconds: 30 },
    rateLimits: { maxConcurrentDispatches: 5 },
  },
  async (request) => {
    const task = parseCheckTask(request.data);
    if (task === undefined) {
      logger.warn("malformed address check task");
      return;
    }
    const result = await handleCheckTask(liveAddressDeps(), task);
    logger.info("address check", { result, name: task.name });
  },
);
