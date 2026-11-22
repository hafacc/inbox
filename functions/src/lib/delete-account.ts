import {
  type OutboxEntry,
  pendingSendTasks,
  sendTaskId,
} from "./outbox-task.js";
import { type Log, messageOf } from "./util.js";
import { KEY_DOMAIN, type KeyStore, kvKeyFor } from "./wkd-keys.js";

/** An outbox doc as account deletion reads it. */
export interface StoredSend {
  /** outbox doc id */
  outboxId: string;
  /** the entry, or undefined when the doc is malformed */
  entry: OutboxEntry | undefined;
  /** when the doc was last written, epoch milliseconds */
  writtenAt: number;
}

/** Firestore, as account deletion uses it. */
export interface AccountRecords {
  /** `users/{uid}.address`, if any. */
  address(uid: string): Promise<string | undefined>;
  /** Every `users/{uid}/outbox` doc. */
  sends(uid: string): Promise<StoredSend[]>;
  /**
   * Mark the account deleted until `until` (epoch milliseconds), so the rules refuse writes from
   * devices still signed in to it.
   */
  markDeleted(uid: string, until: number): Promise<void>;
  /** Delete `users/{uid}` and `private/{uid}` with everything under them; missing docs are fine. */
  deleteAll(uid: string): Promise<void>;
}

/** Google, as account deletion uses it. */
export interface GoogleGrant {
  /** The user's stored refresh token, unsealed, or undefined when none is stored. */
  refreshToken(uid: string): Promise<string | undefined>;
  /** A fresh access token, or undefined when Google says the grant is gone (`invalid_grant`). */
  accessToken(refreshToken: string): Promise<string | undefined>;
  /** Stop the mailbox's Gmail push notifications (`users.stop`). */
  stopWatch(accessToken: string): Promise<void>;
  /** Revoke the grant; `already-revoked` when Google no longer knows the token. */
  revoke(refreshToken: string): Promise<"revoked" | "already-revoked">;
}

/** A Cloud Tasks queue, as account deletion uses it. */
export interface TaskCanceller {
  /** Delete a queued task; a missing one counts as done. */
  remove(id: string): Promise<void>;
}

/** Everything account deletion talks to, injectable for tests. */
export interface DeletionDeps {
  /** Firestore */
  records: AccountRecords;
  /** the user's Google grant */
  google: GoogleGrant;
  /**
   * Stop the user's @hafa.cc address and keep its name taken; returns it, or undefined when they
   * held none.
   */
  retireAddress(uid: string): Promise<string | undefined>;
  /** Workers KV of published keys, or undefined when it isn't set up */
  keys(): KeyStore | undefined;
  /** the `outboxSend` queue */
  sendQueue: TaskCanceller;
  /** Delete the Firebase Auth user; a missing one counts as done. */
  deleteUser(uid: string): Promise<void>;
  /** the clock, epoch milliseconds */
  now(): number;
  /** logger */
  log: Log;
}

/**
 * How long a deleted account stays marked: well past the hour a Firebase ID token lasts, so no
 * device still signed in can write its data back.
 */
export const DELETED_MARK_MS = 24 * 60 * 60 * 1000;

/** What `deleteAccount` returns. */
export interface DeletionResult {
  /** always true; failures throw */
  deleted: true;
}

async function cancelSends(
  deps: DeletionDeps,
  uid: string,
  sends: readonly StoredSend[],
): Promise<void> {
  const now = deps.now();
  const ids = sends.flatMap(({ outboxId, entry, writtenAt }) =>
    entry?.status === "waiting"
      ? pendingSendTasks(
          uid,
          outboxId,
          { sendAt: entry.sendAt, writtenAt },
          now,
        ).map((task) => sendTaskId(task))
      : [],
  );
  for (const id of ids) {
    try {
      await deps.sendQueue.remove(id);
    } catch (error) {
      deps.log.warn("task delete failed", {
        uid,
        id,
        message: messageOf(error),
      });
    }
  }
}

async function endGrant(deps: DeletionDeps, uid: string): Promise<void> {
  const refreshToken = await deps.google.refreshToken(uid);
  if (refreshToken === undefined) return;
  const accessToken = await deps.google.accessToken(refreshToken);
  if (accessToken === undefined) {
    deps.log.info("Google grant already gone", { uid });
    return;
  }
  try {
    await deps.google.stopWatch(accessToken);
  } catch (error) {
    deps.log.warn("Gmail watch stop failed", {
      uid,
      message: messageOf(error),
    });
  }
  const revoked = await deps.google.revoke(refreshToken);
  deps.log.info("Google grant revoked", { uid, revoked });
}

/**
 * Delete everything the server holds for a user.
 *
 * In order: mark the account deleted (so the rules refuse writes from devices still signed in, and
 * no outbox doc can appear after the next step reads them), cancel the queued send tasks the
 * user's outbox docs name, remove their published key from Workers KV, stop their @hafa.cc address
 * (forwarding rule and destination; the name stays taken for good), stop their Gmail watch and
 * revoke their Google grant, delete `users/{uid}` and `private/{uid}` recursively, and delete the
 * Firebase Auth user. What a step needs is read before anything it depends on is deleted. Each
 * step treats "already gone" as done, so a call that failed partway can simply be made again; until
 * then, or until the user signs in again, the account stays closed to writes.
 */
export async function deleteAccount(
  deps: DeletionDeps,
  uid: string,
): Promise<DeletionResult> {
  await deps.records.markDeleted(uid, deps.now() + DELETED_MARK_MS);
  const [address, sends] = await Promise.all([
    deps.records.address(uid),
    deps.records.sends(uid),
  ]);
  await cancelSends(deps, uid, sends);
  const keys = deps.keys();
  if (
    keys !== undefined &&
    address?.toLowerCase().endsWith(`@${KEY_DOMAIN}`) === true
  ) {
    await keys.delete(await kvKeyFor(address.toLowerCase()));
  }
  await deps.retireAddress(uid);
  await endGrant(deps, uid);
  await deps.records.deleteAll(uid);
  await deps.deleteUser(uid);
  deps.log.info("account deleted", { uid });
  return { deleted: true };
}
