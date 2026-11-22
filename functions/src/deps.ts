import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { logger } from "firebase-functions/v2";
import { gmailForUser } from "./account-access.js";
import { db, type PrivateDoc, privateDoc, userDoc } from "./admin.js";
import { gmailTopic } from "./config.js";
import type {
  Account,
  Deps,
  Messenger,
  SendResult,
  Store,
  StoredAction,
} from "./lib/gmail-push.js";
import { fcmMessage } from "./lib/notification.js";

function accountFrom(uid: string, data: PrivateDoc): Account {
  return {
    uid,
    email: data.email ?? "",
    historyId: data.historyId,
    lastSyncAt: data.lastSyncAt?.toMillis(),
  };
}

function threadDoc(uid: string, threadId: string) {
  return userDoc(uid).collection("threads").doc(threadId);
}

function actionDoc(uid: string, nonce: string) {
  return privateDoc(uid).collection("actions").doc(nonce);
}

/**
 * The Gmail push and notification store on Firestore. Each `private/{uid}` it reads goes into `seen`, so Gmail access
 * in the same run needn't read it again.
 */
function firestoreStore(seen: Map<string, PrivateDoc>): Store {
  const account = (uid: string, data: PrivateDoc): Account => {
    seen.set(uid, data);
    return accountFrom(uid, data);
  };
  return {
    async accountByEmail(email) {
      const found = await db
        .collection("private")
        .where("email", "==", email)
        .limit(1)
        .get();
      const entry = found.docs[0];
      return entry === undefined
        ? undefined
        : account(entry.id, entry.data() as PrivateDoc);
    },
    async account(uid) {
      const snapshot = await privateDoc(uid).get();
      return snapshot.exists
        ? account(uid, snapshot.data() as PrivateDoc)
        : undefined;
    },
    async accountsWithGrant() {
      const found = await db
        .collection("private")
        .where("refreshToken", "!=", null)
        .get();
      return found.docs.map((entry) =>
        account(entry.id, entry.data() as PrivateDoc),
      );
    },
    async saveWatch(uid, historyId, expiresAt) {
      const ref = privateDoc(uid);
      await db.runTransaction(async (transaction) => {
        const stored = (await transaction.get(ref)).data() as
          | PrivateDoc
          | undefined;
        // Gone with the account (deleted while the watch call was out): don't write it back.
        if (stored === undefined) return;
        transaction.set(
          ref,
          {
            watchExpiry: Timestamp.fromMillis(expiresAt),
            ...(stored.historyId === undefined ? { historyId } : {}),
          },
          { merge: true },
        );
      });
    },
    async claimHistory(uid, from, to, syncedAt) {
      const ref = privateDoc(uid);
      return db.runTransaction(async (transaction) => {
        const stored = (await transaction.get(ref)).data() as
          | PrivateDoc
          | undefined;
        if (stored === undefined || stored.historyId !== from) return false;
        transaction.set(
          ref,
          { historyId: to, lastSyncAt: Timestamp.fromMillis(syncedAt) },
          { merge: true },
        );
        return true;
      });
    },
    async clearSnooze(uid, threadId) {
      await threadDoc(uid, threadId).delete();
    },
    async notifyPrefs(uid) {
      const settings = (await userDoc(uid).get()).get("settings") as
        | { quietBundles?: unknown; showTodos?: unknown }
        | undefined;
      const showTodos = settings?.showTodos !== false;
      if (settings?.quietBundles !== true) {
        return { quietBundles: false, bundleOff: [], showTodos };
      } else {
        const off = await userDoc(uid)
          .collection("labelPrefs")
          .where("bundle", "==", false)
          .get();
        return {
          quietBundles: true,
          bundleOff: off.docs.map((entry) => entry.id),
          showTodos,
        };
      }
    },
    async emailTodoText(uid, threadId) {
      // An email's todo is stored under its thread's id.
      const data = (
        await userDoc(uid).collection("todos").doc(threadId).get()
      ).data();
      return data !== undefined &&
        data.threadId === threadId &&
        data.deleted !== true &&
        typeof data.text === "string"
        ? data.text
        : null;
    },
    async deviceTokens(uid) {
      const found = await userDoc(uid).collection("devices").get();
      return found.docs.map((entry) => entry.id);
    },
    async androidTokens(uid) {
      const found = await userDoc(uid)
        .collection("devices")
        .where("platform", "==", "android")
        .get();
      return found.docs.map((entry) => entry.id);
    },
    async removeDevices(uid, tokens) {
      if (tokens.length === 0) return;
      const batch = db.batch();
      for (const token of tokens) {
        batch.delete(userDoc(uid).collection("devices").doc(token));
      }
      // Their snooze waits go too, merged so a missing doc is no error.
      batch.set(
        userDoc(uid).collection("meta").doc("snoozeWaits"),
        {
          waits: Object.fromEntries(
            tokens.map((token) => [token, FieldValue.delete()]),
          ),
        },
        { merge: true },
      );
      await batch.commit();
    },
    async saveAction(uid, nonce, action) {
      return db.runTransaction(async (transaction) => {
        if (!(await transaction.get(privateDoc(uid))).exists) return false;
        transaction.set(actionDoc(uid, nonce), {
          threadId: action.threadId,
          expiresAt: Timestamp.fromMillis(action.expiresAt),
        });
        return true;
      });
    },
    async action(uid, nonce) {
      const data = (await actionDoc(uid, nonce).get()).data() as
        | { threadId?: string; expiresAt?: Timestamp }
        | undefined;
      if (data?.threadId === undefined || data.expiresAt === undefined) {
        return undefined;
      } else {
        return {
          threadId: data.threadId,
          expiresAt: data.expiresAt.toMillis(),
        } satisfies StoredAction;
      }
    },
    async deleteAction(uid, nonce) {
      await actionDoc(uid, nonce).delete();
    },
  };
}

/**
 * Send FCM data to several devices through the Admin SDK, kept for a day while a device is
 * offline: high priority unless told otherwise. Returns a result per token, in order.
 */
export async function sendFcm(
  tokens: string[],
  data: Record<string, string>,
  priority: "high" | "normal" = "high",
): Promise<SendResult[]> {
  const response = await getMessaging().sendEachForMulticast(
    fcmMessage(tokens, data, priority),
  );
  return response.responses.map((result) => ({
    success: result.success,
    ...(result.error === undefined
      ? {}
      : { error: { code: result.error.code } }),
  }));
}

const fcmMessenger: Messenger = {
  // A clear shows nothing, and FCM demotes apps whose high-priority pushes show nothing.
  send: (tokens, data) =>
    sendFcm(tokens, { ...data }, data.kind === "clear" ? "normal" : "high"),
};

/** The production wiring of the Gmail push and notification handlers; call only inside a handler. */
export function liveDeps(): Deps {
  const seen = new Map<string, PrivateDoc>();
  return {
    store: firestoreStore(seen),
    gmailFor: (uid) => gmailForUser(uid, seen.get(uid)),
    messenger: fcmMessenger,
    topic: gmailTopic(),
    now: Date.now,
    log: logger,
  };
}
