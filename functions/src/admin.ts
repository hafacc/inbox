import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, type Timestamp } from "firebase-admin/firestore";
import { getFunctions } from "firebase-admin/functions";
import { REGION } from "./config.js";

initializeApp();

/** Firestore through the Admin SDK (bypasses security rules). */
export const db = getFirestore();

/** Firebase Auth through the Admin SDK. */
export const auth = getAuth();

/** Stored Google grant at `private/{uid}`; function-only. */
export interface PrivateDoc {
  /** sealed refresh token; absent after Google revokes it */
  refreshToken?: string;
  /** Google account email, lowercased */
  email?: string;
  /** scopes granted at the last sign-in */
  scopes?: string[];
  /** history id `gmailChanged` has handled up to */
  historyId?: string;
  /** when the Gmail watch lapses */
  watchExpiry?: Timestamp;
  /** when `gmailChanged` last finished */
  lastSyncAt?: Timestamp;
}

/** Reference to a user's private doc. */
export function privateDoc(uid: string) {
  return db.collection("private").doc(uid);
}

/**
 * Reference to the mark a deleted account leaves (`deletedAccounts/{uid}: { expiresAt }`, removed
 * by a TTL policy), which the rules check before every client write; function-only.
 */
export function deletedAccountDoc(uid: string) {
  return db.collection("deletedAccounts").doc(uid);
}

/** Reference to a user's client-visible doc. */
export function userDoc(uid: string) {
  return db.collection("users").doc(uid);
}

/** A task function's Cloud Tasks queue. */
export interface CloudTaskQueue<Task> {
  /** Queue a task to fire at `at`; a task with the same id already queued counts as done. */
  enqueue(task: Task, id: string, at: Date): Promise<void>;
  /** Delete a queued task; a missing one counts as done. */
  remove(id: string): Promise<void>;
}

/** The Cloud Tasks queue of the task function `functionName`, through the Admin SDK. */
export function cloudTaskQueue<Task>(
  functionName: string,
): CloudTaskQueue<Task> {
  const queue = () =>
    getFunctions().taskQueue<Task>(
      `locations/${REGION}/functions/${functionName}`,
    );
  return {
    async enqueue(task, id, at) {
      try {
        await queue().enqueue(task, { id, scheduleTime: at });
      } catch (error) {
        if (
          (error as { code?: string }).code !== "functions/task-already-exists"
        ) {
          throw error;
        }
      }
    },
    async remove(id) {
      try {
        await queue().delete(id);
      } catch (error) {
        if ((error as { code?: string }).code !== "functions/not-found") {
          throw error;
        }
      }
    },
  };
}
