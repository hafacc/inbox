import { HttpsError, onCall } from "firebase-functions/v2/https";
import { accessTokenFor, signedInUid } from "./account-access.js";
import { GOOGLE_CLIENT_SECRET } from "./config.js";
import type { GmailTokenResult } from "./lib/requests.js";

/**
 * Mints a Gmail access token for the signed-in user from their stored refresh token.
 *
 * Throws `failed-precondition` with message `reauth` when no refresh token is stored or Google
 * revoked it (`invalid_grant`, after which the stored token is deleted); the client should then run
 * the sign-in code flow again.
 */
export const gmailToken = onCall<unknown, Promise<GmailTokenResult>>(
  { secrets: [GOOGLE_CLIENT_SECRET] },
  async (request) => {
    const token = await accessTokenFor(signedInUid(request));
    if (token === undefined) {
      throw new HttpsError("failed-precondition", "reauth");
    } else {
      return { accessToken: token.accessToken, expiresAt: token.expiresAt };
    }
  },
);
