import { describe, expect, it } from "bun:test";
import type { GmailHistoryRecord, GmailMessage } from "./gmail-api.js";
import {
  analyzeHistory,
  analyzeRecent,
  compareHistoryIds,
  isNotifiable,
  parseGmailPush,
} from "./history.js";

function added(
  id: string,
  messageId: string,
  threadId: string,
  labelIds: string[],
): GmailHistoryRecord {
  return {
    id,
    messagesAdded: [{ message: { id: messageId, threadId, labelIds } }],
  };
}

describe("parseGmailPush", () => {
  it("reads the address and history id", () => {
    expect(
      parseGmailPush({
        emailAddress: "Ana@Gmail.example.com",
        historyId: 12345,
      }),
    ).toEqual({ emailAddress: "ana@gmail.example.com", historyId: "12345" });
    expect(
      parseGmailPush({ emailAddress: "a@b.c", historyId: "98765432101234567" }),
    ).toEqual({ emailAddress: "a@b.c", historyId: "98765432101234567" });
  });

  it("rejects malformed bodies", () => {
    expect(parseGmailPush(undefined)).toBeUndefined();
    expect(parseGmailPush({ emailAddress: "a@b.c" })).toBeUndefined();
    expect(
      parseGmailPush({ emailAddress: "", historyId: "1" }),
    ).toBeUndefined();
    expect(
      parseGmailPush({ emailAddress: "a@b.c", historyId: "12x" }),
    ).toBeUndefined();
  });
});

describe("compareHistoryIds", () => {
  it("compares past double precision", () => {
    expect(compareHistoryIds("9007199254740993", "9007199254740992")).toBe(1);
    expect(compareHistoryIds("2", "10")).toBe(-1);
    expect(compareHistoryIds("7", "7")).toBe(0);
  });
});

describe("label rules", () => {
  it("notifies only inbox mail from others", () => {
    expect(isNotifiable(["INBOX", "UNREAD"])).toBe(true);
    expect(isNotifiable(["UNREAD"])).toBe(false);
    expect(isNotifiable(["INBOX", "SENT"])).toBe(false);
    expect(isNotifiable(["INBOX", "DRAFT"])).toBe(false);
    expect(isNotifiable(["INBOX", "SPAM"])).toBe(false);
  });
});

describe("analyzeHistory", () => {
  it("notifies new inbox mail once per message", () => {
    const records = [
      added("101", "m1", "t1", ["INBOX", "UNREAD"]),
      added("102", "m1", "t1", ["INBOX", "UNREAD"]),
      added("103", "m2", "t2", ["CATEGORY_UPDATES"]),
      added("104", "m3", "t3", ["SENT"]),
    ];
    expect(analyzeHistory(records)).toEqual({
      notifyMessageIds: ["m1"],
      clearedThreadIds: [],
    });
  });

  it("clears threads with a message read or taken out of the inbox", () => {
    function removed(id: string, threadId: string, labelIds: string[]) {
      return {
        id,
        labelsRemoved: [{ message: { id: `m-${id}`, threadId }, labelIds }],
      };
    }
    const records: GmailHistoryRecord[] = [
      removed("101", "t1", ["UNREAD"]),
      removed("102", "t2", ["INBOX"]),
      removed("103", "t3", ["STARRED"]),
      removed("104", "t1", ["UNREAD"]),
      removed("105", "t4", ["UNREAD"]),
      added("106", "m4", "t4", ["INBOX", "UNREAD"]),
    ];
    expect(analyzeHistory(records)).toEqual({
      notifyMessageIds: ["m4"],
      clearedThreadIds: ["t1", "t2"],
    });
  });

  it("doesn't notify about mail that gained INBOX after it arrived", () => {
    const records: GmailHistoryRecord[] = [
      added("101", "m1", "t1", ["INBOX", "UNREAD"]),
      {
        id: "102",
        labelsAdded: [
          {
            message: {
              id: "m1",
              threadId: "t1",
              labelIds: ["INBOX", "UNREAD"],
            },
            labelIds: ["INBOX"],
          },
        ],
      },
    ];
    expect(analyzeHistory(records).notifyMessageIds).toEqual([]);
  });
});

describe("analyzeRecent", () => {
  function message(
    id: string,
    threadId: string,
    internalDate: number,
    labelIds: string[],
  ): GmailMessage {
    return { id, threadId, labelIds, internalDate: String(internalDate) };
  }

  it("notifies only what arrived since the last sync", () => {
    const messages = [
      message("m1", "t1", 1_000, ["INBOX"]),
      message("m2", "t2", 3_000, ["INBOX"]),
      message("m3", "t3", 4_000, ["INBOX", "SENT"]),
    ];
    expect(analyzeRecent(messages, 2_000).notifyMessageIds).toEqual(["m2"]);
    expect(analyzeRecent(messages, undefined).notifyMessageIds).toEqual([]);
  });

  it("lists them oldest first, as Gmail's search gives them newest first", () => {
    const messages = [
      message("m3", "t3", 4_000, ["INBOX"]),
      message("m2", "t2", 3_000, ["INBOX"]),
    ];
    expect(analyzeRecent(messages, 2_000).notifyMessageIds).toEqual([
      "m2",
      "m3",
    ]);
  });
});
