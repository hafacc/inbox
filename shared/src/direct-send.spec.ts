import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  CLIENT_OUTBOX_FIELDS,
  DIRECT_HANDOVER_MAX_MS,
  DIRECT_SEND_LATE_MS,
  DIRECT_SEND_TIMEOUT_MS,
  type DirectSendInput,
  directSendDecision,
  directSendFailure,
  draftSender,
  type GmailFailure,
  type HeaderLine,
  isGmailRoute,
  type SendAsAddress,
} from "./direct-send";

type Fixtures = {
  lateMs: number;
  handoverMaxMs: number;
  sendTimeoutMs: number;
  clientFields: string[];
  decision: { name: string; input: DirectSendInput; expect: string }[];
  resendDomain: string;
  sendAs: SendAsAddress[];
  route: { name: string; from: string; expect: "send" | "server" }[];
  sender: { name: string; headers: HeaderLine[]; expect: string | null }[];
  failure: { name: string; error: GmailFailure; expect: string }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/direct-send-fixtures.json", import.meta.url),
    "utf8",
  ),
);

test("the limits and client fields read the same on both apps", () => {
  expect(DIRECT_SEND_LATE_MS).toBe(fixtures.lateMs);
  expect(DIRECT_HANDOVER_MAX_MS).toBe(fixtures.handoverMaxMs);
  expect(DIRECT_SEND_TIMEOUT_MS).toBe(fixtures.sendTimeoutMs);
  expect([...CLIENT_OUTBOX_FIELDS]).toEqual(fixtures.clientFields);
});

describe("directSendDecision (must match Android)", () => {
  for (const fixture of fixtures.decision) {
    test(fixture.name, () => {
      expect(directSendDecision(fixture.input)).toBe(
        fixture.expect as ReturnType<typeof directSendDecision>,
      );
    });
  }
});

describe("isGmailRoute (must match Android)", () => {
  for (const fixture of fixtures.route) {
    test(fixture.name, () => {
      expect(
        isGmailRoute(fixture.from, fixtures.sendAs, fixtures.resendDomain),
      ).toBe(fixture.expect === "send");
    });
  }
});

describe("draftSender (must match Android)", () => {
  for (const fixture of fixtures.sender) {
    test(fixture.name, () => {
      expect(draftSender(fixture.headers)).toBe(fixture.expect);
    });
  }
});

describe("directSendFailure (must match Android)", () => {
  for (const fixture of fixtures.failure) {
    test(fixture.name, () => {
      expect(directSendFailure(fixture.error)).toBe(
        fixture.expect as ReturnType<typeof directSendFailure>,
      );
    });
  }
});
