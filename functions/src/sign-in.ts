import { FieldValue } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { OAuth2Client } from "google-auth-library";
import { rememberAccessToken } from "./account-access.js";
import {
  auth,
  deletedAccountDoc,
  type PrivateDoc,
  privateDoc,
  userDoc,
} from "./admin.js";
import {
  clientCredentials,
  GOOGLE_CLIENT_SECRET,
  tokenSealer,
} from "./config.js";
import { startWatchAfterSignIn } from "./gmail-changed.js";
import {
  exchangeCode,
  GMAIL_MODIFY_SCOPE,
  GoogleTokenError,
  hasScopes,
} from "./lib/google.js";
import {
  type GoogleIdentity,
  identityFromPayload,
  uidForGoogleSub,
} from "./lib/identity.js";
import { resumeBlocked, resumeFiling } from "./lib/outbox.js";
import { parseSignInInput, type SignInResult } from "./lib/requests.js";
import { messageOf } from "./lib/util.js";
import { liveSendDeps } from "./outbox-deps.js";

const idTokenVerifier = new OAuth2Client();

async function upsertAuthUser(
  uid: string,
  identity: GoogleIdentity,
): Promise<void> {
  const profile = {
    email: identity.email,
    emailVerified: true,
    displayName: identity.displayName ?? null,
    photoURL: identity.photoUrl ?? null,
  };
  try {
    await auth.updateUser(uid, profile);
  } catch (error) {
    if ((error as { code?: unknown }).code === "auth/user-not-found") {
      await auth.createUser({
        uid,
        email: profile.email,
        emailVerified: true,
        displayName: identity.displayName,
        photoURL: identity.photoUrl,
      });
    } else {
      throw error;
    }
  }
}

async function saveProfile(
  uid: string,
  identity: GoogleIdentity,
): Promise<void> {
  await userDoc(uid).set(
    {
      email: identity.email,
      displayName: identity.displayName ?? null,
      photoUrl: identity.photoUrl ?? null,
    },
    { merge: true },
  );
}

/**
 * Signs in with a Google authorization code.
 *
 * Exchanges the code from the Google Identity Services code client for tokens, verifies the id
 * token, requires the `gmail.modify` grant, creates or updates the Firebase user `google:<sub>`,
 * stores the KMS-sealed refresh token in `private/{uid}`, resumes the user's sends that were
 * blocked waiting for sign-in, files Sent copies still waiting for Google access, and returns a custom token for
 * `signInWithCustomToken`. Throws `failed-precondition` with message `reauth` when Google returned
 * no refresh token and none is stored, so the client must ask for consent again.
 */
export const signIn = onCall<unknown, Promise<SignInResult>>(
  { secrets: [GOOGLE_CLIENT_SECRET] },
  async (request) => {
    const input = parseSignInInput(request.data);
    if (input === undefined) {
      throw new HttpsError(
        "invalid-argument",
        "Expected { code, redirectUri }.",
      );
    }
    const credentials = clientCredentials();

    let tokens: Awaited<ReturnType<typeof exchangeCode>>;
    const requestedAt = Date.now();
    try {
      tokens = await exchangeCode(credentials, input.code, input.redirectUri);
    } catch (error) {
      if (error instanceof GoogleTokenError) {
        logger.warn("code exchange failed", { code: error.code });
        throw new HttpsError(
          "invalid-argument",
          "Google rejected the authorization code.",
        );
      }
      throw error;
    }
    if (!hasScopes(tokens.scopes, [GMAIL_MODIFY_SCOPE])) {
      throw new HttpsError(
        "permission-denied",
        "Gmail access was not granted.",
      );
    }
    if (tokens.idToken === undefined) {
      throw new HttpsError(
        "invalid-argument",
        "Google returned no id token; request the openid scope.",
      );
    }

    let identity: GoogleIdentity;
    try {
      const ticket = await idTokenVerifier.verifyIdToken({
        idToken: tokens.idToken,
        audience: credentials.clientId,
      });
      identity = identityFromPayload(ticket.getPayload());
    } catch (error) {
      logger.warn("id token rejected", { message: messageOf(error) });
      throw new HttpsError("unauthenticated", "Google id token was not valid.");
    }

    const uid = uidForGoogleSub(identity.sub);
    const privateRef = privateDoc(uid);
    const stored = (await privateRef.get()).data() as PrivateDoc | undefined;
    const sealedRefreshToken =
      tokens.refreshToken === undefined
        ? stored?.refreshToken
        : await tokenSealer().seal(tokens.refreshToken);
    if (sealedRefreshToken === undefined) {
      throw new HttpsError("failed-precondition", "reauth");
    }

    await upsertAuthUser(uid, identity);
    // A deleted account signing in again may write its data once more.
    await deletedAccountDoc(uid).delete();
    await privateRef.set(
      {
        refreshToken: sealedRefreshToken,
        // Lowercased: Gmail pushes name the mailbox in lower case, and accounts are found by it.
        email: identity.email.toLowerCase(),
        scopes: tokens.scopes,
        updatedAt: FieldValue.serverTimestamp(),
        // Mail from while no grant was stored sends no notifications: history starts over at the
        // next push or watch.
        ...(stored?.refreshToken === undefined
          ? { historyId: FieldValue.delete() }
          : {}),
      },
      { merge: true },
    );
    rememberAccessToken(
      uid,
      sealedRefreshToken,
      tokens.accessToken,
      requestedAt + tokens.expiresIn * 1000,
    );
    const resumeSends = async () => {
      try {
        const deps = liveSendDeps();
        await Promise.all([
          resumeBlocked(deps, "sign-in", uid),
          resumeFiling(deps, uid),
        ]);
      } catch (error) {
        logger.warn("resuming sends after sign-in failed", {
          uid,
          message: messageOf(error),
        });
      }
    };
    const [customToken] = await Promise.all([
      auth.createCustomToken(uid),
      startWatchAfterSignIn(uid, tokens.accessToken),
      resumeSends(),
      saveProfile(uid, identity),
    ]);
    return { customToken, email: identity.email };
  },
);
