import { HttpsError, onCall } from "firebase-functions/v2/https";
import { signedInUid } from "./account-access.js";
import { CLOUDFLARE_API_TOKEN, GOOGLE_CLIENT_SECRET } from "./config.js";
import { liveDeletionDeps } from "./delete-account-deps.js";
import { AddressRefused } from "./lib/address.js";
import {
  type DeletionResult,
  deleteAccount as deleteEverything,
} from "./lib/delete-account.js";

/**
 * Deletes the signed-in user's account and everything the server holds for them.
 *
 * Takes no data. Cancels their queued send tasks, unpublishes their key, stops their
 * @hafa.cc address's forwarding (the name stays taken for good), stops their Gmail watch, revokes
 * their Google grant, deletes `users/{uid}` and `private/{uid}` with everything under them, and
 * deletes their Firebase Auth user. Returns
 * `{ deleted: true }`. Safe to call again after a failure; the client should sign out afterwards.
 * Mail in Gmail is untouched. Throws `failed-precondition` (with `reason`) when the address can't
 * be stopped yet.
 */
export const deleteAccount = onCall<unknown, Promise<DeletionResult>>(
  {
    secrets: [GOOGLE_CLIENT_SECRET, CLOUDFLARE_API_TOKEN],
    timeoutSeconds: 300,
  },
  async (request) => {
    const uid = signedInUid(request);
    try {
      return await deleteEverything(liveDeletionDeps(), uid);
    } catch (error) {
      if (error instanceof AddressRefused) {
        throw new HttpsError("failed-precondition", error.message, {
          reason: error.reason,
        });
      }
      throw error;
    }
  },
);
