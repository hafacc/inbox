import { afterEach, beforeEach, expect, jest, test } from "bun:test";
import {
  DIRECT_HANDOVER_MAX_MS,
  DIRECT_SEND_TIMEOUT_MS,
} from "@inbox/shared/direct-send";
import { GmailError } from "../gmail/request";
import {
  type DeviceClaim,
  type DirectSendDeps,
  sendDirectly,
} from "./direct-send";

let calls: string[];
let claims: DeviceClaim[];
let clock: number;
let deps: DirectSendDeps;
let sendError: unknown;
let claimTakes: number;
let refusals: string[];

beforeEach(() => {
  calls = [];
  claims = [];
  clock = 1_000;
  sendError = undefined;
  claimTakes = 0;
  refusals = [];
  deps = {
    async readDraft(draftId) {
      calls.push(`read ${draftId}`);
      return {
        id: "m-1",
        headers: [
          { name: "From", value: "Ana <Ana@gmail.example.com>" },
          { name: "Message-ID", value: "<a@mail.gmail.com>" },
        ],
      };
    },
    async claim(claim) {
      calls.push("claim");
      claims.push(claim);
      clock += claimTakes;
      return true;
    },
    async sendDraft(draftId) {
      calls.push(`send ${draftId}`);
      if (sendError !== undefined) throw sendError;
      return { id: "m-1", threadId: "t-1" };
    },
    async recordSent(sent, deviceClaim) {
      calls.push(`sent ${sent.id} ${sent.threadId} ${deviceClaim}`);
    },
    async recordRefused(message, deviceClaim) {
      calls.push(`refused ${deviceClaim}`);
      refusals.push(message);
    },
    newClaimId: () => "claim-0123456789abcdef",
    now: () => clock,
  };
});

const send = { draftId: "d-1", from: "ana@gmail.example.com" };

test("reads the draft, claims with its ids, sends once and records it", async () => {
  expect(await sendDirectly(deps, send)).toBe("sent");
  expect(calls).toEqual([
    "read d-1",
    "claim",
    "send d-1",
    "sent m-1 t-1 claim-0123456789abcdef",
  ]);
  expect(claims).toEqual([
    {
      draftMessageId: "m-1",
      messageIdHeader: "<a@mail.gmail.com>",
      deviceClaim: "claim-0123456789abcdef",
    },
  ]);
});

test("leaves a draft it can't read, or from someone else, to the server unclaimed", async () => {
  deps.readDraft = async () => {
    throw new GmailError(404, "gone");
  };
  expect(await sendDirectly(deps, send)).toBe("server");
  deps.readDraft = async () => ({
    id: "m-1",
    headers: [{ name: "From", value: "bob@example.com" }],
  });
  expect(await sendDirectly(deps, send)).toBe("server");
  expect(calls).toEqual([]);
});

test("doesn't send when the claim is refused or fails", async () => {
  deps.claim = async () => false;
  expect(await sendDirectly(deps, send)).toBe("server");
  deps.claim = async () => {
    throw new Error("offline");
  };
  expect(await sendDirectly(deps, send)).toBe("server");
  expect(calls).toEqual(["read d-1", "read d-1"]);
});

test("leaves a claim that took too long to the server's takeover", async () => {
  claimTakes = DIRECT_HANDOVER_MAX_MS + 1;
  expect(await sendDirectly(deps, send)).toBe("left");
  expect(calls).toEqual(["read d-1", "claim"]);
});

test("records a clean refusal and leaves anything else, never retrying", async () => {
  sendError = new GmailError(400, "Invalid To header", "invalidArgument");
  expect(await sendDirectly(deps, send)).toBe("refused");
  expect(refusals).toEqual(["Invalid To header"]);
  expect(calls.at(-1)).toBe("refused claim-0123456789abcdef");
  for (const error of [
    new GmailError(503, "busy"),
    new GmailError(429, "slow down"),
    new GmailError(404, "gone"),
    new TypeError("fetch failed"),
  ]) {
    calls = [];
    sendError = error;
    expect(await sendDirectly(deps, send)).toBe("left");
    expect(calls).toEqual(["read d-1", "claim", "send d-1"]);
  }
});

afterEach(() => {
  jest.useRealTimers();
});

test("records a send Gmail confirms after the timeout under its claim, never sending again", async () => {
  jest.useFakeTimers();
  let answer: (sent: { id: string; threadId: string }) => void = () => {};
  deps.sendDraft = async (draftId) => {
    calls.push(`send ${draftId}`);
    return new Promise((resolve) => {
      answer = resolve;
    });
  };
  const result = sendDirectly(deps, send);
  // Let the read and the claim settle before the clock moves.
  for (let step = 0; step < 10; step++) await Promise.resolve();
  jest.advanceTimersByTime(DIRECT_SEND_TIMEOUT_MS + 1);
  expect(await result).toBe("left");
  expect(calls).toEqual(["read d-1", "claim", "send d-1"]);
  answer({ id: "m-1", threadId: "t-1" });
  for (let step = 0; step < 10; step++) await Promise.resolve();
  expect(calls).toEqual([
    "read d-1",
    "claim",
    "send d-1",
    "sent m-1 t-1 claim-0123456789abcdef",
  ]);
});

test("records a clean refusal after the timeout and leaves any other late failure", async () => {
  jest.useFakeTimers();
  let fail: (error: unknown) => void = () => {};
  deps.sendDraft = async (draftId) => {
    calls.push(`send ${draftId}`);
    return new Promise((_resolve, reject) => {
      fail = reject;
    });
  };
  for (const [error, recorded] of [
    [new GmailError(400, "Invalid To header", "invalidArgument"), true],
    [new GmailError(503, "busy"), false],
  ] as const) {
    calls = [];
    const result = sendDirectly(deps, send);
    for (let step = 0; step < 10; step++) await Promise.resolve();
    jest.advanceTimersByTime(DIRECT_SEND_TIMEOUT_MS + 1);
    expect(await result).toBe("left");
    fail(error);
    for (let step = 0; step < 10; step++) await Promise.resolve();
    expect(calls).toEqual(
      recorded
        ? ["read d-1", "claim", "send d-1", "refused claim-0123456789abcdef"]
        : ["read d-1", "claim", "send d-1"],
    );
  }
});
