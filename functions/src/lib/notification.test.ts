import { describe, expect, it } from "bun:test";
import type { MulticastMessage } from "firebase-admin/messaging";
import { newActionToken } from "./action-token.js";
import type { GmailMessage } from "./gmail-api.js";
import {
  clearData,
  fcmMessage,
  MAX_CLEARED_THREADS,
  notificationData,
  parseSender,
  staleTokens,
} from "./notification.js";

// FCM's documented limits: https://firebase.google.com/docs/cloud-messaging/concept-options
const FCM_DATA_LIMIT_BYTES = 4096;
const FCM_MAX_TTL_MS = 28 * 24 * 60 * 60 * 1000;
const RESERVED_KEYS: ReadonlySet<string> = new Set([
  "from",
  "notification",
  "message_type",
  "collapse_key",
]);
const URGENCIES: ReadonlySet<string> = new Set([
  "very-low",
  "low",
  "normal",
  "high",
]);

/** What FCM would refuse in a multicast message, as readable problems; empty when it is valid. */
function fcmProblems(message: MulticastMessage): string[] {
  const problems: string[] = [];
  const data: Record<string, unknown> = message.data ?? {};
  let bytes = 0;
  for (const [key, value] of Object.entries(data)) {
    const lower = key.toLowerCase();
    if (
      RESERVED_KEYS.has(lower) ||
      lower.startsWith("google") ||
      lower.startsWith("gcm")
    ) {
      problems.push(`reserved data key ${key}`);
    }
    if (typeof value !== "string") {
      problems.push(`non-string data value at ${key}`);
    } else {
      bytes += Buffer.byteLength(key) + Buffer.byteLength(value);
    }
  }
  if (bytes > FCM_DATA_LIMIT_BYTES) {
    problems.push(`data is ${bytes} bytes`);
  }
  if (message.tokens.length === 0 || message.tokens.length > 500) {
    problems.push(`${message.tokens.length} tokens`);
  }
  const { android, webpush } = message;
  if (android?.priority !== undefined) {
    if (!["high", "normal"].includes(android.priority)) {
      problems.push(`android priority ${android.priority}`);
    }
  }
  if (android?.ttl !== undefined) {
    if (
      !Number.isInteger(android.ttl) ||
      android.ttl < 0 ||
      android.ttl > FCM_MAX_TTL_MS
    ) {
      problems.push(`android ttl ${android.ttl}`);
    }
  }
  const headers = webpush?.headers ?? {};
  if (headers.Urgency !== undefined && !URGENCIES.has(headers.Urgency)) {
    problems.push(`webpush urgency ${headers.Urgency}`);
  }
  if (headers.TTL !== undefined && !/^[0-9]+$/.test(headers.TTL)) {
    problems.push(`webpush TTL ${headers.TTL}`);
  }
  if (message.notification !== undefined || android?.notification) {
    problems.push("notification payload: clients build their own");
  }
  return problems;
}

describe("parseSender", () => {
  it("splits name and address", () => {
    expect(parseSender('"Ana Lima" <ana@example.com>')).toEqual({
      name: "Ana Lima",
      address: "ana@example.com",
    });
    expect(parseSender("Bo <bo@example.com>")).toEqual({
      name: "Bo",
      address: "bo@example.com",
    });
  });

  it("falls back to the address", () => {
    expect(parseSender("<x@example.com>")).toEqual({
      name: "x@example.com",
      address: "x@example.com",
    });
    expect(parseSender("plain@example.com")).toEqual({
      name: "plain@example.com",
      address: "plain@example.com",
    });
    expect(parseSender(undefined)).toEqual({ name: "", address: "" });
  });
});

describe("notificationData", () => {
  it("builds string fields from the message", () => {
    const message: GmailMessage = {
      id: "m1",
      threadId: "t1",
      snippet: "Tom &amp; Jerry&#39;s plan",
      internalDate: "1700000000000",
      payload: {
        headers: [
          { name: "from", value: "Ana <ana@example.com>" },
          { name: "Subject", value: "Lunch?" },
        ],
      },
    };
    expect(notificationData(message, "me@gmail.example.com", "tok")).toEqual({
      kind: "message",
      threadId: "t1",
      messageId: "m1",
      sender: "Ana",
      senderAddress: "ana@example.com",
      subject: "Lunch?",
      snippet: "Tom & Jerry's plan",
      encrypted: "false",
      account: "me@gmail.example.com",
      actionToken: "tok",
      sentAt: "1700000000000",
    });
  });

  it("leaves out the subject and text of PGP/MIME mail", () => {
    const message: GmailMessage = {
      id: "m2",
      threadId: "t2",
      snippet: "This is an OpenPGP/MIME encrypted message",
      internalDate: "1700000000000",
      payload: {
        mimeType: "multipart/encrypted",
        headers: [
          { name: "From", value: "Ana <ana@proton.example.net>" },
          { name: "Subject", value: "..." },
          {
            name: "Content-Type",
            value:
              'multipart/encrypted; protocol="application/pgp-encrypted"; boundary="x"',
          },
        ],
      },
    };
    expect(notificationData(message, "user@hafa.cc", "tok")).toEqual({
      kind: "message",
      threadId: "t2",
      messageId: "m2",
      sender: "Ana",
      senderAddress: "ana@proton.example.net",
      subject: "",
      snippet: "",
      encrypted: "true",
      account: "user@hafa.cc",
      actionToken: "tok",
      sentAt: "1700000000000",
    });
  });

  it("treats an inline PGP block as encrypted", () => {
    const data = notificationData(
      {
        id: "m",
        threadId: "t",
        snippet: "-----BEGIN PGP MESSAGE----- wcBMA…",
        payload: { headers: [{ name: "Subject", value: "Secret plans" }] },
      },
      "me@gmail.example.com",
      "tok",
    );
    expect(data).toMatchObject({ encrypted: "true", subject: "", snippet: "" });
  });

  it("doesn't take S/MIME or signed mail for OpenPGP-encrypted", () => {
    for (const value of [
      'multipart/signed; protocol="application/pgp-signature"; boundary=x',
      "application/pkcs7-mime; smime-type=enveloped-data",
      'multipart/encrypted; protocol="application/pkcs7-mime"',
    ]) {
      const data = notificationData(
        {
          id: "m",
          threadId: "t",
          snippet: "hi",
          payload: {
            headers: [
              { name: "Subject", value: "S" },
              { name: "Content-Type", value },
            ],
          },
        },
        "me@gmail.example.com",
        "tok",
      );
      expect(data).toMatchObject({ encrypted: "false", subject: "S" });
    }
  });

  it("clips long snippets", () => {
    const data = notificationData(
      { id: "m", threadId: "t", snippet: "x".repeat(1000) },
      "me@gmail.example.com",
      "tok",
    );
    expect(data.snippet.length).toBe(240);
    expect(data.snippet.endsWith("…")).toBe(true);
  });
});

describe("staleTokens", () => {
  it("picks tokens FCM says are gone", () => {
    expect(
      staleTokens(
        ["a", "b", "c", "d"],
        [
          { success: true },
          {
            success: false,
            error: { code: "messaging/registration-token-not-registered" },
          },
          { success: false, error: { code: "messaging/internal-error" } },
          {
            success: false,
            error: { code: "messaging/invalid-registration-token" },
          },
        ],
      ),
    ).toEqual(["b", "d"]);
  });
});

describe("clearData", () => {
  it("names the threads, at most MAX_CLEARED_THREADS a push", () => {
    expect(clearData(["t1", "t2"], "me@gmail.example.com")).toEqual([
      { kind: "clear", account: "me@gmail.example.com", threadIds: "t1,t2" },
    ]);
    const many = Array.from(
      { length: MAX_CLEARED_THREADS + 1 },
      (_, index) => `t${index}`,
    );
    const payloads = clearData(many, "me@gmail.example.com");
    expect(payloads.map((entry) => entry.threadIds.split(",").length)).toEqual([
      MAX_CLEARED_THREADS,
      1,
    ]);
    expect(clearData([], "me@gmail.example.com")).toEqual([]);
  });
});

describe("fcmMessage", () => {
  const tokens = ["a".repeat(163), "b".repeat(163)];

  it("sends the longest new-mail data FCM takes", () => {
    const wide = "語";
    const message: GmailMessage = {
      id: "18c2f0a1b2c3d4e5",
      threadId: "18c2f0a1b2c3d4e5",
      snippet: wide.repeat(1000),
      internalDate: "1700000000000",
      payload: {
        headers: [
          {
            name: "From",
            value: `"${wide.repeat(1000)}" <${"x".repeat(240)}@${wide.repeat(500)}>`,
          },
          { name: "Subject", value: wide.repeat(1000) },
        ],
      },
    };
    const { token } = newActionToken(`google:${"1".repeat(21)}`);
    const data = notificationData(message, "m".repeat(254), token);
    for (const priority of ["high", "normal"] as const) {
      expect(fcmProblems(fcmMessage(tokens, { ...data }, priority))).toEqual(
        [],
      );
    }
  });

  it("sends the largest clear push FCM takes", () => {
    const threadIds = Array.from({ length: MAX_CLEARED_THREADS }, (_, index) =>
      index.toString(16).padStart(16, "f"),
    );
    for (const data of clearData(threadIds, "m".repeat(254))) {
      expect(fcmProblems(fcmMessage(tokens, { ...data }, "normal"))).toEqual(
        [],
      );
    }
  });

  it("sends the snooze ping FCM takes", () => {
    expect(fcmProblems(fcmMessage(tokens, { kind: "snooze" }, "high"))).toEqual(
      [],
    );
  });

  it("would catch a reserved key", () => {
    expect(
      fcmProblems(fcmMessage(tokens, { kind: "message", from: "Ana" }, "high")),
    ).toEqual(["reserved data key from"]);
  });
});
