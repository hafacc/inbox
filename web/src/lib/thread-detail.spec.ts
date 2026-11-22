import { expect, test } from "bun:test";
import { threadOpenState } from "@inbox/shared/thread-open";
import type { LocalDraft } from "./compose/local-drafts";
import {
  draftIn,
  holdsThread,
  joinDetails,
  relabelDetail,
  savedSummary,
  threadDetail,
  threadItems,
  withAddedMessages,
} from "./thread-detail";
import { summarizeThread } from "./thread-summary";

function encode(text: string): string {
  return Buffer.from(text).toString("base64url");
}

test("an opened thread keeps drafts apart and opens at its first unread message", () => {
  const detail = threadDetail({
    id: "t",
    historyId: "7",
    messages: [
      {
        id: "m1",
        threadId: "t",
        labelIds: ["UNREAD"],
        internalDate: "1000",
        snippet: "Draft attached &amp; signed",
        payload: {
          mimeType: "text/plain",
          headers: [
            { name: "From", value: "Sam Okafor <sam@proton.example.net>" },
            { name: "To", value: "test@hafa.cc" },
            { name: "Subject", value: "Lease paperwork" },
          ],
          body: { size: 5, data: encode("Hello") },
        },
      },
      {
        id: "m2",
        threadId: "t",
        labelIds: ["SENT"],
        internalDate: "2000",
        payload: {
          mimeType: "text/html",
          headers: [{ name: "From", value: "test@hafa.cc" }],
          body: { size: 9, data: encode("<p>Thanks</p>") },
        },
      },
      { id: "m3", threadId: "t", labelIds: ["DRAFT"], internalDate: "3000" },
    ],
  });
  expect(detail.subject).toBe("Lease paperwork");
  expect(detail.messages.map((message) => message.id)).toEqual(["m1", "m2"]);
  expect(detail.drafts.map((message) => message.id)).toEqual(["m3"]);
  expect(detail.messages[0]).toMatchObject({
    from: { name: "Sam Okafor", email: "sam@proton.example.net" },
    to: [{ name: "", email: "test@hafa.cc" }],
    snippet: "Draft attached & signed",
    unread: true,
    body: { text: "Hello", html: null },
  });
  expect(detail.messages[1]!.body.html).toBe("<p>Thanks</p>");
  expect(threadOpenState(detail.messages)).toEqual({
    expanded: ["m1", "m2"],
    scrollTo: "m1",
  });
});

function plain(id: string, date: number, draft = false) {
  return {
    id,
    threadId: "t",
    labelIds: draft ? ["DRAFT"] : ["INBOX"],
    internalDate: String(date),
    snippet: id,
  };
}

function local(
  localId: string,
  messageId: string | null,
  state: LocalDraft["state"],
  threadId = "t",
): LocalDraft {
  return {
    localId,
    messageId,
    state,
    updatedAt: 1,
    threadId,
    from: "test@hafa.cc",
    to: [],
    cc: [],
    bcc: [],
    subject: "",
    snippet: `local ${localId}`,
  };
}

test("a thread shows its drafts in place, the device's copies over Gmail's", () => {
  const detail = threadDetail({
    id: "t",
    messages: [
      plain("m1", 1000),
      plain("d1", 2000, true),
      plain("m2", 3000),
      plain("d2", 4000, true),
      plain("d3", 5000, true),
    ],
  });
  const items = threadItems(detail, [
    local("a", "d2", "draft"),
    local("b", "d3", "deleted"),
    local("c", null, "sending"),
    local("other", null, "draft", "elsewhere"),
  ]);
  expect(
    items.map((item) =>
      item.kind === "local"
        ? `${item.sending ? "sending" : "local"}:${item.draft.localId}`
        : `${item.kind}:${item.message.id}`,
    ),
  ).toEqual(["message:m1", "draft:d1", "message:m2", "local:a", "sending:c"]);
});

test("a sent message Gmail has replaces the one being sent", () => {
  const detail = threadDetail({
    id: "t",
    messages: [plain("m1", 1000), plain("d1", 2000)],
  });
  const items = threadItems(detail, [local("a", "d1", "sending")]);
  expect(items.map((item) => item.kind)).toEqual(["message", "message"]);
});

function encrypted(
  id: string,
  threadId: string,
  date: number,
  messageId: string,
) {
  return {
    id,
    threadId,
    labelIds: ["INBOX"],
    internalDate: String(date),
    payload: {
      mimeType: "text/plain",
      headers: [
        { name: "From", value: "Sam Okafor <sam@proton.example.net>" },
        { name: "Subject", value: "..." },
        { name: "Message-ID", value: messageId },
      ],
      body: { size: 5, data: encode("Hello") },
    },
  };
}

test("an open joined row shows every member's messages by date", () => {
  const detail = joinDetails([
    threadDetail({
      id: "a",
      historyId: "100",
      messages: [
        encrypted("a1", "a", 1000, "<a1@x>"),
        encrypted("a2", "a", 3000, "<a2@x>"),
      ],
    }),
    threadDetail({
      id: "b",
      historyId: "130",
      messages: [encrypted("b1", "b", 2000, "<b1@x>")],
    }),
  ]);
  expect(detail.id).toBe("a");
  expect(detail.historyId).toBe("130");
  expect(detail.messages.map((message) => message.id)).toEqual([
    "a1",
    "b1",
    "a2",
  ]);
  expect(holdsThread(detail, "b")).toBe(true);
  expect(holdsThread(detail, "c")).toBe(false);
});

test("a reply to a joined row goes to the newest message's own thread", () => {
  const detail = joinDetails([
    threadDetail({
      id: "a",
      historyId: "100",
      messages: [encrypted("a1", "a", 1000, "<a1@x>")],
    }),
    threadDetail({
      id: "b",
      historyId: "130",
      messages: [encrypted("b1", "b", 2000, "<b1@x>")],
    }),
  ]);
  const newest = detail.messages.at(-1)!;
  expect(newest.threadId).toBe("b");
  expect(newest.headers?.messageId).toBe("<b1@x>");
  const draft: LocalDraft = {
    localId: "l1",
    messageId: null,
    state: "draft",
    updatedAt: 0,
    threadId: "b",
    from: "test@hafa.cc",
    to: [],
    cc: [],
    bcc: [],
    subject: "",
    snippet: "Sounds good",
  };
  expect(
    threadItems(detail, [draft]).some((item) => item.kind === "local"),
  ).toBe(true);
});

test("drafts saved in any thread of a joined row show when it opens", () => {
  const draft = {
    ...encrypted("b2", "b", 4000, "<b2@x>"),
    labelIds: ["DRAFT"],
  };
  const detail = joinDetails([
    threadDetail({
      id: "a",
      historyId: "100",
      messages: [encrypted("a1", "a", 1000, "<a1@x>")],
    }),
    threadDetail({
      id: "b",
      historyId: "130",
      messages: [encrypted("b1", "b", 2000, "<b1@x>"), draft],
    }),
  ]);
  expect(detail.drafts.map((message) => message.id)).toEqual(["b2"]);
  expect(
    threadItems(detail, []).map((item) => [
      item.kind,
      item.kind === "local" ? item.draft.localId : item.message.id,
    ]),
  ).toEqual([
    ["message", "a1"],
    ["message", "b1"],
    ["draft", "b2"],
  ]);
  const drafts = [
    { id: "d1", message: { threadId: "c" } },
    { id: "d2", message: { threadId: "b" } },
  ];
  expect(draftIn(drafts, ["a", "b"])?.id).toBe("d2");
  expect(draftIn(drafts, ["a"])).toBeUndefined();
});

test("history's labels set a saved thread's unread flags and drop its deleted messages", () => {
  const message = (id: string, threadId: string, labelIds: string[]) => ({
    id,
    threadId,
    labelIds,
    internalDate: "1000",
  });
  const detail = joinDetails([
    threadDetail({
      id: "t",
      historyId: "7",
      messages: [
        message("m1", "t", ["INBOX", "UNREAD"]),
        message("m2", "t", ["INBOX"]),
        message("m3", "t", ["INBOX"]),
      ],
    }),
    threadDetail({
      id: "u",
      historyId: "5",
      messages: [message("n1", "u", ["UNREAD"])],
    }),
  ]);
  const next = relabelDetail(detail, "t", {
    m1: ["INBOX"],
    m2: ["INBOX", "UNREAD"],
  });
  expect(
    next.messages.map((entry) => [entry.id, entry.unread] as const),
  ).toEqual([
    ["m1", false],
    ["m2", true],
    ["n1", true],
  ]);
  expect(
    relabelDetail(detail, "t", {
      m1: ["UNREAD"],
      m2: [],
      m3: ["STARRED"],
    }),
  ).toBe(detail);
});

test("a saved thread with a new message read alone has the row Gmail's whole thread gives", () => {
  const sent = (
    id: string,
    date: number,
    labels: string[],
    from: string,
    snippet: string,
  ) => ({
    id,
    threadId: "t",
    historyId: id === "m3" ? "30" : "10",
    labelIds: labels,
    internalDate: String(date),
    snippet,
    payload: {
      mimeType: "text/plain",
      headers: [
        { name: "From", value: from },
        { name: "Subject", value: "Lease" },
        { name: "Message-ID", value: `<${id}@x>` },
      ],
      body: { size: 2, data: encode("hi") },
    },
  });
  const first = sent("m1", 1000, ["INBOX"], "Sam <sam@x.example.com>", "one");
  const mine = sent("m2", 2000, ["SENT"], "Me <me@x.example.com>", "two");
  const fresh = sent(
    "m3",
    3000,
    ["INBOX", "UNREAD"],
    "Ana <ana@x.example.com>",
    "three",
  );
  const saved = threadDetail({
    id: "t",
    historyId: "10",
    messages: [first, mine],
  });
  const before = summarizeThread({
    id: "t",
    historyId: "10",
    messages: [first, mine],
  });
  const body = withAddedMessages(saved, [fresh]);
  expect(body.messages.map((message) => message.id)).toEqual([
    "m1",
    "m2",
    "m3",
  ]);
  expect(withAddedMessages(body, [fresh])).toBe(body);
  const labels = { m1: ["INBOX"], m2: ["SENT"], m3: ["INBOX", "UNREAD"] };
  expect(savedSummary(body, labels, before, "30")).toEqual(
    summarizeThread({
      id: "t",
      historyId: "30",
      messages: [first, mine, fresh],
    }),
  );
});
