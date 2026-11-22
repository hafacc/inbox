import { HttpsError, onCall } from "firebase-functions/v2/https";
import { GOOGLE_CLIENT_SECRET } from "./config.js";
import { liveDeps } from "./deps.js";
import { parseActionInput } from "./lib/action-token.js";
import { ActionRefused, handleNotificationAction } from "./lib/gmail-push.js";

/**
 * Runs Done tapped on a notification, from a service worker or app that has no sign-in at
 * hand. Authorized by the notification's `actionToken` rather than Firebase Auth: the token names one
 * thread and works for 14 days. Throws `not-found` for an unknown or expired token and
 * `failed-precondition` / `reauth` when Gmail access is gone.
 */
export const notificationAction = onCall<unknown, Promise<{ ok: true }>>(
  { secrets: [GOOGLE_CLIENT_SECRET] },
  async (request) => {
    const input = parseActionInput(request.data);
    if (input === undefined) {
      throw new HttpsError(
        "invalid-argument",
        "Expected { token, action: done }.",
      );
    }
    try {
      await handleNotificationAction(liveDeps(), input);
    } catch (error) {
      if (error instanceof ActionRefused) {
        throw error.reason === "reauth"
          ? new HttpsError("failed-precondition", "reauth")
          : new HttpsError("not-found", "This notification has expired.");
      }
      throw error;
    }
    return { ok: true };
  },
);
