import { expect, test } from "bun:test";
import { UNDO_SEND_DELAY_MS } from "@inbox/shared/send-suggest";
import {
  autosaveDelay,
  blockedText,
  canSendAgain,
  failureView,
  heldSends,
  isFinished,
  isLocked,
  movedDraftWrite,
  type OutboxDoc,
  outboxDocs,
  outboxForDraft,
  outboxFromData,
  resendWrite,
  retryView,
  SAVE_LEAD_MS,
  waitingWrite,
} from "./outbox";

const stamp = (millis: number) => ({ toMillis: () => millis });

function outbox(id: string, patch: Partial<OutboxDoc>): OutboxDoc {
  return {
    id,
    draftId: `d-${id}`,
    from: "test@hafa.cc",
    status: "waiting",
    sendAt: 1_000,
    chosenAt: null,
    sentAt: null,
    threadId: null,
    via: null,
    filed: null,
    rejected: [],
    error: null,
    nextAttemptAt: null,
    lastError: null,
    blockedOn: null,
    ...patch,
  };
}

test("reads a server-updated doc", () => {
  expect(
    outboxFromData("o1", {
      draftId: "d1",
      from: "test@hafa.cc",
      status: "sent",
      sendAt: stamp(5),
      chosenAt: null,
      sentAt: stamp(6),
      threadId: "t1",
      via: "resend",
      filed: false,
      rejected: ["x@y.example.net", 3],
    }),
  ).toEqual({
    id: "o1",
    draftId: "d1",
    from: "test@hafa.cc",
    status: "sent",
    sendAt: 5,
    chosenAt: null,
    sentAt: 6,
    threadId: "t1",
    via: "resend",
    filed: false,
    rejected: ["x@y.example.net"],
    error: null,
    nextAttemptAt: null,
    lastError: null,
    blockedOn: null,
  });
});

test("reads a retrying doc's next attempt and last error", () => {
  expect(
    outboxFromData("o2", {
      draftId: "d1",
      from: "test@hafa.cc",
      status: "retrying",
      sendAt: stamp(5),
      nextAttemptAt: stamp(9),
      lastError: {
        code: "rate-limited",
        message: "Daily limit.",
        at: stamp(7),
      },
    }),
  ).toMatchObject({
    status: "retrying",
    nextAttemptAt: 9,
    lastError: { code: "rate-limited", message: "Daily limit." },
  });
});

test("reads a blocked doc and says what it waits for", () => {
  const doc = outboxFromData("o3", {
    draftId: "d1",
    from: "test@hafa.cc",
    status: "blocked",
    sendAt: stamp(5),
    blockedOn: "resend",
    lastError: { code: "resend", message: "Not set up." },
  });
  expect(doc).toMatchObject({ status: "blocked", blockedOn: "resend" });
  expect(blockedText(doc!)).toBe("Sending from @hafa.cc isn't set up yet");
  expect(
    blockedText(outbox("b", { status: "blocked", blockedOn: "sign-in" })),
  ).toBe("Waiting for you to sign in again");
  expect(blockedText(outbox("b", { status: "blocked" }))).toBe(
    "Waiting until it can be sent",
  );
  expect(blockedText(outbox("w", {}))).toBeNull();
  expect(canSendAgain(doc!)).toBe(true);
  expect(canSendAgain(outbox("f", { status: "failed" }))).toBe(true);
  expect(canSendAgain(outbox("r", { status: "retrying" }))).toBe(false);
  expect(isLocked(doc)).toBe(false);
});

test("skips docs it can't read", () => {
  expect(outboxFromData("o", { status: "waiting" })).toBeNull();
  expect(
    outboxFromData("o", {
      draftId: "d",
      from: "a@b.c",
      status: "lost",
      sendAt: stamp(1),
    }),
  ).toBeNull();
});

test("failures read as English with the right actions", () => {
  expect(
    failureView({ code: "rejected", message: "Daily sending limit reached." }),
  ).toEqual({
    message: "Daily sending limit reached.",
    canRetry: true,
    canEdit: true,
  });
  expect(failureView({ code: "draft-missing", message: "x" })).toMatchObject({
    canRetry: false,
    canEdit: false,
  });
  expect(failureView({ code: "uncertain", message: "" })).toMatchObject({
    canRetry: true,
  });
  expect(failureView({ code: "uncertain", message: "" }).message).toContain(
    "Check Sent",
  );
  expect(
    failureView({ code: "new-code", message: "Server text" }).message,
  ).toBe("Server text");
});

test("retry waits out the undo delay and drops the suggestion", () => {
  expect(resendWrite(outbox("o", { chosenAt: 5, threadId: "t" }), 100)).toEqual(
    {
      draftId: "d-o",
      from: "test@hafa.cc",
      sendAt: 100 + UNDO_SEND_DELAY_MS,
      chosenAt: null,
      threadId: "t",
    },
  );
});

test("Outbox lists all unsent: failed, blocked, then under way, then waiting by time", () => {
  const docs = [
    outbox("late", { sendAt: 30 }),
    outbox("sent", { status: "sent" }),
    outbox("early", { sendAt: 10 }),
    outbox("failed", { status: "failed", sendAt: 50 }),
    outbox("blocked", { status: "blocked", sendAt: 1 }),
    outbox("going", { status: "sending", sendAt: 4 }),
    outbox("retrying", { status: "retrying", sendAt: 2 }),
    outbox("due", { sendAt: 3 }),
  ];
  expect(outboxDocs(docs, 5).map((entry) => entry.id)).toEqual([
    "failed",
    "blocked",
    "retrying",
    "due",
    "going",
    "early",
    "late",
  ]);
});

test("a retrying send reads as not sent yet, with when and why", () => {
  const next = new Date(2026, 10, 4, 14, 5).getTime();
  const now = new Date(2026, 10, 4, 13, 0).getTime();
  expect(
    retryView(
      outbox("o", {
        status: "retrying",
        nextAttemptAt: next,
        lastError: { code: "network", message: "Couldn't connect to Gmail." },
      }),
      now,
    ),
  ).toEqual({
    headline: "Not sent yet · trying again at 14:05",
    reason: "Couldn't connect to Gmail.",
    mayHaveGone: true,
  });
  expect(
    retryView(
      outbox("o", {
        status: "retrying",
        nextAttemptAt: next,
        lastError: { code: "rate-limited", message: "" },
      }),
      now,
    ),
  ).toMatchObject({ reason: "Sending limit reached." });
  expect(retryView(outbox("o", {}), now)).toBeNull();
});

test("a Resend send is finished only once filed", () => {
  expect(isFinished(outbox("a", { status: "sent", filed: false }))).toBe(false);
  expect(isFinished(outbox("a", { status: "sent", filed: true }))).toBe(true);
  expect(isFinished(outbox("a", { status: "sent", filed: null }))).toBe(true);
  expect(isFinished(outbox("a", { status: "failed" }))).toBe(false);
});

test("a send under way locks its draft", () => {
  expect(isLocked(outbox("a", { status: "sending" }))).toBe(true);
  expect(isLocked(outbox("a", { status: "retrying" }))).toBe(true);
  expect(isLocked(outbox("a", {}))).toBe(false);
  expect(isLocked(null)).toBe(false);
  const docs = [
    outbox("waiting", { draftId: "d1" }),
    outbox("retrying", { draftId: "d1", status: "retrying" }),
  ];
  expect(outboxForDraft(docs, "d1")?.id).toBe("retrying");
});

test("a draft's send is its waiting doc, else its blocked one, else its failed one; sent docs don't count", () => {
  const docs = [
    outbox("sent", { draftId: "d1", status: "sent" }),
    outbox("failed", { draftId: "d1", status: "failed" }),
    outbox("waiting", { draftId: "d1" }),
    outbox("other", { draftId: "d2" }),
  ];
  expect(outboxForDraft(docs, "d1")?.id).toBe("waiting");
  expect(outboxForDraft(docs.slice(0, 2), "d1")?.id).toBe("failed");
  expect(outboxForDraft(docs.slice(0, 1), "d1")).toBeNull();
  expect(outboxForDraft(docs, null)).toBeNull();
  expect(
    outboxForDraft(
      [
        ...docs.slice(0, 2),
        outbox("blocked", { draftId: "d1", status: "blocked" }),
      ],
      "d1",
    )?.id,
  ).toBe("blocked");
});

test("undoing a send now of a scheduled message puts its old time back", () => {
  const doc = outbox("o1", { sendAt: 9_000, chosenAt: 8_000, threadId: "t1" });
  expect(waitingWrite(doc)).toEqual({
    draftId: "d-o1",
    from: "test@hafa.cc",
    sendAt: 9_000,
    chosenAt: 8_000,
    threadId: "t1",
  });
  expect(waitingWrite(outbox("o2", { status: "failed" }))).toBeNull();
});

test("a schedule follows its draft to a new id, keeping its time", () => {
  const doc = outbox("o1", { sendAt: 9_000 });
  expect(movedDraftWrite(doc, "d-new")).toMatchObject({
    draftId: "d-new",
    sendAt: 9_000,
  });
  expect(movedDraftWrite(doc, "d-o1")).toBeNull();
  expect(movedDraftWrite({ ...doc, status: "failed" }, "d-new")).toBeNull();
});

test("an edit to a scheduled message saves in time for the send", () => {
  const now = 100_000;
  expect(autosaveDelay(now, null, 2_000)).toBe(2_000);
  expect(autosaveDelay(now, now + 60_000, 2_000)).toBe(2_000);
  expect(autosaveDelay(now, now + SAVE_LEAD_MS + 500, 2_000)).toBe(500);
  expect(autosaveDelay(now, now + SAVE_LEAD_MS - 1, 2_000)).toBe(0);
  expect(autosaveDelay(now, now - 1, 2_000)).toBe(0);
});

test("lists a send made offline as held until its outbox doc is written or heard of", () => {
  const send = { kind: "now" as const, outboxId: null, queuedAt: 5_000 };
  const job = {
    localId: "c1",
    draftId: null,
    threadId: "t1",
    from: "me@example.com",
    raw: "",
    version: 1,
    send,
  };
  const local = {
    localId: "c1",
    messageId: null,
    state: "sending" as const,
    updatedAt: 5_000,
    threadId: "t1",
    from: "me@example.com",
    to: [{ name: "Maya", email: "maya@example.com" }],
    cc: [{ name: "", email: "sam@example.com" }],
    bcc: [],
    subject: "Lunch",
    snippet: "Noon?",
  };
  const drafts = new Map([["c1", local]]);
  expect(heldSends([job], new Map(), [], drafts)).toEqual([
    {
      localId: "c1",
      from: "me@example.com",
      sendAt: 5_000 + UNDO_SEND_DELAY_MS,
      now: true,
      preview: {
        to: [...local.to, ...local.cc],
        bcc: [],
        subject: "Lunch",
        snippet: "Noon?",
      },
    },
  ]);
  // Its doc written (Firestore queues it offline): the doc's row stands for it.
  expect(heldSends([job], new Map([["c1", send]]), [], drafts)).toEqual([]);
  // A plain draft save isn't a send.
  expect(heldSends([{ ...job, send: null }], new Map(), [], drafts)).toEqual(
    [],
  );
  // A rewrite of a scheduled send whose doc exists shows as that doc.
  const rewrite = {
    ...job,
    send: {
      kind: "at" as const,
      sendAt: 9_000,
      chosenAt: null,
      outboxId: "o1",
    },
  };
  expect(heldSends([rewrite], new Map(), ["o1"], drafts)).toEqual([]);
  expect(heldSends([rewrite], new Map(), [], new Map())).toEqual([
    {
      localId: "c1",
      from: "me@example.com",
      sendAt: 9_000,
      now: false,
      preview: null,
    },
  ]);
});
