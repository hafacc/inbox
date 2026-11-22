import { describe, expect, it } from "bun:test";
import {
  clickPlan,
  functionUrl,
  notificationContent,
  type PushPayload,
  parsePush,
  threadUrl,
} from "./push";

const SCOPE = "https://inbox.hafa.cc/";

const PAYLOAD: PushPayload = {
  kind: "message",
  threadId: "t1",
  messageId: "m1",
  sender: "Ana",
  senderAddress: "ana@example.com",
  subject: "Lunch?",
  snippet: "Are you free on Friday",
  account: "me@gmail.example.com",
  actionToken: "token",
  sentAt: "1800000000000",
  encrypted: false,
};

describe("parsePush", () => {
  it("reads FCM's data envelope", () => {
    expect(parsePush({ data: PAYLOAD, from: "123" })).toEqual(PAYLOAD);
  });

  it("fills missing fields with empty text", () => {
    expect(
      parsePush({ data: { kind: "message", threadId: "t" } }),
    ).toMatchObject({
      kind: "message",
      threadId: "t",
      subject: "",
      actionToken: "",
    });
  });

  it("ignores pushes that aren't ours", () => {
    expect(parsePush(null)).toBeNull();
    expect(parsePush({ data: { kind: "other", threadId: "t" } })).toBeNull();
    expect(parsePush({ data: { kind: "message" } })).toBeNull();
  });
});

describe("encrypted pushes", () => {
  it("reads the encrypted flag", () => {
    expect(
      parsePush({ data: { ...PAYLOAD, subject: "", encrypted: "true" } })
        ?.encrypted,
    ).toBe(true);
    expect(
      parsePush({ data: { ...PAYLOAD, encrypted: "false" } })?.encrypted,
    ).toBe(false);
  });

  it("says who sent it when it couldn't be decrypted", () => {
    const content = notificationContent(
      { ...PAYLOAD, subject: "", snippet: "", encrypted: true },
      2,
      SCOPE,
    );
    expect(content.title).toBe("Ana");
    expect(content.options.body).toBe("Encrypted message from Ana");
  });

  it("shows the decrypted subject and text like any message", () => {
    const content = notificationContent(
      { ...PAYLOAD, encrypted: true },
      2,
      SCOPE,
    );
    expect(content.options.body).toBe("Lunch?\nAre you free on Friday");
  });
});

describe("notificationContent", () => {
  it("shows the sender and the message", () => {
    const content = notificationContent(PAYLOAD, 3, SCOPE);
    expect(content.options.icon).toBe("https://inbox.hafa.cc/icon-192.png");
    expect(content.options.badge).toBe("https://inbox.hafa.cc/badge-96.png");
    expect(content.title).toBe("Ana");
    expect(content.options.body).toBe("Lunch?\nAre you free on Friday");
    expect(content.options.tag).toBe("thread:t1");
    expect(content.options.timestamp).toBe(1_800_000_000_000);
    expect(content.options.actions).toEqual([
      { action: "done", title: "Done" },
      { action: "reply", title: "Reply" },
    ]);
  });

  it("keeps only the buttons the platform shows", () => {
    expect(
      notificationContent(PAYLOAD, 2, SCOPE).options.actions.map(
        (entry) => entry.action,
      ),
    ).toEqual(["done", "reply"]);
  });

  it("names a sender without a display name by address", () => {
    expect(
      notificationContent({ ...PAYLOAD, sender: "" }, 2, SCOPE).title,
    ).toBe("ana@example.com");
  });
});

describe("clickPlan", () => {
  it("sends done to the server", () => {
    expect(clickPlan("done", PAYLOAD)).toEqual({
      kind: "call",
      threadId: "t1",
      body: { data: { token: "token", action: "done" } },
    });
  });

  it("opens the thread for reply, snooze, the body, or a missing token", () => {
    expect(clickPlan("reply", PAYLOAD)).toEqual({
      kind: "open",
      threadId: "t1",
    });
    expect(clickPlan("snooze", PAYLOAD).kind).toBe("open");
    expect(clickPlan("", PAYLOAD).kind).toBe("open");
    expect(clickPlan("done", { ...PAYLOAD, actionToken: "" }).kind).toBe(
      "open",
    );
  });
});

describe("urls", () => {
  it("calls the emulator locally and Cloud Functions otherwise", () => {
    expect(functionUrl("notificationAction", "localhost")).toBe(
      "http://localhost:5001/hafa-inbox/us-central1/notificationAction",
    );
    expect(functionUrl("notificationAction", "inbox.hafa.cc")).toBe(
      "https://us-central1-hafa-inbox.cloudfunctions.net/notificationAction",
    );
  });

  it("opens a thread through a query parameter", () => {
    expect(threadUrl("https://inbox.hafa.cc/", "t 1")).toBe(
      "https://inbox.hafa.cc/?thread=t+1",
    );
  });
});
