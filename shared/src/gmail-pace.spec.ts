import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  backoffMs,
  type ConnectionEvent,
  isUsageLimit,
  nextCall,
  offlineAfter,
  type PaceState,
  retriesAfterLimit,
  savingHeldUntil,
  type WaitingCall,
} from "./gmail-pace";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/gmail-pace-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  usageLimit: { status: number; reason: string | null; limited: boolean }[];
  backoff: {
    name: string;
    strike: number;
    retryAfter: number | null;
    jitter: number;
    ms: number;
  }[];
  retry: { isRead: boolean; attempt: number; retry: boolean }[];
  offline: {
    name: string;
    offline: boolean;
    event: ConnectionEvent;
    expect: boolean;
  }[];
  savingHold: { pausedUntil: number; heldUntil: number }[];
  next: {
    name: string;
    waiting: WaitingCall[];
    state: PaceState;
    now: number;
    id: string | null;
    wakeAt: number | null;
  }[];
};

describe("gmail pace fixtures", () => {
  for (const { status, reason, limited } of fixtures.usageLimit) {
    test(`usage limit: ${status} ${reason}`, () => {
      expect(isUsageLimit(status, reason)).toBe(limited);
    });
  }
  for (const fixture of fixtures.backoff) {
    test(`backoff: ${fixture.name}`, () => {
      expect(
        backoffMs(fixture.strike, fixture.retryAfter, fixture.jitter),
      ).toBe(fixture.ms);
    });
  }
  for (const { isRead, attempt, retry } of fixtures.retry) {
    test(`retry: ${isRead ? "read" : "write"} after try ${attempt}`, () => {
      expect(retriesAfterLimit(isRead, attempt)).toBe(retry);
    });
  }
  for (const { pausedUntil, heldUntil } of fixtures.savingHold) {
    test(`saving hold after a pause to ${pausedUntil}`, () => {
      expect(savingHeldUntil(pausedUntil)).toBe(heldUntil);
    });
  }
  for (const fixture of fixtures.next) {
    test(`next: ${fixture.name}`, () => {
      expect(nextCall(fixture.waiting, fixture.state, fixture.now)).toEqual({
        id: fixture.id,
        wakeAt: fixture.wakeAt,
      });
    });
  }
});

describe("offlineAfter", () => {
  for (const fixture of fixtures.offline) {
    test(fixture.name, () => {
      expect(offlineAfter(fixture.offline, fixture.event)).toBe(fixture.expect);
    });
  }
});
