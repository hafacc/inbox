import { createHash } from "node:crypto";
import {
  FieldValue,
  type QueryDocumentSnapshot,
  Timestamp,
} from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { gmailForUser } from "./account-access.js";
import { activeAddressHolder } from "./address-deps.js";
import { cloudTaskQueue, db, privateDoc, userDoc } from "./admin.js";
import { RESEND_API_KEY, RESEND_DOMAIN, RESEND_SMTP } from "./config.js";
import {
  CLAIM_LEASE_MS,
  decideClaim,
  FILING_LEASE_MS,
  type OutboxRef,
  type OutboxStore,
  type OverdueSend,
  type SendDeps,
  type SmtpSender,
  SWEEP_BATCH,
  SWEEP_MARGIN_MS,
  smtpErrorOf,
  type UnfiledCopy,
} from "./lib/outbox.js";
import { outboxFromDoc, type SendTask } from "./lib/outbox-task.js";
import { millisOf } from "./lib/util.js";

/** Name of the send task function, which is also its queue's name. */
export const SEND_FUNCTION = "outboxSend";

/** Most blocked sends resumed in one go; a later sign-in or check picks up the rest. */
const BLOCKED_BATCH = 200;

/** Firestore error code for a missing document. */
const NOT_FOUND = 5;

function outboxDoc(uid: string, outboxId: string) {
  return userDoc(uid).collection("outbox").doc(outboxId);
}

/** Collection of kept Sent copies, under `private/{uid}` where clients can't read. */
const SENT_COPIES = "sentCopies";

/** Bytes per chunk doc of a kept copy; a Firestore doc holds at most 1 MiB. */
const COPY_CHUNK_BYTES = 900 * 1024;

/** Chunk docs per batch; a commit carries at most 10 MiB. */
const CHUNKS_PER_BATCH = 9;

function sentCopyDoc(uid: string, outboxId: string) {
  return privateDoc(uid).collection(SENT_COPIES).doc(outboxId);
}

/** Collection of drafts' Resend handovers, under `private/{uid}`. */
const DRAFT_HANDOVERS = "resendHandovers";

/** How long a draft's handover is kept: Resend forgets a key after a day, so it is useless after. */
const HANDOVER_TTL_MS = 24 * 60 * 60 * 1000;

// Draft ids come from Gmail; hashing keeps them a valid doc id whatever they hold.
function draftHandoverDoc(uid: string, draftId: string) {
  const id = createHash("sha256").update(draftId).digest("hex");
  return privateDoc(uid).collection(DRAFT_HANDOVERS).doc(id);
}

function chunkId(index: number): string {
  return String(index).padStart(4, "0");
}

function copiesOf(docs: QueryDocumentSnapshot[]): UnfiledCopy[] {
  return refsOf(docs).map((ref, index) => ({
    ...ref,
    savedAt: millisOf(docs[index]?.get("savedAt")) ?? 0,
  }));
}

function refsOf(docs: QueryDocumentSnapshot[]): OutboxRef[] {
  return docs.flatMap((doc) => {
    const owner = doc.ref.parent.parent?.id;
    return owner === undefined ? [] : [{ uid: owner, outboxId: doc.id }];
  });
}

async function updateIfPresent(
  uid: string,
  outboxId: string,
  fields: Record<string, unknown>,
): Promise<boolean> {
  try {
    await outboxDoc(uid, outboxId).update(fields);
    return true;
  } catch (error) {
    if ((error as { code?: number }).code !== NOT_FOUND) throw error;
    return false;
  }
}

function timestampOrDelete(millis: number | null) {
  return millis === null ? FieldValue.delete() : Timestamp.fromMillis(millis);
}

/** The outbox on Firestore. */
const firestoreOutbox: OutboxStore = {
  async entry(uid, outboxId) {
    return outboxFromDoc((await outboxDoc(uid, outboxId).get()).data());
  },
  async claim(uid, outboxId, sendAt, now) {
    const ref = outboxDoc(uid, outboxId);
    return db.runTransaction(async (transaction) => {
      const entry = outboxFromDoc((await transaction.get(ref)).data());
      const claim = decideClaim(entry, sendAt, now);
      if (claim.kind === "claimed" || claim.kind === "expired") {
        transaction.update(ref, {
          status: "sending",
          claimedAt: Timestamp.fromMillis(now),
          firstAttemptAt: Timestamp.fromMillis(
            claim.entry.firstAttemptAt ?? now,
          ),
          nextAttemptAt: FieldValue.delete(),
          // The claim is the server's now, so a device that claimed it before can't record a result.
          deviceClaim: FieldValue.delete(),
          ...(claim.kind === "expired"
            ? { attempts: claim.entry.attempts }
            : {}),
        });
      }
      return claim;
    });
  },
  async markAttempt(uid, outboxId, record) {
    return updateIfPresent(uid, outboxId, {
      via: record.via,
      inFlightAt: Timestamp.fromMillis(record.inFlightAt),
      draftMessageId: record.draftMessageId ?? FieldValue.delete(),
      messageIdHeader: record.messageIdHeader ?? FieldValue.delete(),
      messageDate: timestampOrDelete(record.messageDate),
      idempotencyKey: record.idempotencyKey ?? FieldValue.delete(),
      uncertainSince: timestampOrDelete(record.uncertainSince),
    });
  },
  async markRetrying(uid, outboxId, record) {
    const ref = outboxDoc(uid, outboxId);
    return db.runTransaction(async (transaction) => {
      const entry = outboxFromDoc((await transaction.get(ref)).data());
      if (entry?.status !== "sending") return false;
      transaction.update(ref, {
        status: "retrying",
        via: record.via,
        attempts: record.attempts,
        nextAttemptAt: Timestamp.fromMillis(record.nextAttemptAt),
        lastError: {
          code: record.reason,
          message: record.message,
          at: FieldValue.serverTimestamp(),
        },
        uncertainSince: timestampOrDelete(record.uncertainSince),
        messageDate: timestampOrDelete(record.messageDate),
        idempotencyKey: record.idempotencyKey ?? FieldValue.delete(),
        claimedAt: FieldValue.delete(),
        inFlightAt: FieldValue.delete(),
      });
      return true;
    });
  },
  async markBlocked(uid, outboxId, record) {
    const ref = outboxDoc(uid, outboxId);
    return db.runTransaction(async (transaction) => {
      const entry = outboxFromDoc((await transaction.get(ref)).data());
      if (entry?.status !== "sending") return false;
      transaction.update(ref, {
        status: "blocked",
        blockedOn: record.on,
        via: record.via,
        lastError: {
          code: record.on,
          message: record.message,
          at: FieldValue.serverTimestamp(),
        },
        uncertainSince: timestampOrDelete(record.uncertainSince),
        messageDate: timestampOrDelete(record.messageDate),
        idempotencyKey: record.idempotencyKey ?? FieldValue.delete(),
        claimedAt: FieldValue.delete(),
        inFlightAt: FieldValue.delete(),
        nextAttemptAt: FieldValue.delete(),
      });
      return true;
    });
  },
  async blockedSends(on, uid) {
    const query = (
      uid === undefined
        ? db.collectionGroup("outbox")
        : userDoc(uid).collection("outbox")
    ).where("blockedOn", "==", on);
    return refsOf((await query.limit(BLOCKED_BATCH).get()).docs);
  },
  async unblock(uid, outboxId, on) {
    const ref = outboxDoc(uid, outboxId);
    return db.runTransaction(async (transaction) => {
      const entry = outboxFromDoc((await transaction.get(ref)).data());
      if (entry?.status !== "blocked" || entry.blockedOn !== on) return false;
      transaction.update(ref, {
        status: "waiting",
        attempts: 0,
        blockedOn: FieldValue.delete(),
        lastError: FieldValue.delete(),
        nextAttemptAt: FieldValue.delete(),
      });
      return true;
    });
  },
  async restoreAttempt(uid, outboxId, draftId, carried) {
    const ref = outboxDoc(uid, outboxId);
    return db.runTransaction(async (transaction) => {
      const entry = outboxFromDoc((await transaction.get(ref)).data());
      if (
        entry?.status !== "waiting" ||
        entry.draftId !== draftId ||
        entry.idempotencyKey !== null ||
        entry.uncertainSince !== null
      ) {
        return false;
      }
      transaction.update(ref, {
        ...(carried.via === null ? {} : { via: carried.via }),
        ...(carried.draftMessageId === null
          ? {}
          : { draftMessageId: carried.draftMessageId }),
        ...(carried.messageIdHeader === null
          ? {}
          : { messageIdHeader: carried.messageIdHeader }),
        ...(carried.uncertainSince === null
          ? {}
          : { uncertainSince: Timestamp.fromMillis(carried.uncertainSince) }),
        ...(carried.messageDate === null
          ? {}
          : { messageDate: Timestamp.fromMillis(carried.messageDate) }),
        ...(carried.idempotencyKey === null
          ? {}
          : { idempotencyKey: carried.idempotencyKey }),
      });
      return true;
    });
  },
  async markSent(uid, outboxId, record) {
    await updateIfPresent(uid, outboxId, {
      status: "sent",
      via: record.via,
      sentAt: Timestamp.fromMillis(record.sentAt),
      messageId: record.messageId,
      ...(record.threadId === null ? {} : { threadId: record.threadId }),
      messageIdHeader: record.messageIdHeader,
      filed: record.filed,
      rejected: record.rejected,
      error: FieldValue.delete(),
      inFlightAt: FieldValue.delete(),
      nextAttemptAt: FieldValue.delete(),
    });
  },
  async markFiled(uid, outboxId, messageId, threadId) {
    await updateIfPresent(uid, outboxId, {
      filed: true,
      ...(messageId === null ? {} : { messageId }),
      ...(threadId === null ? {} : { threadId }),
    });
  },
  async markFailed(uid, outboxId, via, code, message, resend) {
    await updateIfPresent(uid, outboxId, {
      ...(resend === undefined
        ? {}
        : {
            uncertainSince: timestampOrDelete(resend.uncertainSince),
            messageDate: timestampOrDelete(resend.messageDate),
            idempotencyKey: resend.idempotencyKey ?? FieldValue.delete(),
          }),
      status: "failed",
      via,
      error: { code, message },
      failedAt: FieldValue.serverTimestamp(),
      inFlightAt: FieldValue.delete(),
      nextAttemptAt: FieldValue.delete(),
    });
  },
  activeAddressOwner: activeAddressHolder,
  async saveSentCopy(uid, outboxId, copy) {
    const ref = sentCopyDoc(uid, outboxId);
    const chunks: Buffer[] = [];
    for (let start = 0; start < copy.raw.length; start += COPY_CHUNK_BYTES) {
      chunks.push(copy.raw.subarray(start, start + COPY_CHUNK_BYTES));
    }
    for (let first = 0; first < chunks.length; first += CHUNKS_PER_BATCH) {
      const batch = db.batch();
      chunks.slice(first, first + CHUNKS_PER_BATCH).forEach((data, offset) => {
        batch.set(ref.collection("chunks").doc(chunkId(first + offset)), {
          data,
        });
      });
      await batch.commit();
    }
    // Written last, so a copy whose doc exists is whole; and only while its send's doc exists, so
    // nothing is kept for an account deleted meanwhile.
    const kept = await db.runTransaction(async (transaction) => {
      if (!(await transaction.get(outboxDoc(uid, outboxId))).exists) {
        return false;
      }
      transaction.set(ref, {
        draftId: copy.draftId,
        threadId: copy.threadId,
        messageIdHeader: copy.messageIdHeader,
        chunks: chunks.length,
        savedAt: Timestamp.fromMillis(copy.savedAt),
        nextAttemptAt: Timestamp.fromMillis(copy.savedAt + SWEEP_MARGIN_MS),
      });
      return true;
    });
    if (!kept) await db.recursiveDelete(ref);
    return kept;
  },
  async claimSentCopy(uid, outboxId, now) {
    const ref = sentCopyDoc(uid, outboxId);
    const claimed = await db.runTransaction(async (transaction) => {
      const data = (await transaction.get(ref)).data();
      if (data === undefined) return undefined;
      const filingAt = millisOf(data.filingAt);
      if (filingAt !== undefined && now - filingAt < FILING_LEASE_MS) {
        return "busy" as const;
      }
      transaction.update(ref, { filingAt: Timestamp.fromMillis(now) });
      return data;
    });
    if (claimed === undefined || claimed === "busy") return claimed;
    const { draftId, threadId, messageIdHeader, chunks } = claimed;
    const savedAt = millisOf(claimed.savedAt);
    if (
      typeof draftId !== "string" ||
      typeof messageIdHeader !== "string" ||
      typeof chunks !== "number" ||
      savedAt === undefined
    ) {
      return undefined;
    }
    const parts = await ref.collection("chunks").orderBy("__name__").get();
    if (parts.size !== chunks) {
      throw new Error(`kept copy has ${parts.size} of ${chunks} chunks`);
    }
    return {
      raw: Buffer.concat(
        parts.docs.map((part) => Buffer.from(part.get("data") as Uint8Array)),
      ),
      draftId,
      threadId: typeof threadId === "string" ? threadId : null,
      messageIdHeader,
      savedAt,
    };
  },
  async deleteSentCopy(uid, outboxId) {
    await db.recursiveDelete(sentCopyDoc(uid, outboxId));
  },
  async unfiledCopies(before, uid) {
    const cutoff = Timestamp.fromMillis(before);
    const query =
      uid === undefined
        ? db
            .collectionGroup(SENT_COPIES)
            .where("nextAttemptAt", "<", cutoff)
            .orderBy("nextAttemptAt")
        : privateDoc(uid).collection(SENT_COPIES).where("savedAt", "<", cutoff);
    return copiesOf((await query.limit(SWEEP_BATCH).get()).docs);
  },
  async deferSentCopy(uid, outboxId, until) {
    try {
      await sentCopyDoc(uid, outboxId).update({
        nextAttemptAt: Timestamp.fromMillis(until),
      });
    } catch (error) {
      if ((error as { code?: number }).code !== NOT_FOUND) throw error;
    }
  },
  async accountExists(uid) {
    return (await privateDoc(uid).get()).exists;
  },
  async draftHandover(uid, draftId) {
    const data = (await draftHandoverDoc(uid, draftId).get()).data();
    const messageDate = millisOf(data?.messageDate);
    const startedAt = millisOf(data?.startedAt);
    if (
      typeof data?.idempotencyKey !== "string" ||
      typeof data.messageIdHeader !== "string" ||
      messageDate === undefined ||
      startedAt === undefined
    ) {
      return undefined;
    }
    return {
      idempotencyKey: data.idempotencyKey,
      messageIdHeader: data.messageIdHeader,
      messageDate,
      startedAt,
    };
  },
  async keepDraftHandover(uid, outboxId, draftId, handover) {
    return db.runTransaction(async (transaction) => {
      if (!(await transaction.get(outboxDoc(uid, outboxId))).exists) {
        return false;
      }
      transaction.set(draftHandoverDoc(uid, draftId), {
        idempotencyKey: handover.idempotencyKey,
        messageIdHeader: handover.messageIdHeader,
        messageDate: Timestamp.fromMillis(handover.messageDate),
        startedAt: Timestamp.fromMillis(handover.startedAt),
        expiresAt: Timestamp.fromMillis(handover.startedAt + HANDOVER_TTL_MS),
      });
      return true;
    });
  },
  async dropDraftHandover(uid, draftId, idempotencyKey) {
    const ref = draftHandoverDoc(uid, draftId);
    await db.runTransaction(async (transaction) => {
      const stored = (await transaction.get(ref)).data();
      if (
        stored === undefined ||
        (idempotencyKey !== undefined &&
          stored.idempotencyKey !== idempotencyKey)
      ) {
        return;
      }
      transaction.delete(ref);
    });
  },
  async overdueSends(dueBefore) {
    const cutoff = Timestamp.fromMillis(dueBefore);
    const [waiting, retrying, sending] = await Promise.all([
      db
        .collectionGroup("outbox")
        .where("status", "==", "waiting")
        .where("sendAt", "<", cutoff)
        .limit(SWEEP_BATCH)
        .get(),
      db
        .collectionGroup("outbox")
        .where("status", "==", "retrying")
        .where("nextAttemptAt", "<", cutoff)
        .limit(SWEEP_BATCH)
        .get(),
      db
        .collectionGroup("outbox")
        .where("status", "==", "sending")
        .where(
          "claimedAt",
          "<",
          Timestamp.fromMillis(dueBefore - CLAIM_LEASE_MS),
        )
        .limit(SWEEP_BATCH)
        .get(),
    ]);
    const docs = [...waiting.docs, ...retrying.docs, ...sending.docs];
    return docs.flatMap((doc): OverdueSend[] => {
      const owner = doc.ref.parent.parent?.id;
      const entry = outboxFromDoc(doc.data());
      if (
        owner === undefined ||
        (entry?.status !== "waiting" &&
          entry?.status !== "retrying" &&
          entry?.status !== "sending")
      ) {
        return [];
      }
      return [
        {
          uid: owner,
          outboxId: doc.id,
          status: entry.status,
          sendAt: entry.sendAt,
          attempts: entry.attempts,
        },
      ];
    });
  },
};

/**
 * Resend SMTP through nodemailer (loaded on first use), sending the message bytes as they are;
 * call only inside a handler.
 */
export function resendSmtp(): SmtpSender | undefined {
  const apiKey = RESEND_API_KEY.value();
  if (apiKey === "") return undefined;
  const connect = async () =>
    (await import("nodemailer")).default.createTransport({
      host: RESEND_SMTP.host,
      port: RESEND_SMTP.port,
      secure: true,
      auth: { user: RESEND_SMTP.user, pass: apiKey },
    });
  return {
    async verify() {
      const transport = await connect();
      try {
        await transport.verify();
      } catch (error) {
        throw smtpErrorOf(error);
      } finally {
        transport.close();
      }
    },
    async send(envelope, message) {
      const transport = await connect();
      try {
        const info = await transport.sendMail({ envelope, raw: message });
        return {
          accepted: info.accepted.map(String),
          rejected: info.rejected.map(String),
        };
      } catch (error) {
        throw smtpErrorOf(error);
      } finally {
        transport.close();
      }
    },
  };
}

/**
 * The production wiring of the send handlers; call only inside a handler. Pass `resendSmtp()` from
 * a function that declares the `RESEND_API_KEY` secret; without it, Resend sending is off.
 */
export function liveSendDeps(smtp?: SmtpSender): SendDeps {
  return {
    store: firestoreOutbox,
    gmailFor: async (uid) => (await gmailForUser(uid))?.gmail,
    smtp,
    queue: cloudTaskQueue<SendTask>(SEND_FUNCTION),
    resendDomain: RESEND_DOMAIN,
    now: Date.now,
    log: logger,
  };
}
