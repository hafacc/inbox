import { Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { liveAddressDeps } from "./address-deps.js";
import {
  auth,
  cloudTaskQueue,
  db,
  deletedAccountDoc,
  type PrivateDoc,
  privateDoc,
  userDoc,
} from "./admin.js";
import { clientCredentials, tokenSealer } from "./config.js";
import { keyStore } from "./key-publish.js";
import { retireAddress } from "./lib/address.js";
import type {
  AccountRecords,
  DeletionDeps,
  GoogleGrant,
} from "./lib/delete-account.js";
import { gmailApi } from "./lib/gmail-api.js";
import {
  GoogleTokenError,
  refreshAccessToken,
  revokeToken,
} from "./lib/google.js";
import { outboxFromDoc } from "./lib/outbox-task.js";
import { SEND_FUNCTION } from "./outbox-deps.js";

/** A user's docs on Firestore, as account deletion reads and deletes them. */
const firestoreRecords: AccountRecords = {
  async address(uid) {
    const address = (await userDoc(uid).get()).data()?.address;
    return typeof address === "string" ? address : undefined;
  },
  async sends(uid) {
    const found = await userDoc(uid).collection("outbox").get();
    return found.docs.map((entry) => ({
      outboxId: entry.id,
      entry: outboxFromDoc(entry.data()),
      writtenAt: entry.updateTime.toMillis(),
    }));
  },
  async markDeleted(uid, until) {
    await deletedAccountDoc(uid).set({
      expiresAt: Timestamp.fromMillis(until),
    });
  },
  async deleteAll(uid) {
    await db.recursiveDelete(userDoc(uid));
    await db.recursiveDelete(privateDoc(uid));
  },
};

/** The user's Google grant through the sealer and Google's OAuth endpoints. */
const googleGrant: GoogleGrant = {
  async refreshToken(uid) {
    const stored = (await privateDoc(uid).get()).data() as
      | PrivateDoc
      | undefined;
    return stored?.refreshToken === undefined
      ? undefined
      : tokenSealer().open(stored.refreshToken);
  },
  async accessToken(refreshToken) {
    try {
      return (await refreshAccessToken(clientCredentials(), refreshToken))
        .accessToken;
    } catch (error) {
      if (error instanceof GoogleTokenError && error.code === "invalid_grant") {
        return undefined;
      }
      throw error;
    }
  },
  async stopWatch(accessToken) {
    await gmailApi(accessToken).stop();
  },
  revoke: (refreshToken) => revokeToken(refreshToken),
};

/**
 * The production wiring of account deletion; call only inside a handler that declares the
 * `GOOGLE_CLIENT_SECRET` and `CLOUDFLARE_API_TOKEN` secrets.
 */
export function liveDeletionDeps(): DeletionDeps {
  return {
    records: firestoreRecords,
    google: googleGrant,
    retireAddress: (uid) => retireAddress(liveAddressDeps(), uid),
    keys: keyStore,
    sendQueue: cloudTaskQueue(SEND_FUNCTION),
    async deleteUser(uid) {
      try {
        await auth.deleteUser(uid);
      } catch (error) {
        if ((error as { code?: string }).code !== "auth/user-not-found") {
          throw error;
        }
      }
    },
    now: Date.now,
    log: logger,
  };
}
