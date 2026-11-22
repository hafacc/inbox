import { beforeEach, describe, expect, it } from "bun:test";
import {
  type GmailApi,
  GmailError,
  type GmailRawDraft,
  type GmailSendAs,
} from "./gmail-api.js";
import {
  type AttemptRecord,
  type BlockRecord,
  type CarriedAttempt,
  CLAIM_LEASE_MS,
  CLAIM_SLACK_MS,
  type Claim,
  COPY_KEEP_WITHOUT_ACCESS_MS,
  carriedAttempt,
  type DraftHandover,
  decideClaim,
  FILING_LEASE_MS,
  handleOutboxWrite,
  handleSendTask,
  IDEMPOTENCY_HEADER,
  idempotencyKeyFor,
  type OutboxStore,
  type OverdueSend,
  RESEND_KEY_WINDOW_MS,
  RETRY_DELAYS_MINUTES,
  type ResendRecord,
  type RetryRecord,
  requeueOverdueSends,
  resumeBlocked,
  resumeFiling,
  resumeResendSends,
  retryDelayMs,
  type SendDeps,
  type SentCopy,
  type SentRecord,
  SmtpError,
  SWEEP_MARGIN_MS,
  smtpErrorOf,
} from "./outbox.js";
import {
  type BlockReason,
  type OutboxEntry,
  type RetryReason,
  type SendFailure,
  type SendRoute,
  type SendTask,
  sendTaskId,
} from "./outbox-task.js";
import { splitMessage } from "./raw-message.js";
import { MAX_TASK_DELAY_MS } from "./task-legs.js";

const UID = "google:1";
const NOW = 1_800_000_000_000;
const SEND_AT = NOW - 1000;

type StoredEntry = OutboxEntry & {
  messageId?: string | null;
  threadId?: string | null;
  rejected?: string[];
  error?: { code: SendFailure; message: string };
  lastError?: { code: RetryReason | BlockReason; message: string } | null;
};

class MemoryOutbox implements OutboxStore {
  entries = new Map<string, StoredEntry>();
  addresses = new Map<string, string>();
  inactiveAddresses = new Set<string>();
  copies = new Map<
    string,
    SentCopy & { filingAt?: number; nextAttemptAt?: number }
  >();
  handovers = new Map<string, DraftHandover>();
  accounts = new Set([UID]);

  async entry(_uid: string, outboxId: string) {
    return this.entries.get(outboxId);
  }
  async claim(
    _uid: string,
    outboxId: string,
    sendAt: number,
    now: number,
  ): Promise<Claim> {
    const claim = decideClaim(this.entries.get(outboxId), sendAt, now);
    if (claim.kind === "claimed" || claim.kind === "expired") {
      const stored = this.entries.get(outboxId)!;
      stored.status = "sending";
      stored.claimedAt = now;
      stored.firstAttemptAt = claim.entry.firstAttemptAt;
      stored.nextAttemptAt = null;
      stored.attempts = claim.entry.attempts;
      stored.deviceClaim = null;
    }
    return claim;
  }
  async markAttempt(_uid: string, outboxId: string, record: AttemptRecord) {
    const stored = this.entries.get(outboxId);
    if (stored === undefined) return false;
    Object.assign(stored, record);
    return true;
  }
  async markRetrying(_uid: string, outboxId: string, record: RetryRecord) {
    const stored = this.entries.get(outboxId);
    if (stored?.status !== "sending") return false;
    Object.assign(stored, {
      status: "retrying",
      via: record.via,
      attempts: record.attempts,
      nextAttemptAt: record.nextAttemptAt,
      lastError: { code: record.reason, message: record.message },
      uncertainSince: record.uncertainSince,
      messageDate: record.messageDate,
      idempotencyKey: record.idempotencyKey,
      claimedAt: null,
      inFlightAt: null,
    });
    return true;
  }
  async markBlocked(_uid: string, outboxId: string, record: BlockRecord) {
    const stored = this.entries.get(outboxId);
    if (stored?.status !== "sending") return false;
    Object.assign(stored, {
      status: "blocked",
      blockedOn: record.on,
      via: record.via,
      lastError: { code: record.on, message: record.message },
      uncertainSince: record.uncertainSince,
      messageDate: record.messageDate,
      idempotencyKey: record.idempotencyKey,
      claimedAt: null,
      inFlightAt: null,
      nextAttemptAt: null,
    });
    return true;
  }
  async blockedSends(on: BlockReason) {
    return [...this.entries]
      .filter(([, entry]) => entry.blockedOn === on)
      .map(([outboxId]) => ({ uid: UID, outboxId }));
  }
  async unblock(_uid: string, outboxId: string, on: BlockReason) {
    const stored = this.entries.get(outboxId);
    if (stored?.status !== "blocked" || stored.blockedOn !== on) return false;
    Object.assign(stored, {
      status: "waiting",
      attempts: 0,
      blockedOn: null,
      lastError: null,
      nextAttemptAt: null,
    });
    return true;
  }
  async restoreAttempt(
    _uid: string,
    outboxId: string,
    draftId: string,
    carried: CarriedAttempt,
  ) {
    const stored = this.entries.get(outboxId);
    if (
      stored?.status !== "waiting" ||
      stored.draftId !== draftId ||
      stored.idempotencyKey !== null ||
      stored.uncertainSince !== null
    ) {
      return false;
    }
    for (const [key, value] of Object.entries(carried)) {
      if (value !== null) Object.assign(stored, { [key]: value });
    }
    return true;
  }
  async markSent(_uid: string, outboxId: string, record: SentRecord) {
    const stored = this.entries.get(outboxId);
    if (stored === undefined) return;
    Object.assign(stored, {
      status: "sent",
      via: record.via,
      sentAt: record.sentAt,
      messageId: record.messageId,
      threadId: record.threadId,
      messageIdHeader: record.messageIdHeader,
      filed: record.filed,
      rejected: record.rejected,
      inFlightAt: null,
    });
  }
  async markFiled(
    _uid: string,
    outboxId: string,
    messageId: string | null,
    threadId: string | null,
  ) {
    const stored = this.entries.get(outboxId);
    if (stored === undefined) return;
    stored.filed = true;
    stored.messageId = messageId ?? stored.messageId;
    stored.threadId = threadId ?? stored.threadId;
  }
  async markFailed(
    _uid: string,
    outboxId: string,
    via: SendRoute | null,
    code: SendFailure,
    message: string,
    resend?: ResendRecord,
  ) {
    const stored = this.entries.get(outboxId);
    if (stored === undefined) return;
    if (resend !== undefined) Object.assign(stored, resend);
    stored.status = "failed";
    stored.via = via;
    stored.error = { code, message };
    stored.errorCode = code;
    stored.inFlightAt = null;
  }
  async activeAddressOwner(local: string) {
    return this.inactiveAddresses.has(local)
      ? undefined
      : this.addresses.get(local);
  }
  async saveSentCopy(_uid: string, outboxId: string, copy: SentCopy) {
    if (!this.entries.has(outboxId)) return false;
    this.copies.set(outboxId, {
      ...copy,
      nextAttemptAt: copy.savedAt + SWEEP_MARGIN_MS,
    });
    return true;
  }
  async claimSentCopy(_uid: string, outboxId: string, now: number) {
    const copy = this.copies.get(outboxId);
    if (copy === undefined) return undefined;
    if (copy.filingAt !== undefined && now - copy.filingAt < FILING_LEASE_MS) {
      return "busy" as const;
    }
    copy.filingAt = now;
    return copy;
  }
  async deleteSentCopy(_uid: string, outboxId: string) {
    this.copies.delete(outboxId);
  }
  async unfiledCopies(before: number, uid?: string) {
    return [...this.copies]
      .filter(([, copy]) =>
        uid === undefined
          ? (copy.nextAttemptAt ?? copy.savedAt + SWEEP_MARGIN_MS) < before
          : copy.savedAt < before,
      )
      .map(([outboxId, copy]) => ({
        uid: UID,
        outboxId,
        savedAt: copy.savedAt,
      }));
  }
  async deferSentCopy(_uid: string, outboxId: string, until: number) {
    const copy = this.copies.get(outboxId);
    if (copy !== undefined) copy.nextAttemptAt = until;
  }
  async accountExists(uid: string) {
    return this.accounts.has(uid);
  }
  async draftHandover(_uid: string, draftId: string) {
    const kept = this.handovers.get(draftId);
    return kept === undefined ? undefined : { ...kept };
  }
  async keepDraftHandover(
    _uid: string,
    outboxId: string,
    draftId: string,
    handover: DraftHandover,
  ) {
    if (!this.entries.has(outboxId)) return false;
    this.handovers.set(draftId, { ...handover });
    return true;
  }
  async dropDraftHandover(
    _uid: string,
    draftId: string,
    idempotencyKey?: string,
  ) {
    const kept = this.handovers.get(draftId);
    if (
      kept !== undefined &&
      (idempotencyKey === undefined || kept.idempotencyKey === idempotencyKey)
    ) {
      this.handovers.delete(draftId);
    }
  }
  async overdueSends(dueBefore: number): Promise<OverdueSend[]> {
    return [...this.entries].flatMap(([outboxId, entry]): OverdueSend[] => {
      const due =
        entry.status === "waiting"
          ? entry.sendAt
          : entry.status === "retrying"
            ? entry.nextAttemptAt
            : entry.status === "sending" && entry.claimedAt !== null
              ? entry.claimedAt + CLAIM_LEASE_MS
              : null;
      if (
        due === null ||
        due >= dueBefore ||
        (entry.status !== "waiting" &&
          entry.status !== "retrying" &&
          entry.status !== "sending")
      ) {
        return [];
      }
      return [
        {
          uid: UID,
          outboxId,
          status: entry.status,
          sendAt: entry.sendAt,
          attempts: entry.attempts,
        },
      ];
    });
  }
}

function rawMessage(from: string): string {
  return Buffer.from(
    [
      `From: Ana <${from}>`,
      "To: Bob <bob@example.com>",
      "Bcc: secret@example.com",
      "Subject: Hi",
      "Message-ID: <draft1@mail.gmail.com>",
      "Date: Thu, 01 Jan 2026 00:00:00 +0000",
      "MIME-Version: 1.0",
      "Content-Type: text/plain; charset=UTF-8",
      "",
      "Hello",
      "",
    ].join("\r\n"),
  ).toString("base64url");
}

class FakeGmail implements GmailApi {
  drafts = new Map<string, GmailRawDraft>();
  sendAs: GmailSendAs[] = [
    { sendAsEmail: "ana@gmail.example.com", isPrimary: true },
    { sendAsEmail: "ana@work.example", verificationStatus: "accepted" },
    { sendAsEmail: "ana@pending.example", verificationStatus: "pending" },
  ];
  sentDrafts: string[] = [];
  inserted: {
    raw: string;
    threadId: string | undefined;
    labelIds: string[];
  }[] = [];
  searches: string[] = [];
  existingCopy = false;
  insertError: Error | undefined;
  sendError: Error | undefined;
  draftError: Error | undefined;
  /** send, then lose the response, as when the connection drops */
  loseSendResponse = false;
  messages = new Map<
    string,
    { id: string; threadId: string; labelIds: string[] }
  >();

  addDraft(id: string, from: string) {
    this.drafts.set(id, {
      id,
      message: { id: `m-${id}`, threadId: "t1", raw: rawMessage(from) },
    });
  }
  async watch(): Promise<never> {
    throw new Error("unused");
  }
  async stop(): Promise<never> {
    throw new Error("unused");
  }
  async listHistory(): Promise<never> {
    throw new Error("unused");
  }
  async getMessage(id: string) {
    const message = this.messages.get(id);
    if (message === undefined) throw new GmailError(404, "not found");
    return message;
  }
  async getThread(): Promise<never> {
    throw new Error("unused");
  }
  async modifyThread(): Promise<never> {
    throw new Error("unused");
  }
  async getHistoryId(): Promise<never> {
    throw new Error("unused");
  }
  async listMessages(query: string) {
    this.searches.push(query);
    return this.existingCopy ? [{ id: "copy0", threadId: "t1" }] : [];
  }
  async getDraftRaw(id: string) {
    if (this.draftError !== undefined) throw this.draftError;
    const draft = this.drafts.get(id);
    if (draft === undefined) throw new GmailError(404, "not found");
    this.rawReads.push(id);
    return draft;
  }
  /** drafts whose headers were read, in order */
  headerReads: string[] = [];
  /** drafts whose whole message was read, in order */
  rawReads: string[] = [];
  async getDraftHeaders(id: string) {
    if (this.draftError !== undefined) throw this.draftError;
    const draft = this.drafts.get(id);
    if (draft === undefined) throw new GmailError(404, "not found");
    this.headerReads.push(id);
    const { headers } = splitMessage(
      Buffer.from(draft.message.raw, "base64url"),
    );
    return {
      id,
      message: {
        id: draft.message.id,
        threadId: draft.message.threadId,
        payload: {
          headers: headers.map(({ name, value }) => ({ name, value })),
        },
      },
    };
  }
  async sendDraft(id: string) {
    if (this.sendError !== undefined) throw this.sendError;
    const draft = this.drafts.get(id);
    if (draft === undefined) throw new GmailError(404, "not found");
    this.drafts.delete(id);
    this.sentDrafts.push(id);
    const sent = {
      id: draft.message.id,
      threadId: draft.message.threadId,
      labelIds: ["SENT"],
    };
    this.messages.set(sent.id, sent);
    if (this.loseSendResponse) throw new TypeError("fetch failed");
    return { id: sent.id, threadId: sent.threadId };
  }
  async deleteDraft(id: string) {
    if (!this.drafts.delete(id)) throw new GmailError(404, "not found");
  }
  async insertMessage(
    raw: string,
    threadId: string | undefined,
    labelIds: string[],
  ) {
    if (this.insertError !== undefined) throw this.insertError;
    this.inserted.push({ raw, threadId, labelIds });
    return { id: "copy1", threadId: threadId ?? "t-new" };
  }
  async listSendAs() {
    return this.sendAs;
  }
}

let outbox: MemoryOutbox;
let gmail: FakeGmail;
let smtpSent: { envelope: { from: string; to: string[] }; message: string }[];
let smtpError: Error | undefined;
let verifyError: Error | undefined;
let smtpTried: string[];
let queued: { task: SendTask; id: string; at: Date }[];
let removed: string[];
let grant: boolean;
let clock: number;
let deps: SendDeps;

function waiting(from: string, extra: Partial<OutboxEntry> = {}): StoredEntry {
  return {
    status: "waiting",
    draftId: "d1",
    from,
    sendAt: SEND_AT,
    claimedAt: null,
    via: null,
    sentAt: null,
    messageIdHeader: null,
    filed: false,
    attempts: 0,
    firstAttemptAt: null,
    nextAttemptAt: null,
    inFlightAt: null,
    uncertainSince: null,
    draftMessageId: null,
    messageDate: null,
    idempotencyKey: null,
    blockedOn: null,
    errorCode: null,
    deviceClaim: null,
    ...extra,
  };
}

function task(extra: Partial<SendTask> = {}): SendTask {
  return {
    uid: UID,
    outboxId: "o1",
    sendAt: SEND_AT,
    writtenAt: SEND_AT - 10_000,
    hop: 1,
    attempt: 0,
    ...extra,
  };
}

beforeEach(() => {
  outbox = new MemoryOutbox();
  outbox.addresses.set("test", UID);
  outbox.addresses.set("team", "google:2");
  gmail = new FakeGmail();
  smtpSent = [];
  smtpError = undefined;
  verifyError = undefined;
  smtpTried = [];
  queued = [];
  removed = [];
  grant = true;
  clock = NOW;
  deps = {
    store: outbox,
    gmailFor: async () => (grant ? gmail : undefined),
    smtp: {
      async verify() {
        if (verifyError !== undefined) throw verifyError;
      },
      async send(envelope, message) {
        smtpTried.push(message.toString("utf8"));
        if (smtpError !== undefined) throw smtpError;
        smtpSent.push({ envelope, message: message.toString("utf8") });
        return { accepted: envelope.to, rejected: [] };
      },
    },
    queue: {
      async enqueue(queuedTask, id, at) {
        queued.push({ task: queuedTask, id, at });
      },
      async remove(id) {
        removed.push(id);
      },
    },
    resendDomain: "hafa.cc",
    now: () => clock,
    log: { info() {}, warn() {} },
  };
});

describe("handleSendTask through Gmail", () => {
  it("sends the draft from the primary address and records it", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", waiting("Ana@Gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      via: "gmail",
      sentAt: NOW,
      messageId: "m-d1",
      threadId: "t1",
      filed: true,
    });
    expect(smtpSent).toEqual([]);
  });

  it("sends from a verified send-as alias", async () => {
    gmail.addDraft("d1", "ana@work.example");
    outbox.entries.set("o1", waiting("ana@work.example"));
    expect(await handleSendTask(deps, task())).toBe("sent");
  });

  it("refuses an alias Gmail hasn't verified", async () => {
    gmail.addDraft("d1", "ana@pending.example");
    outbox.entries.set("o1", waiting("ana@pending.example"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("not-owned");
    expect(gmail.sentDrafts).toEqual([]);
  });

  it("never sends twice when the task fires again", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    await handleSendTask(deps, task());
    expect(await handleSendTask(deps, task())).toBe("stale");
    expect(gmail.sentDrafts).toEqual(["d1"]);
  });

  it("retries a server error later with the error and next attempt shown", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.sendError = new GmailError(503, "backend error");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "retrying",
      attempts: 1,
      nextAttemptAt: NOW + retryDelayMs(1),
      firstAttemptAt: NOW,
      lastError: { code: "server", message: "backend error" },
    });
    expect(queued).toHaveLength(1);
    expect(queued[0]?.task.attempt).toBe(1);
    expect(queued[0]?.at.getTime()).toBe(NOW + retryDelayMs(1));

    gmail.sendError = undefined;
    clock = NOW + retryDelayMs(1);
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
  });

  it("treats Gmail's rate limits as passing, including a 403 with a rate reason", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.sendError = new GmailError(403, "slow down", "userRateLimitExceeded");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")?.lastError?.code).toBe("rate-limited");
  });

  it("retries after 1, 5 and 30 minutes, then leaves it to the user to send again", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.sendError = new GmailError(500, "down");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    let result = await handleSendTask(deps, task());
    const gaps: number[] = [];
    while (result === "retrying") {
      const next = queued.at(-1)!;
      gaps.push(next.at.getTime() - clock);
      clock = next.at.getTime();
      result = await handleSendTask(deps, next.task);
    }
    expect(result).toBe("failed");
    expect(RETRY_DELAYS_MINUTES).toEqual([1, 5, 30]);
    expect(gaps).toEqual([1, 5, 30].map((minutes) => minutes * 60_000));
    expect(outbox.entries.get("o1")?.error).toEqual({
      code: "unavailable",
      message: "Still failing after 4 tries: down",
    });
    expect(gmail.sentDrafts).toEqual([]);

    gmail.sendError = undefined;
    const before = outbox.entries.get("o1")!;
    const again = waiting("ana@gmail.example.com", { sendAt: clock + 10_000 });
    outbox.entries.set("o1", again);
    queued = [];
    await handleOutboxWrite(
      deps,
      UID,
      "o1",
      {
        status: "failed",
        waiting: { sendAt: SEND_AT, writtenAt: NOW },
        entry: before,
      },
      {
        status: "waiting",
        waiting: { sendAt: clock + 10_000, writtenAt: clock },
        entry: { ...again },
      },
    );
    clock = queued[0]!.at.getTime();
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
  });

  it("doesn't send again when drafts.send went but its answer was lost", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.loseSendResponse = true;
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")?.lastError?.code).toBe("network");
    clock = queued[0]!.at.getTime();
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      messageId: "m-d1",
    });
  });

  it("finds the sent message by Message-ID when its Gmail id isn't there", async () => {
    gmail.existingCopy = true;
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "retrying",
        via: "gmail",
        attempts: 1,
        nextAttemptAt: NOW - 1,
        draftMessageId: "m-old",
        messageIdHeader: "<draft1@mail.gmail.com>",
      }),
    );
    expect(await handleSendTask(deps, task({ attempt: 1 }))).toBe("sent");
    expect(gmail.searches).toEqual([
      "rfc822msgid:draft1@mail.gmail.com in:sent",
    ]);
    expect(outbox.entries.get("o1")?.messageId).toBe("copy0");
  });

  it("marks a refusal failed without retrying", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.sendError = new GmailError(400, "Invalid To header");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error).toEqual({
      code: "rejected",
      message: "Invalid To header",
    });
  });

  it("retries when the draft can't be read for now", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.draftError = new GmailError(500, "oops");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")?.status).toBe("retrying");
  });
});

describe("handleSendTask checks", () => {
  it("does nothing once the entry is deleted (undo)", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    expect(await handleSendTask(deps, task())).toBe("stale");
    expect(gmail.sentDrafts).toEqual([]);
  });

  it("does nothing when the entry was rescheduled", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", { sendAt: NOW + 1000 }),
    );
    expect(await handleSendTask(deps, task())).toBe("stale");
    expect(outbox.entries.get("o1")?.status).toBe("waiting");
  });

  it("fails when the draft is gone", async () => {
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("draft-missing");
  });

  it("looks up the send-as addresses alongside the draft, not after it", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    const readHeaders = gmail.getDraftHeaders.bind(gmail);
    const listSendAs = gmail.listSendAs.bind(gmail);
    let listed: () => void = () => {};
    const sendAsAsked = new Promise<void>((resolve) => {
      listed = resolve;
    });
    gmail.listSendAs = () => {
      listed();
      return listSendAs();
    };
    // The headers come back only once the send-as list was asked for, as when both are in flight.
    gmail.getDraftHeaders = async (id: string) => {
      await sendAsAsked;
      return readHeaders(id);
    };
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
  });

  it("still fails a From mismatch when the send-as lookup fails", async () => {
    gmail.addDraft("d1", "ana@work.example");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    gmail.listSendAs = async () => {
      throw new TypeError("fetch failed");
    };
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("from-mismatch");
  });

  it("sends through Gmail reading only the draft's headers", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.headerReads).toEqual(["d1"]);
    expect(gmail.rawReads).toEqual([]);
    expect(outbox.entries.get("o1")).toMatchObject({
      messageIdHeader: "<draft1@mail.gmail.com>",
    });
  });

  it("refuses a Resend draft whose header has a bare line break", async () => {
    gmail.drafts.set("d1", {
      id: "d1",
      message: {
        id: "m-d1",
        threadId: "t1",
        raw: Buffer.from(
          "From: test@hafa.cc\r\nSubject: hi\nBcc: x@example.com\r\n\r\nBody\r\n",
        ).toString("base64url"),
      },
    });
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("rejected");
    expect(smtpTried).toEqual([]);
  });

  function setDraft(lines: string[]) {
    gmail.drafts.set("d1", {
      id: "d1",
      message: {
        id: "m-d1",
        threadId: "t1",
        raw: Buffer.from(
          `${[...lines, "Subject: hi", "", "Body"].join("\r\n")}\r\n`,
        ).toString("base64url"),
      },
    });
  }

  for (const from of ["ana@gmail.example.com", "test@hafa.cc"]) {
    it(`refuses a draft whose In-Reply-To isn't message ids (from ${from})`, async () => {
      setDraft([
        `From: ${from}`,
        "To: bob@example.com",
        "In-Reply-To: <a1@x> Bcc eve@example.net",
      ]);
      outbox.entries.set("o1", waiting(from));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error).toEqual({
        code: "rejected",
        message:
          "The draft's In-Reply-To line holds something other than message ids, so it can't be sent safely.",
      });
      expect(gmail.sentDrafts).toEqual([]);
      expect(smtpTried).toEqual([]);
    });

    it(`refuses a draft with two Bcc lines (from ${from})`, async () => {
      setDraft([
        `From: ${from}`,
        "To: bob@example.com",
        "Bcc: cat@example.com",
        "Bcc: eve@example.net",
      ]);
      outbox.entries.set("o1", waiting(from));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error?.message).toBe(
        "The draft has more than one Bcc line, so it can't be sent safely.",
      );
      expect(gmail.sentDrafts).toEqual([]);
      expect(smtpTried).toEqual([]);
    });

    it(`refuses a draft with a control character in a header (from ${from})`, async () => {
      setDraft([`From: ${from}`, "To: bob@example.com", "X-Note: a\u0000b"]);
      outbox.entries.set("o1", waiting(from));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error?.message).toBe(
        "The draft's X-Note line holds a hidden control character, so it can't be sent safely.",
      );
      expect(gmail.sentDrafts).toEqual([]);
      expect(smtpTried).toEqual([]);
    });

    it(`sends a reply with folded References and a tab (from ${from})`, async () => {
      setDraft([
        `From: ${from}`,
        "To: bob@example.com",
        "In-Reply-To: <a2@x>",
        "References: <a1@x>",
        " <a2@x>",
        "X-Note: a\tb",
      ]);
      outbox.entries.set("o1", waiting(from));
      expect(await handleSendTask(deps, task())).toBe("sent");
    });
  }

  it("refuses @hafa.cc mail whose From name holds an email address", async () => {
    for (const from of [
      '"test@hafa.cc" <test@hafa.cc>',
      '"Ana (bob@x.example.com)" <test@hafa.cc>',
      "=?UTF-8?B?Ym9iQHguY29t?= <test@hafa.cc>",
      "test@hafa.cc (bob@x.example.com)",
    ]) {
      setDraft([`From: ${from}`, "To: bob@example.com"]);
      outbox.entries.set("o1", waiting("test@hafa.cc"));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error).toEqual({
        code: "rejected",
        message:
          "Your name can't contain an email address, since it would make the mail look like it came from that address. Change your name in Settings.",
      });
    }
    expect(smtpTried).toEqual([]);
  });

  it("sends @hafa.cc mail whose From name only has an @ in it", async () => {
    setDraft(['From: "Ana @ Home" <test@hafa.cc>', "To: bob@example.com"]);
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("sent");
  });

  it("refuses @hafa.cc mail whose Reply-To or Sender is another @hafa.cc address", async () => {
    for (const line of [
      "Reply-To: staff@hafa.cc",
      "Sender: Bo <STAFF@hafa.cc>",
    ]) {
      setDraft(["From: Ana <test@hafa.cc>", "To: bob@example.com", line]);
      outbox.entries.set("o1", waiting("test@hafa.cc"));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error?.message).toBe(
        "Mail from your @hafa.cc address can only use that address as its reply address.",
      );
    }
    expect(smtpTried).toEqual([]);
  });

  it("sends @hafa.cc mail replying to its own address or one elsewhere", async () => {
    setDraft([
      "From: Ana <test@hafa.cc>",
      "To: bob@example.com",
      "Reply-To: test@hafa.cc, ana@gmail.example.com",
    ]);
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("sent");
  });

  it("fails when the draft's From differs from the entry", async () => {
    gmail.addDraft("d1", "ana@work.example");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("from-mismatch");
  });

  it("refuses a draft with two From headers or a From naming more than one mailbox", async () => {
    for (const from of [
      "From: Ana <test@hafa.cc>\r\nFrom: Eve <staging@hafa.cc>",
      "From: Ana <test@hafa.cc>, Eve <staging@hafa.cc>",
      "From: team: Ana <test@hafa.cc>;",
    ]) {
      gmail.drafts.set("d1", {
        id: "d1",
        message: {
          id: "m-d1",
          threadId: "t1",
          raw: Buffer.from(
            `${from}\r\nTo: bob@example.com\r\nSubject: Hi\r\n\r\nHello\r\n`,
          ).toString("base64url"),
        },
      });
      outbox.entries.set("o1", waiting("test@hafa.cc"));
      expect(await handleSendTask(deps, task())).toBe("failed");
      expect(outbox.entries.get("o1")?.error?.code).toBe("from-mismatch");
    }
    expect(smtpTried).toEqual([]);
  });

  it("blocks while Google access is gone and goes once sign-in resumes it", async () => {
    grant = false;
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("blocked");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "blocked",
      blockedOn: "sign-in",
      lastError: { code: "sign-in" },
    });
    expect(queued).toEqual([]);
    expect(await resumeBlocked(deps, "resend")).toBe(0);

    grant = true;
    expect(await resumeBlocked(deps, "sign-in", UID)).toBe(1);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "waiting",
      blockedOn: null,
      attempts: 0,
    });
    expect(await handleSendTask(deps, task())).toBe("sent");
  });

  it("retries when the token can't be fetched for now", async () => {
    deps.gmailFor = async () => {
      throw new Error("token endpoint unreachable");
    };
    outbox.entries.set("o1", waiting("ana@gmail.example.com"));
    expect(await handleSendTask(deps, task())).toBe("retrying");
  });

  it("a retry that fires early only makes sure the right one is queued", async () => {
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "retrying",
        attempts: 2,
        nextAttemptAt: NOW + 60_000,
      }),
    );
    expect(await handleSendTask(deps, task({ attempt: 1 }))).toBe("retrying");
    expect(
      queued.map((entry) => [entry.task.attempt, entry.at.getTime()]),
    ).toEqual([[2, NOW + 60_000]]);
  });

  it("an early retry queues itself under an id of its own, so it isn't dropped as a repeat", async () => {
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "retrying",
        attempts: 2,
        nextAttemptAt: NOW + 60_000,
      }),
    );
    const own = task({ attempt: 2 });
    expect(await handleSendTask(deps, own)).toBe("retrying");
    expect(queued).toHaveLength(1);
    expect(queued[0]!.task).toEqual(own);
    expect(queued[0]!.id).not.toBe(sendTaskId(own));
  });

  it("takes a retry that fires a few seconds early as due", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "retrying",
        attempts: 1,
        nextAttemptAt: NOW + 5000,
      }),
    );
    expect(await handleSendTask(deps, task({ attempt: 1 }))).toBe("sent");
  });

  it("stops retrying once the entry is deleted", async () => {
    expect(await handleSendTask(deps, task({ attempt: 3 }))).toBe("stale");
  });

  it("only queues the next leg of a send more than 29 days out", async () => {
    const far = NOW + 40 * 24 * 60 * 60 * 1000;
    outbox.entries.set("o1", waiting("ana@gmail.example.com", { sendAt: far }));
    const first = task({ sendAt: far, writtenAt: NOW - MAX_TASK_DELAY_MS });
    expect(await handleSendTask(deps, first)).toBe("rescheduled");
    expect(queued.map((entry) => entry.task.hop)).toEqual([2]);
    expect(queued[0]?.at.getTime()).toBe(NOW + MAX_TASK_DELAY_MS);
  });

  it("checks back after the lease while another attempt holds the entry", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        claimedAt: NOW - 1000,
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("busy");
    expect(gmail.sentDrafts).toEqual([]);
    expect(queued[0]?.at.getTime()).toBe(NOW - 1000 + CLAIM_LEASE_MS + 1000);
  });

  it("takes over a dead attempt whose draft still exists", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
  });

  it("counts a dead attempt whose draft went to Sent as sent", async () => {
    gmail.messages.set("m-d1", {
      id: "m-d1",
      threadId: "t1",
      labelIds: ["SENT"],
    });
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        via: "gmail",
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
        inFlightAt: NOW - CLAIM_LEASE_MS - 1,
        draftMessageId: "m-d1",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      messageId: "m-d1",
    });
  });

  it("counts each dead run as an attempt and fails once they pass the retries", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        attempts: 1,
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
      }),
    );
    gmail.sendError = new GmailError(503, "busy");
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")?.attempts).toBe(3);
    gmail.sendError = undefined;

    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        via: "resend",
        attempts: 3,
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
        inFlightAt: NOW - CLAIM_LEASE_MS - 1,
        messageDate: NOW - CLAIM_LEASE_MS - 1,
        idempotencyKey: "k",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "failed",
      attempts: 4,
      error: { code: "unavailable" },
      uncertainSince: NOW - CLAIM_LEASE_MS - 1,
      idempotencyKey: "k",
    });
    expect(gmail.sentDrafts).toEqual([]);
    expect(smtpTried).toEqual([]);
  });

  it("fails as draft-missing when a dead attempt's draft is gone but not in Sent", async () => {
    outbox.entries.set(
      "o1",
      waiting("ana@gmail.example.com", {
        status: "sending",
        via: "gmail",
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
        draftMessageId: "m-d1",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("draft-missing");
  });
});

describe("handleSendTask after a device claimed a send now", () => {
  // As the rules let a device write its claim: the lease starts at the claim, the handover is recorded.
  function deviceClaimed(claimedAt: number): StoredEntry {
    return waiting("ana@gmail.example.com", {
      status: "sending",
      via: "gmail",
      claimedAt,
      firstAttemptAt: claimedAt,
      inFlightAt: claimedAt,
      draftMessageId: "m-d1",
      messageIdHeader: "<draft1@mail.gmail.com>",
      deviceClaim: "c".repeat(24),
    });
  }

  it("does nothing while the device's lease runs, and finds it done after", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", deviceClaimed(NOW - 2000));
    expect(await handleSendTask(deps, task())).toBe("busy");
    expect(gmail.sentDrafts).toEqual([]);
    expect(queued[0]?.at.getTime()).toBe(NOW - 2000 + CLAIM_LEASE_MS + 1000);

    // The device sent it and recorded it as the server would.
    gmail.drafts.delete("d1");
    Object.assign(outbox.entries.get("o1")!, {
      status: "sent",
      sentAt: NOW,
      messageId: "m-d1",
      threadId: "t1",
      filed: true,
      inFlightAt: null,
    });
    clock = NOW + CLAIM_LEASE_MS;
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("stale");
    expect(gmail.sentDrafts).toEqual([]);
    expect(gmail.headerReads).toEqual([]);
  });

  it("leaves a send the device found refused as it is", async () => {
    Object.assign(
      outbox.entries.set("o1", deviceClaimed(NOW - 2000)).get("o1")!,
      {
        status: "failed",
        error: { code: "rejected", message: "Invalid To header" },
        errorCode: "rejected",
      },
    );
    expect(await handleSendTask(deps, task())).toBe("stale");
  });

  it("sends once itself when the device died before handing it over", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", deviceClaimed(NOW - CLAIM_LEASE_MS - 1));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.sentDrafts).toEqual(["d1"]);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      messageId: "m-d1",
      attempts: 1,
      deviceClaim: null,
    });
  });

  it("records it sent, without sending again, when the device died after Gmail took it", async () => {
    gmail.messages.set("m-d1", {
      id: "m-d1",
      threadId: "t1",
      labelIds: ["SENT"],
    });
    outbox.entries.set("o1", deviceClaimed(NOW - CLAIM_LEASE_MS - 1));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.sentDrafts).toEqual([]);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      via: "gmail",
      messageId: "m-d1",
      threadId: "t1",
      deviceClaim: null,
    });
  });

  it("counts the takeover as an attempt and retries without the device's claim", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    gmail.sendError = new GmailError(503, "busy");
    const claimedAt = NOW - CLAIM_LEASE_MS - 1;
    outbox.entries.set("o1", deviceClaimed(claimedAt));
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "retrying",
      attempts: 2,
      firstAttemptAt: claimedAt,
      deviceClaim: null,
    });
    expect(gmail.sentDrafts).toEqual([]);
  });

  it("holds its own lease once it took the device's claim over", async () => {
    gmail.addDraft("d1", "ana@gmail.example.com");
    outbox.entries.set("o1", deviceClaimed(NOW - CLAIM_LEASE_MS - 1));
    const claim = await outbox.claim(UID, "o1", SEND_AT, NOW);
    expect(claim.kind).toBe("expired");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sending",
      claimedAt: NOW,
      deviceClaim: null,
    });
    expect(await handleSendTask(deps, task())).toBe("busy");
    expect(gmail.sentDrafts).toEqual([]);
  });

  it("finds it by Message-ID when Gmail kept another id", async () => {
    gmail.existingCopy = true;
    outbox.entries.set("o1", deviceClaimed(NOW - CLAIM_LEASE_MS - 1));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(gmail.searches).toEqual([
      "rfc822msgid:draft1@mail.gmail.com in:sent",
    ]);
    expect(gmail.sentDrafts).toEqual([]);
  });
});

function keptCopy(savedAt: number): SentCopy {
  return {
    raw: Buffer.from("From: test@hafa.cc\r\n\r\nHi\r\n"),
    draftId: "d1",
    threadId: "t1",
    messageIdHeader: "<dev@hafa.cc>",
    savedAt,
  };
}

describe("handleSendTask through Resend", () => {
  it("sends the raw message without Bcc, files the copy with SENT and deletes the draft", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("sent");

    expect(smtpSent).toHaveLength(1);
    const [sent] = smtpSent;
    expect(sent!.envelope).toEqual({
      from: "test@hafa.cc",
      to: ["bob@example.com", "secret@example.com"],
    });
    expect(sent!.message).not.toContain("Bcc:");
    expect(sent!.message).toContain("Date: Fri, 15 Jan 2027 08:00:00 +0000");
    expect(sent!.message).not.toContain("2026 00:00:00");
    expect(sent!.message).toContain("Message-ID: <draft1@mail.gmail.com>");

    expect(gmail.inserted).toHaveLength(1);
    const copy = Buffer.from(gmail.inserted[0]!.raw, "base64url").toString();
    expect(copy).toContain("Bcc: secret@example.com");
    expect(copy).toContain("Message-ID: <draft1@mail.gmail.com>");
    expect(gmail.inserted[0]).toMatchObject({
      threadId: "t1",
      labelIds: ["SENT"],
    });
    expect(gmail.drafts.has("d1")).toBe(false);
    expect(gmail.sentDrafts).toEqual([]);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      via: "resend",
      filed: true,
      messageId: "copy1",
      messageIdHeader: "<draft1@mail.gmail.com>",
    });
  });

  it("keeps PGP/MIME intact: the same ciphertext goes out and into Sent", async () => {
    const encryptedBody = [
      "This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156)",
      "--=_tok_enc",
      "Content-Type: application/pgp-encrypted",
      "Content-Description: PGP/MIME version identification",
      "",
      "Version: 1",
      "",
      "--=_tok_enc",
      'Content-Type: application/octet-stream; name="encrypted.asc"',
      "Content-Description: OpenPGP encrypted message",
      'Content-Disposition: inline; filename="encrypted.asc"',
      "",
      "-----BEGIN PGP MESSAGE-----",
      "",
      "wV4DR2b2udXyHrYSAQdAO6lKX7b3Gm0p+M1wD3H0lkF4yxxZ0F4qJm9DgYb7cR4w",
      "=Zx0a",
      "-----END PGP MESSAGE-----",
      "--=_tok_enc--",
      "",
    ].join("\r\n");
    const contentType =
      'Content-Type: multipart/encrypted; protocol="application/pgp-encrypted";\r\n boundary="=_tok_enc"';
    const raw = [
      "From: Ana <test@hafa.cc>",
      "To: Bob <bob@example.com>",
      "Bcc: secret@example.com",
      "Subject: ...",
      "Autocrypt: addr=test@hafa.cc; prefer-encrypt=mutual;",
      " keydata=xjMEaVW5ABYJKwYBBAHaRw8BAQdA",
      "MIME-Version: 1.0",
      contentType,
      "",
      encryptedBody,
    ].join("\r\n");
    gmail.drafts.set("d1", {
      id: "d1",
      message: {
        id: "m-d1",
        threadId: "t1",
        raw: Buffer.from(raw).toString("base64url"),
      },
    });
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("sent");

    const [sent] = smtpSent;
    const copy = Buffer.from(gmail.inserted[0]!.raw, "base64url").toString();
    for (const message of [sent!.message, copy]) {
      expect(message).toContain(`${contentType}\r\n\r\n${encryptedBody}`);
      expect(message.endsWith(`\r\n\r\n${encryptedBody}`)).toBe(true);
      expect(message).toContain(
        "Autocrypt: addr=test@hafa.cc; prefer-encrypt=mutual;\r\n keydata=",
      );
    }
    expect(sent!.message).not.toContain("Bcc:");
    expect(copy).toContain("Bcc: secret@example.com");
  });

  it("sends an encrypted send's main message and Bcc copy as separate docs, each to its own recipients", async () => {
    const draft = (id: string, addressing: string[]) => ({
      id,
      message: {
        id: `m-${id}`,
        threadId: "t1",
        raw: Buffer.from(
          [
            "From: Ana <test@hafa.cc>",
            ...addressing,
            "Subject: ...",
            "MIME-Version: 1.0",
            'Content-Type: multipart/encrypted; protocol="application/pgp-encrypted"; boundary="b"',
            "",
            "--b--",
            "",
          ].join("\r\n"),
        ).toString("base64url"),
      },
    });
    gmail.drafts.set(
      "d1",
      draft("d1", ["To: Bob <bob@example.com>", "Cc: cy@example.org"]),
    );
    gmail.drafts.set("d2", draft("d2", ["Bcc: secret@example.com"]));
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    outbox.entries.set("o1-bcc-1", waiting("test@hafa.cc", { draftId: "d2" }));
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(await handleSendTask(deps, task({ outboxId: "o1-bcc-1" }))).toBe(
      "sent",
    );

    expect(smtpSent.map((sent) => sent.envelope.to)).toEqual([
      ["bob@example.com", "cy@example.org"],
      ["secret@example.com"],
    ]);
    const [, copySent] = smtpSent;
    expect(copySent!.message).not.toContain("Bcc:");
    expect(copySent!.message).not.toMatch(/^(To|Cc):/m);
    const filed = gmail.inserted.map((entry) =>
      Buffer.from(entry.raw, "base64url").toString(),
    );
    expect(filed).toHaveLength(2);
    expect(filed[1]).toContain("Bcc: secret@example.com");
    const messageIds = new Set(
      smtpSent.map((sent) => /Message-ID: (.*)/.exec(sent.message)?.[1]),
    );
    expect(messageIds.size).toBe(2);
    for (const outboxId of ["o1", "o1-bcc-1"]) {
      expect(outbox.entries.get(outboxId)).toMatchObject({
        status: "sent",
        filed: true,
      });
    }
  });

  it("refuses the account's own @hafa.cc address until its setup is active", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    outbox.inactiveAddresses.add("test");
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("not-owned");
    expect(smtpSent).toEqual([]);
  });

  it("refuses an @hafa.cc address another user holds", async () => {
    gmail.addDraft("d1", "team@hafa.cc");
    outbox.entries.set("o1", waiting("team@hafa.cc"));
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("not-owned");
    expect(smtpSent).toEqual([]);
  });

  it("sends an idempotency key, retries a refusal with a new key and date, and fails a permanent one", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = new SmtpError("451 try later", false);
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "retrying",
      via: "resend",
      idempotencyKey: null,
      messageDate: null,
      uncertainSince: null,
    });
    smtpError = new SmtpError("550 mailbox unavailable", true);
    clock = queued[0]!.at.getTime();
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("failed");
    expect(outbox.entries.get("o1")?.error).toEqual({
      code: "rejected",
      message: "550 mailbox unavailable",
    });
  });

  it("treats Resend's daily limit as passing", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message:
        "Message failed: 550 daily_quota_exceeded: You have reached your daily email sending quota",
      responseCode: 550,
      command: "DATA",
    });
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.entries.get("o1")?.lastError?.code).toBe("rate-limited");
  });

  it("resends the identical message under the same key after an unknown outcome", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "Connection closed",
      code: "ECONNECTION",
      command: "DATA",
    });
    expect(await handleSendTask(deps, task())).toBe("retrying");
    const after = outbox.entries.get("o1")!;
    expect(after.uncertainSince).toBe(NOW);
    expect(after.idempotencyKey).toBe(`${idempotencyKeyFor(UID, "o1", NOW)}`);
    expect(after.messageDate).toBe(NOW);

    smtpError = undefined;
    clock = queued[0]!.at.getTime();
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    const [sent] = smtpSent;
    expect(sent!.message).toContain(
      `${IDEMPOTENCY_HEADER}: ${idempotencyKeyFor(UID, "o1", NOW)}`,
    );
    expect(sent!.message).toContain("Date: Fri, 15 Jan 2027 08:00:00 +0000");
    const copy = Buffer.from(gmail.inserted[0]!.raw, "base64url").toString();
    expect(copy).not.toContain(IDEMPOTENCY_HEADER);
    expect(copy).toContain("Date: Fri, 15 Jan 2027 08:00:00 +0000");
  });

  it("reuses the draft's key when its doc was deleted mid-retry and made again", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "Connection closed",
      code: "ECONNECTION",
      command: "DATA",
    });
    expect(await handleSendTask(deps, task())).toBe("retrying");
    outbox.entries.delete("o1");
    outbox.entries.set("o2", waiting("test@hafa.cc"));
    smtpError = undefined;
    clock = NOW + 5 * 60_000;
    expect(
      await handleSendTask(deps, task({ outboxId: "o2", sendAt: SEND_AT })),
    ).toBe("sent");
    const key = `${IDEMPOTENCY_HEADER}: ${idempotencyKeyFor(UID, "o1", NOW)}`;
    expect(smtpTried[0]).toContain(key);
    expect(smtpSent[0]!.message).toBe(smtpTried[0]!);
    expect(outbox.handovers.size).toBe(0);
  });

  it("sends a draft as new once Resend refused it outright", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "Try later",
      responseCode: 451,
      command: "DATA",
    });
    expect(await handleSendTask(deps, task())).toBe("retrying");
    expect(outbox.handovers.size).toBe(0);
    outbox.entries.delete("o1");
    outbox.entries.set("o2", waiting("test@hafa.cc"));
    smtpError = undefined;
    clock = NOW + 60_000;
    await handleSendTask(deps, task({ outboxId: "o2" }));
    expect(smtpSent[0]!.message).toContain(
      `${IDEMPOTENCY_HEADER}: ${idempotencyKeyFor(UID, "o2", clock)}`,
    );
  });

  it("logs codes, never addresses or the server's text", async () => {
    const lines: unknown[] = [];
    deps.log = {
      info: (...args) => lines.push(args),
      warn: (...args) => lines.push(args),
    };
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("staff@hafa.cc"));
    await handleSendTask(deps, task());
    outbox.entries.set("o2", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "550 bob@example.com mailbox unavailable",
      responseCode: 550,
    });
    await handleSendTask(deps, task({ outboxId: "o2" }));
    expect(JSON.stringify(lines)).not.toMatch(/@|mailbox unavailable/);
  });

  it("keeps the key through the retries and a manual send again while the outcome is unknown", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({ message: "socket hang up", code: "ESOCKET" });
    let result = await handleSendTask(deps, task());
    while (result === "retrying") {
      const next = queued.at(-1)!;
      clock = next.at.getTime();
      result = await handleSendTask(deps, next.task);
    }
    expect(result).toBe("failed");
    const failed = outbox.entries.get("o1")!;
    expect(failed.error?.code).toBe("unavailable");
    expect(failed.error?.message).toContain("may have gone through");
    expect(failed).toMatchObject({
      uncertainSince: NOW,
      idempotencyKey: idempotencyKeyFor(UID, "o1", NOW),
      messageDate: NOW,
    });
    expect(smtpTried).toHaveLength(4);
    expect(new Set(smtpTried).size).toBe(1);

    smtpError = undefined;
    clock += 60 * 60_000;
    const again = waiting("test@hafa.cc", { sendAt: clock + 10_000 });
    outbox.entries.set("o1", again);
    queued = [];
    await handleOutboxWrite(
      deps,
      UID,
      "o1",
      {
        status: "failed",
        waiting: { sendAt: SEND_AT, writtenAt: NOW },
        entry: failed,
      },
      {
        status: "waiting",
        waiting: { sendAt: clock + 10_000, writtenAt: clock },
        entry: { ...again },
      },
    );
    clock = queued[0]!.at.getTime();
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    expect(new Set(smtpTried).size).toBe(1);
    expect(smtpSent[0]!.message).toContain(
      `${IDEMPOTENCY_HEADER}: ${idempotencyKeyFor(UID, "o1", NOW)}`,
    );
  });

  it("won't resend under an old key once its window has passed", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set(
      "o1",
      waiting("test@hafa.cc", {
        via: "resend",
        uncertainSince: NOW - RESEND_KEY_WINDOW_MS,
        messageDate: NOW - RESEND_KEY_WINDOW_MS,
        idempotencyKey: "inbox/old",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("uncertain");
    expect(smtpTried).toEqual([]);
  });

  it("fails as uncertain when Resend says the key was used for other content", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "409 invalid_idempotent_request",
      responseCode: 550,
    });
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("uncertain");
  });

  it("blocks while Resend isn't set up, and the check resumes it once it works", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    const smtp = deps.smtp;
    deps.smtp = undefined;
    expect(await handleSendTask(deps, task())).toBe("blocked");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "blocked",
      blockedOn: "resend",
    });
    expect(queued).toEqual([]);
    expect(await resumeResendSends(deps)).toBe(0);

    deps.smtp = smtp;
    verifyError = smtpErrorOf({ message: "Invalid login", code: "EAUTH" });
    expect(await resumeResendSends(deps)).toBe(0);
    expect(outbox.entries.get("o1")?.status).toBe("blocked");

    verifyError = undefined;
    expect(await resumeResendSends(deps)).toBe(1);
    expect(outbox.entries.get("o1")?.status).toBe("waiting");
    expect(await handleSendTask(deps, task())).toBe("sent");
  });

  it("blocks when Resend refuses the server's credentials", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    smtpError = smtpErrorOf({
      message: "Invalid login: 535 Authentication failed",
      responseCode: 535,
      command: "AUTH PLAIN",
    });
    expect(await handleSendTask(deps, task())).toBe("blocked");
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "blocked",
      blockedOn: "resend",
      idempotencyKey: null,
      messageDate: null,
    });
  });

  it("checks nothing while no send is blocked on Resend", async () => {
    let verified = false;
    deps.smtp = {
      async verify() {
        verified = true;
      },
      async send() {
        throw new Error("unused");
      },
    };
    expect(await resumeResendSends(deps)).toBe(0);
    expect(verified).toBe(false);
  });

  it("resends a dead attempt's message under its key, within the window", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set(
      "o1",
      waiting("test@hafa.cc", {
        status: "sending",
        via: "resend",
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
        firstAttemptAt: NOW - CLAIM_LEASE_MS - 1,
        inFlightAt: NOW - CLAIM_LEASE_MS - 1,
        messageDate: NOW - CLAIM_LEASE_MS - 1,
        idempotencyKey: "inbox/k",
        messageIdHeader: "<draft1@mail.gmail.com>",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(smtpSent[0]!.message).toContain(`${IDEMPOTENCY_HEADER}: inbox/k`);
  });

  it("never resends a dead attempt's message once the key window has passed", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set(
      "o1",
      waiting("test@hafa.cc", {
        status: "sending",
        via: "resend",
        claimedAt: NOW - CLAIM_LEASE_MS - 1,
        inFlightAt: NOW - RESEND_KEY_WINDOW_MS,
        messageDate: NOW - RESEND_KEY_WINDOW_MS,
        idempotencyKey: "inbox/k",
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("failed");
    expect(outbox.entries.get("o1")?.error?.code).toBe("uncertain");
    expect(smtpSent).toEqual([]);
  });

  it("files the copy that went, not the draft, after filing failed, and deletes the draft at once", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    gmail.insertError = new GmailError(503, "backend error");
    await expect(handleSendTask(deps, task())).rejects.toThrow("backend error");
    expect(smtpSent).toHaveLength(1);
    expect(gmail.drafts.has("d1")).toBe(false);
    expect(outbox.entries.get("o1")).toMatchObject({
      status: "sent",
      filed: false,
    });
    const kept = outbox.copies.get("o1");
    expect(kept?.raw.toString()).toContain("Bcc: secret@example.com");

    // The draft is edited (recreated under its id) before the retry runs.
    gmail.addDraft("d1", "test@hafa.cc");
    gmail.drafts.get("d1")!.message.raw = Buffer.from(
      "From: test@hafa.cc\r\nSubject: Edited\r\n\r\nNew text\r\n",
    ).toString("base64url");
    gmail.insertError = undefined;
    clock += FILING_LEASE_MS;
    expect(await handleSendTask(deps, task())).toBe("filed");
    expect(smtpSent).toHaveLength(1);
    expect(gmail.inserted).toHaveLength(1);
    expect(gmail.inserted[0]!.raw).toBe(kept!.raw.toString("base64url"));
    expect(gmail.drafts.has("d1")).toBe(false);
    expect(outbox.copies.has("o1")).toBe(false);
    expect(outbox.entries.get("o1")).toMatchObject({
      filed: true,
      messageId: "copy1",
    });
  });

  it("doesn't file the copy twice when filing timed out after Gmail took it", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    gmail.insertMessage = async (raw, threadId, labelIds) => {
      gmail.inserted.push({ raw, threadId, labelIds });
      gmail.existingCopy = true;
      throw new GmailError(408, "Gmail took too long to answer", "timeout");
    };
    await expect(handleSendTask(deps, task())).rejects.toThrow("too long");
    expect(gmail.inserted).toHaveLength(1);
    expect(gmail.inserted[0]!.threadId).toBe("t1");

    clock += FILING_LEASE_MS;
    expect(await handleSendTask(deps, task())).toBe("filed");
    expect(smtpSent).toHaveLength(1);
    expect(gmail.inserted).toHaveLength(1);
    expect(gmail.searches.at(-1)).toBe(
      "rfc822msgid:draft1@mail.gmail.com in:sent",
    );
    expect(outbox.entries.get("o1")).toMatchObject({
      filed: true,
      messageId: "copy0",
    });
  });

  it("files a kept copy in a thread of its own when its draft's thread went with the draft", async () => {
    outbox.copies.set("o1", {
      raw: Buffer.from("From: test@hafa.cc\r\n\r\nHi\r\n"),
      draftId: "d1",
      threadId: "t-gone",
      messageIdHeader: "<dev@hafa.cc>",
      savedAt: NOW - 1000,
    });
    let calls = 0;
    gmail.insertMessage = async (raw, threadId, labelIds) => {
      calls++;
      if (threadId !== undefined) throw new GmailError(404, "not found");
      gmail.inserted.push({ raw, threadId, labelIds });
      return { id: "copy2", threadId: "t-new" };
    };
    expect(await resumeFiling(deps, UID)).toBe(1);
    expect(calls).toBe(2);
    expect(gmail.inserted[0]!.threadId).toBeUndefined();
  });

  it("the sweep and sign-in file kept copies, skipping copies not yet due and ones another run holds", async () => {
    outbox.copies.set("old", keptCopy(NOW - SWEEP_MARGIN_MS - 1));
    outbox.copies.set("fresh", keptCopy(NOW - 1000));
    outbox.copies.set("held", {
      ...keptCopy(NOW - SWEEP_MARGIN_MS - 1),
      filingAt: NOW - 1000,
    });
    expect(await resumeFiling(deps)).toBe(1);
    expect([...outbox.copies.keys()].sort()).toEqual(["fresh", "held"]);
    expect(gmail.inserted).toHaveLength(1);

    expect(await resumeFiling(deps, UID)).toBe(1);
    expect([...outbox.copies.keys()]).toEqual(["held"]);
  });

  it("puts off a copy whose owner has no Google access, so it doesn't hold up the sweep", async () => {
    outbox.copies.set("old", keptCopy(NOW - SWEEP_MARGIN_MS - 1));
    grant = false;
    expect(await resumeFiling(deps)).toBe(0);
    expect(outbox.copies.get("old")?.nextAttemptAt).toBeGreaterThan(NOW);
    expect(await outbox.unfiledCopies(NOW)).toEqual([]);
    grant = true;
    expect(await resumeFiling(deps, UID)).toBe(1);
    expect(outbox.copies.size).toBe(0);
  });

  it("deletes a copy whose account is gone, or whose owner stayed without Google access too long", async () => {
    outbox.copies.set("gone", keptCopy(NOW - SWEEP_MARGIN_MS - 1));
    grant = false;
    outbox.accounts.clear();
    await resumeFiling(deps);
    expect(outbox.copies.size).toBe(0);

    outbox.accounts.add(UID);
    outbox.copies.set("stale", keptCopy(NOW - COPY_KEEP_WITHOUT_ACCESS_MS));
    await resumeFiling(deps);
    expect(outbox.copies.size).toBe(0);
    expect(gmail.inserted).toEqual([]);
  });

  it("keeps no copy when the doc went while the message was out", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.entries.set("o1", waiting("test@hafa.cc"));
    const send = deps.smtp!.send;
    deps.smtp!.send = async (envelope, message) => {
      outbox.entries.delete("o1");
      return send(envelope, message);
    };
    expect(await handleSendTask(deps, task())).toBe("sent");
    expect(outbox.copies.size).toBe(0);
    expect(gmail.inserted).toHaveLength(1);
  });

  it("deletes the draft and marks the send filed once the sweep dropped the copy", async () => {
    gmail.addDraft("d1", "test@hafa.cc");
    outbox.handovers.set("d1", {
      idempotencyKey: "key1",
      messageDate: NOW - 6000,
      messageIdHeader: "<draft1@mail.gmail.com>",
      startedAt: NOW - 6000,
    });
    outbox.entries.set(
      "o1",
      waiting("test@hafa.cc", {
        status: "sent",
        via: "resend",
        sentAt: NOW - 5000,
        messageIdHeader: "<draft1@mail.gmail.com>",
        filed: false,
      }),
    );
    expect(await handleSendTask(deps, task())).toBe("stale");
    expect(smtpSent).toEqual([]);
    expect(gmail.inserted).toEqual([]);
    expect(gmail.drafts.has("d1")).toBe(false);
    expect(outbox.entries.get("o1")?.filed).toBe(true);
    expect(outbox.handovers.size).toBe(0);
  });
});

describe("requeueOverdueSends", () => {
  it("queues sends whose task should have run a while ago, under fresh ids", async () => {
    const late = NOW - SWEEP_MARGIN_MS - 1000;
    outbox.entries.set(
      "waiting-late",
      waiting("ana@gmail.example.com", { sendAt: late }),
    );
    outbox.entries.set(
      "waiting-due",
      waiting("ana@gmail.example.com", { sendAt: NOW - 1000 }),
    );
    outbox.entries.set(
      "retrying-late",
      waiting("ana@gmail.example.com", {
        sendAt: late - 60_000,
        status: "retrying",
        attempts: 2,
        nextAttemptAt: late,
      }),
    );
    outbox.entries.set(
      "sent",
      waiting("ana@gmail.example.com", { sendAt: late, status: "sent" }),
    );
    expect(await requeueOverdueSends(deps)).toBe(2);
    expect(queued.map((entry) => entry.task)).toEqual([
      {
        uid: UID,
        outboxId: "waiting-late",
        sendAt: late,
        writtenAt: late,
        hop: 1,
        attempt: 0,
      },
      {
        uid: UID,
        outboxId: "retrying-late",
        sendAt: late - 60_000,
        writtenAt: late - 60_000,
        hop: 1,
        attempt: 2,
      },
    ]);
    for (const entry of queued) {
      expect(entry.id).not.toBe(sendTaskId(entry.task));
      expect(entry.at.getTime()).toBe(NOW);
    }

    gmail.addDraft("d1", "ana@gmail.example.com");
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
  });

  it("takes over a dead claim no task came back for, as a device's that died mid-send", async () => {
    const claimedAt = NOW - CLAIM_LEASE_MS - SWEEP_MARGIN_MS - 1000;
    for (const [id, at] of [
      ["dead", claimedAt],
      ["live", NOW - 1000],
    ] as const) {
      outbox.entries.set(
        id,
        waiting("ana@gmail.example.com", {
          sendAt: claimedAt,
          status: "sending",
          via: "gmail",
          claimedAt: at,
          inFlightAt: at,
          draftMessageId: "m-d1",
          deviceClaim: "c".repeat(24),
        }),
      );
    }
    expect(await requeueOverdueSends(deps)).toBe(1);
    expect(queued[0]?.task).toMatchObject({ outboxId: "dead", attempt: 0 });

    gmail.messages.set("m-d1", {
      id: "m-d1",
      threadId: "t1",
      labelIds: ["SENT"],
    });
    expect(await handleSendTask(deps, queued[0]!.task)).toBe("sent");
    expect(gmail.sentDrafts).toEqual([]);
    expect(outbox.entries.get("dead")).toMatchObject({
      status: "sent",
      deviceClaim: null,
    });
  });
});

describe("handleOutboxWrite", () => {
  const schedule = (sendAt: number, writtenAt: number) => ({
    sendAt,
    writtenAt,
  });

  it("queues a task at sendAt for a new entry", async () => {
    await handleOutboxWrite(deps, UID, "o1", null, {
      status: "waiting",
      waiting: schedule(NOW + 10_000, NOW),
    });
    expect(queued).toHaveLength(1);
    expect(queued[0]?.at.getTime()).toBe(NOW + 10_000);
    expect(queued[0]?.task).toEqual({
      uid: UID,
      outboxId: "o1",
      sendAt: NOW + 10_000,
      writtenAt: NOW,
      hop: 1,
      attempt: 0,
    });
  });

  it("deletes the task on undo", async () => {
    await handleOutboxWrite(
      deps,
      UID,
      "o1",
      { status: "waiting", waiting: schedule(NOW + 10_000, NOW) },
      null,
    );
    expect(removed).toHaveLength(1);
    expect(queued).toEqual([]);
  });

  it("replaces the task on reschedule", async () => {
    await handleOutboxWrite(
      deps,
      UID,
      "o1",
      { status: "waiting", waiting: schedule(NOW + 10_000, NOW - 5) },
      { status: "waiting", waiting: schedule(NOW + 60_000, NOW) },
    );
    expect(removed).toHaveLength(1);
    expect(queued.map((entry) => entry.task.sendAt)).toEqual([NOW + 60_000]);
  });

  it("queues again when a failed entry is retried", async () => {
    await handleOutboxWrite(
      deps,
      UID,
      "o1",
      { status: "failed", waiting: schedule(SEND_AT, NOW - 5) },
      { status: "waiting", waiting: schedule(SEND_AT, NOW) },
    );
    expect(queued).toHaveLength(1);
    expect(queued[0]?.at.getTime()).toBe(NOW);
  });

  it("ignores the server's own writes", async () => {
    const at = schedule(SEND_AT, NOW);
    for (const [before, after] of [
      ["waiting", "sending"],
      ["sending", "retrying"],
      ["retrying", "sending"],
      ["sending", "sent"],
      ["sending", "failed"],
      ["sending", "blocked"],
    ] as const) {
      await handleOutboxWrite(
        deps,
        UID,
        "o1",
        { status: before, waiting: at },
        { status: after, waiting: at },
      );
    }
    expect(queued).toEqual([]);
    expect(removed).toEqual([]);
  });
});

describe("decideClaim", () => {
  const entry = waiting("ana@gmail.example.com");

  it("claims a waiting entry for its own time only", () => {
    expect(decideClaim(entry, SEND_AT, NOW).kind).toBe("claimed");
    expect(decideClaim(entry, SEND_AT + 1, NOW).kind).toBe("stale");
    expect(decideClaim(undefined, SEND_AT, NOW).kind).toBe("stale");
  });

  it("is stale for finished entries", () => {
    expect(decideClaim({ ...entry, status: "failed" }, SEND_AT, NOW).kind).toBe(
      "stale",
    );
    expect(
      decideClaim(
        { ...entry, status: "sent", via: "gmail", filed: true },
        SEND_AT,
        NOW,
      ).kind,
    ).toBe("stale");
  });

  it("claims a retrying entry once its next attempt is due", () => {
    const retrying = {
      ...entry,
      status: "retrying" as const,
      nextAttemptAt: NOW,
    };
    expect(decideClaim(retrying, SEND_AT, NOW - CLAIM_SLACK_MS - 1).kind).toBe(
      "early",
    );
    expect(decideClaim(retrying, SEND_AT, NOW - CLAIM_SLACK_MS).kind).toBe(
      "claimed",
    );
    expect(decideClaim(retrying, SEND_AT, NOW)).toMatchObject({
      kind: "claimed",
      entry: { status: "sending", claimedAt: NOW, firstAttemptAt: NOW },
    });
  });
});

describe("carriedAttempt", () => {
  const unknown = waiting("test@hafa.cc", {
    status: "failed",
    errorCode: "unavailable",
    via: "resend",
    messageIdHeader: "<m@x>",
    uncertainSince: NOW,
    messageDate: NOW,
    idempotencyKey: "k",
  });
  const fresh = waiting("test@hafa.cc");

  it("hands a failed send's unknown handover to its rewrite", () => {
    expect(carriedAttempt(unknown, fresh)).toEqual({
      via: "resend",
      draftMessageId: null,
      messageIdHeader: "<m@x>",
      uncertainSince: NOW,
      messageDate: NOW,
      idempotencyKey: "k",
    });
    expect(
      carriedAttempt({ ...unknown, status: "blocked", errorCode: null }, fresh),
    ).toMatchObject({ idempotencyKey: "k" });
  });

  it("drops the key after uncertain, or when nothing was unknown", () => {
    expect(
      carriedAttempt({ ...unknown, errorCode: "uncertain" }, fresh),
    ).toMatchObject({ idempotencyKey: null, uncertainSince: null });
    expect(
      carriedAttempt({ ...unknown, uncertainSince: null }, fresh),
    ).toMatchObject({ idempotencyKey: null, messageDate: null });
  });

  it("hands nothing to another draft, a doc with its own record, or from a sent one", () => {
    expect(carriedAttempt(unknown, { ...fresh, draftId: "d2" })).toBeNull();
    expect(
      carriedAttempt(unknown, { ...fresh, idempotencyKey: "other" }),
    ).toBeNull();
    expect(carriedAttempt({ ...unknown, status: "sent" }, fresh)).toBeNull();
    expect(carriedAttempt(fresh, fresh)).toBeNull();
    expect(carriedAttempt(undefined, fresh)).toBeNull();
  });
});
