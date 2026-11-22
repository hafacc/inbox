import { expect, test } from "bun:test";
import { touchesDrafts } from "./draft-history";
import type { GmailHistoryRecord } from "./types";

const message = (labelIds?: string[]) => ({
  message: { id: "m1", threadId: "t1", labelIds },
});

test("a draft saved or deleted touches drafts", () => {
  expect(
    touchesDrafts([{ id: "1", messagesAdded: [message(["DRAFT"])] }]),
  ).toBe(true);
  expect(
    touchesDrafts([{ id: "1", messagesDeleted: [message(["DRAFT"])] }]),
  ).toBe(true);
});

test("a deleted message without its labels may have been a draft", () => {
  expect(touchesDrafts([{ id: "1", messagesDeleted: [message()] }])).toBe(true);
});

test("the draft label added or removed touches drafts", () => {
  const records: GmailHistoryRecord[] = [
    {
      id: "1",
      labelsRemoved: [{ ...message(["INBOX"]), labelIds: ["DRAFT"] }],
    },
  ];
  expect(touchesDrafts(records)).toBe(true);
});

test("mail and label changes off drafts don't touch drafts", () => {
  expect(
    touchesDrafts([
      {
        id: "1",
        messagesAdded: [message(["INBOX", "UNREAD"])],
        messagesDeleted: [message(["TRASH"])],
        labelsAdded: [{ ...message(["DRAFT"]), labelIds: ["STARRED"] }],
      },
    ]),
  ).toBe(false);
  expect(touchesDrafts([])).toBe(false);
});
