import { expect, test } from "bun:test";
import { onlyInOutbox, outboxDrafts } from "@inbox/shared/outbox-drafts";
import type { GmailMessage } from "./gmail/types";
import {
  isInInbox,
  sendersLabel,
  sentDated,
  summarizeThread,
} from "./thread-summary";

function message(
  id: string,
  from: string,
  date: number,
  labelIds: string[],
  extra: Partial<GmailMessage> = {},
): GmailMessage {
  return {
    id,
    threadId: "t",
    labelIds,
    internalDate: String(date),
    snippet: `snippet ${id}`,
    historyId: id,
    payload: {
      mimeType: "text/plain",
      headers: [
        { name: "From", value: from },
        { name: "Subject", value: "  Dinner Saturday?  " },
      ],
    },
    ...extra,
  };
}

test("summarizes senders, count, date and labels, leaving drafts out", () => {
  const summary = summarizeThread({
    id: "t",
    historyId: "900",
    messages: [
      message("1", "Maya Chen <maya@x.example.com>", 1000, ["INBOX"]),
      message("2", "test@hafa.cc", 2000, ["SENT"]),
      message("3", "maya@x.example.com", 3000, ["INBOX", "UNREAD"], {
        snippet: "Works for me &#8212; 19:30 at Lupa?",
      }),
      message("4", "test@hafa.cc", 4000, ["DRAFT"]),
    ],
  });
  expect(summary).toEqual({
    id: "t",
    historyId: "900",
    subject: "Dinner Saturday?",
    snippet: "Works for me — 19:30 at Lupa?",
    senders: [
      { name: "Maya Chen", email: "maya@x.example.com" },
      { name: "", email: "test@hafa.cc" },
    ],
    messageCount: 3,
    lastDate: 3000,
    sentDate: 2000,
    labelIds: ["DRAFT", "INBOX", "SENT", "UNREAD"],
    unread: true,
    messageIds: [],
    firstDate: 1000,
  });
});

test("a reply of the user's leaves the thread at its latest received message", () => {
  const summary = summarizeThread({
    id: "t",
    messages: [
      message("1", "maya@x.example.com", 1000, ["INBOX"]),
      message("2", "maya@x.example.com", 2000, ["INBOX"]),
      message("3", "test@hafa.cc", 9000, ["SENT"]),
    ],
  });
  expect(summary.lastDate).toBe(2000);
  expect(summary.sentDate).toBe(9000);
  expect(sentDated(summary).lastDate).toBe(9000);
});

test("the newest received message dates the thread whatever Gmail's order", () => {
  const summary = summarizeThread({
    id: "t",
    messages: [
      message("2", "maya@x.example.com", 5000, ["INBOX"]),
      message("1", "maya@x.example.com", 1000, ["INBOX"]),
    ],
  });
  expect(summary.lastDate).toBe(5000);
});

test("names one sender in full and several by first name, the account as me", () => {
  const maya = { name: "Maya Chen", email: "maya@x.example.com" };
  const self = { name: "Jordan Lee", email: "test@hafa.cc" };
  const bot = { name: "", email: "noreply@github.com" };
  expect(sendersLabel([maya], "test@hafa.cc")).toBe("Maya Chen");
  expect(sendersLabel([self], "Test@hafa.cc")).toBe("me");
  expect(sendersLabel([maya, self], "test@hafa.cc")).toBe("Maya, me");
  expect(sendersLabel([bot, maya, self, bot], "test@hafa.cc")).toBe(
    "noreply, Maya, me …",
  );
  expect(sendersLabel([], "test@hafa.cc")).toBe("(no sender)");
});

test("a thread with one message in the inbox and another in spam is not in the inbox", () => {
  const thread = summarizeThread({
    id: "t",
    historyId: "9",
    messages: [
      message("m1", "Ann <ann@example.com>", 1_000, ["SPAM", "UNREAD"]),
      message("m2", "Ann <ann@example.com>", 2_000, ["INBOX"]),
    ],
  });
  expect(thread.labelIds).toContain("INBOX");
  expect(isInInbox(thread)).toBe(false);
  expect(isInInbox({ ...thread, labelIds: ["INBOX"] })).toBe(true);
});

test("carries the Message-IDs and first date, and for the hidden subject the ids it answers", () => {
  const headers = (
    subject: string,
    messageId: string,
    inReplyTo?: string,
    references?: string,
  ) => ({
    mimeType: "text/plain",
    headers: [
      { name: "From", value: "sam@proton.example.net" },
      { name: "Subject", value: subject },
      { name: "Message-ID", value: ` ${messageId} ` },
      ...(inReplyTo ? [{ name: "In-Reply-To", value: inReplyTo }] : []),
      ...(references ? [{ name: "References", value: references }] : []),
    ],
  });
  const plain = summarizeThread({
    id: "a",
    messages: [
      message("1", "sam@proton.example.net", 1000, ["INBOX"], {
        payload: headers("Lease", "<a1@x>"),
      }),
    ],
  });
  expect(plain.messageIds).toEqual(["<a1@x>"]);
  expect(plain.answers).toBeUndefined();
  expect(plain.firstDate).toBe(1000);

  const split = summarizeThread({
    id: "b",
    messages: [
      message("2", "sam@proton.example.net", 2000, ["INBOX"], {
        payload: headers("...", "<b1@x>", "<a2@x>", "<a1@x>\r\n <a2@x>"),
      }),
      message("3", "sam@proton.example.net", 3000, ["INBOX"], {
        payload: headers("...", "<b2@x>", "<b1@x>"),
      }),
    ],
  });
  expect(split.messageIds).toEqual(["<b1@x>", "<b2@x>"]);
  expect(split.answers).toEqual(["<a2@x>", "<a1@x>", "<b1@x>"]);
  expect(split.firstDate).toBe(2000);
});

test("a thread holding only a scheduled send's draft shows only in the Outbox", () => {
  const outbox = outboxDrafts(
    [
      { draftId: "r1", messageId: "1", threadId: "t" },
      { draftId: "r2", messageId: "2", threadId: "u" },
    ],
    [
      { draftId: "r1", status: "waiting" },
      { draftId: "r2", status: "waiting" },
    ],
  );
  const scheduled = summarizeThread({
    id: "t",
    messages: [message("1", "test@hafa.cc", 1000, ["DRAFT"])],
  });
  const reply = summarizeThread({
    id: "u",
    messages: [
      message("3", "maya@x.example.com", 500, ["INBOX"]),
      message("2", "test@hafa.cc", 1000, ["DRAFT"]),
    ],
  });
  expect(scheduled.messageCount).toBe(0);
  expect(onlyInOutbox("t", scheduled.messageCount, outbox)).toBe(true);
  expect(onlyInOutbox("u", reply.messageCount, outbox)).toBe(false);
});
