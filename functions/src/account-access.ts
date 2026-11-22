import { FieldValue } from "firebase-admin/firestore";
import { type CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { db, type PrivateDoc, privateDoc } from "./admin.js";
import { clientCredentials, tokenSealer } from "./config.js";
import {
  type AccessTokens,
  type AccountToken,
  accessTokens,
  type StoredGrant,
} from "./lib/access-tokens.js";
import { type GmailApi, type GmailMailbox, gmailApi } from "./lib/gmail-api.js";
import { refreshAccessToken } from "./lib/google.js";

function grantOf(data: PrivateDoc | undefined): StoredGrant | null {
  return data?.refreshToken === undefined
    ? null
    : { sealed: data.refreshToken, email: data.email ?? "" };
}

const tokens: AccessTokens = accessTokens({
  store: {
    async read(uid) {
      const data = (await privateDoc(uid).get()).data() as
        | PrivateDoc
        | undefined;
      return grantOf(data) ?? undefined;
    },
    dropIfCurrent(uid, sealed) {
      const ref = privateDoc(uid);
      return db.runTransaction(async (transaction) => {
        const data = (await transaction.get(ref)).data() as
          | PrivateDoc
          | undefined;
        if (data?.refreshToken !== sealed) {
          return grantOf(data) ?? undefined;
        } else {
          transaction.update(ref, {
            refreshToken: FieldValue.delete(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          return undefined;
        }
      });
    },
  },
  open: (sealed) => tokenSealer().open(sealed),
  refresh: (refreshToken) =>
    refreshAccessToken(clientCredentials(), refreshToken),
  now: Date.now,
});

/** Remember an access token Google just issued, so the next call on this instance reuses it. */
export function rememberAccessToken(
  uid: string,
  sealed: string,
  accessToken: string,
  expiresAt: number,
): void {
  tokens.remember(uid, sealed, accessToken, expiresAt);
}

/**
 * A Google access token for a user from their stored refresh token; undefined when none is stored
 * or Google revoked it (see `accessTokens`). Pass `stored` when `private/{uid}` was just read.
 * Callers must declare the `GOOGLE_CLIENT_SECRET` secret.
 */
export function accessTokenFor(
  uid: string,
  stored?: PrivateDoc,
): Promise<AccountToken | undefined> {
  return tokens.tokenFor(
    uid,
    stored === undefined ? undefined : grantOf(stored),
  );
}

/** A user's Gmail and address, or undefined when their grant is gone; see {@link accessTokenFor}. */
export async function gmailForUser(
  uid: string,
  stored?: PrivateDoc,
): Promise<{ gmail: GmailApi & GmailMailbox; email: string } | undefined> {
  const token = await accessTokenFor(uid, stored);
  return token === undefined
    ? undefined
    : { gmail: gmailApi(token.accessToken), email: token.email };
}

/** The caller's uid; throws `unauthenticated` when the call isn't signed in. */
export function signedInUid(request: CallableRequest<unknown>): string {
  const uid = request.auth?.uid;
  if (uid === undefined) {
    throw new HttpsError("unauthenticated", "Sign in first.");
  } else {
    return uid;
  }
}
