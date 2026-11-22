import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  evictionKeys,
  type OfflinePlan,
  type OfflineQuery,
  type OfflineThread,
  type OfflineTier,
  offlineDays,
  offlinePlan,
  offlineQueries,
  offlineTier,
  pruneKeys,
} from "./offline-set";

type Fixtures = {
  days: { name: string; value: unknown; expect: number }[];
  tier: {
    name: string;
    thread: OfflineThread;
    snoozed: boolean;
    days: number;
    now: number;
    expect: OfflineTier | null;
  }[];
  plan: {
    name: string;
    threads: OfflineThread[];
    snoozed: string[];
    days: number;
    now: number;
    cap: number;
    encryptedCap: number;
    expect: OfflinePlan;
  }[];
  queries: { name: string; days: number; expect: OfflineQuery[] }[];
  eviction: {
    name: string;
    usage: [string, number][];
    cap: number;
    expect: string[];
  }[];
  prune: {
    name: string;
    keys: string[];
    usage: Record<string, number>;
    cap: number;
    expiredPrefix: string | null;
    kept: string[];
    expect: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/offline-set-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("offlineDays", () => {
  for (const { name, value, expect: expected } of fixtures.days) {
    test(name, () => expect(offlineDays(value)).toBe(expected));
  }
});

describe("offlineTier", () => {
  for (const {
    name,
    thread,
    snoozed,
    days,
    now,
    expect: expected,
  } of fixtures.tier) {
    test(name, () =>
      expect(offlineTier(thread, snoozed, days, now)).toBe(expected),
    );
  }
});

describe("offlinePlan", () => {
  for (const {
    name,
    threads,
    snoozed,
    days,
    now,
    cap,
    encryptedCap,
    expect: expected,
  } of fixtures.plan) {
    test(name, () =>
      expect(
        offlinePlan(threads, new Set(snoozed), days, now, cap, encryptedCap),
      ).toEqual(expected),
    );
  }
});

describe("offlineQueries", () => {
  for (const { name, days, expect: expected } of fixtures.queries) {
    test(name, () => expect(offlineQueries(days)).toEqual(expected));
  }
});

describe("evictionKeys", () => {
  for (const { name, usage, cap, expect: expected } of fixtures.eviction) {
    test(name, () => expect(evictionKeys(usage, cap)).toEqual(expected));
  }
});

describe("pruneKeys", () => {
  for (const {
    name,
    keys,
    usage,
    cap,
    expiredPrefix,
    kept,
    expect: expected,
  } of fixtures.prune) {
    test(name, () => {
      const expired = (key: string) =>
        expiredPrefix !== null && key.startsWith(expiredPrefix);
      expect(
        pruneKeys(
          keys,
          new Map(Object.entries(usage)),
          cap,
          expired,
          new Set(kept),
        ),
      ).toEqual(expected);
    });
  }
});
