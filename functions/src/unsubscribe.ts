import {
  type FunctionsErrorCode,
  HttpsError,
  onCall,
} from "firebase-functions/v2/https";
import { gmailForUser, signedInUid } from "./account-access.js";
import { GOOGLE_CLIENT_SECRET } from "./config.js";
import {
  httpsOneClickPost,
  parseUnsubscribeInput,
  unsubscribe as run,
  UnsubscribeRefused,
  type UnsubscribeResult,
  unsubscribeHeaders,
} from "./lib/unsubscribe.js";

const REFUSALS: Record<
  UnsubscribeRefused["reason"],
  [FunctionsErrorCode, string]
> = {
  "not-found": ["not-found", "No such message."],
  reauth: ["failed-precondition", "reauth"],
  "not-https": ["invalid-argument", "Only https URLs."],
  "not-one-click": [
    "failed-precondition",
    "The message doesn't offer one-click unsubscribe.",
  ],
  "not-listed": [
    "permission-denied",
    "The URL isn't in the message's List-Unsubscribe header.",
  ],
};

/**
 * Sends an RFC 8058 one-click unsubscribe for one of the user's messages.
 *
 * Takes `{ messageId, url, post }`: the Gmail message id, the https URL from its `List-Unsubscribe`
 * and its `List-Unsubscribe-Post` value. Reads the message from Gmail to check it really carries
 * both, then POSTs `List-Unsubscribe=One-Click` to the URL and returns `{ ok, status }`. See
 * docs/images-contract.md for the errors.
 */
export const unsubscribe = onCall<unknown, Promise<UnsubscribeResult>>(
  { secrets: [GOOGLE_CLIENT_SECRET], timeoutSeconds: 60 },
  async (request) => {
    const uid = signedInUid(request);
    const input = parseUnsubscribeInput(request.data);
    if (input === undefined) {
      throw new HttpsError(
        "invalid-argument",
        "Expected { messageId, url, post }.",
      );
    }
    try {
      return await run(
        {
          async headers(uid, messageId) {
            const access = await gmailForUser(uid);
            if (access === undefined) throw new UnsubscribeRefused("reauth");
            return unsubscribeHeaders(access.gmail, messageId);
          },
          post: httpsOneClickPost,
        },
        uid,
        input,
      );
    } catch (error) {
      if (error instanceof UnsubscribeRefused) {
        const [code, message] = REFUSALS[error.reason];
        throw new HttpsError(code, message);
      }
      throw error;
    }
  },
);
