import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  compareHistoryIds,
  inLapseWindow,
  lowestHistoryId,
  recentlyLapsed,
  type ScanStart,
  scanStart,
  stillFound,
} from "./wake";

type Fixtures = {
  window: {
    name: string;
    snoozeUntil: number | null;
    now: number;
    inWindow: boolean;
    recentlyLapsed: boolean;
  }[];
  start: {
    name: string;
    watermarks: Record<string, string>;
    unfinishedStart: string | null;
    expect: ScanStart | null;
  }[];
  still: {
    name: string;
    found: string[];
    scanned: Record<string, string>;
    current: Record<string, string>;
    expect: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/wake-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

const map = (record: Record<string, string>) => new Map(Object.entries(record));

describe("lapse window", () => {
  for (const { name, snoozeUntil, now, ...expected } of fixtures.window) {
    test(name, () => {
      expect(inLapseWindow(snoozeUntil, now)).toBe(expected.inWindow);
      expect(recentlyLapsed(snoozeUntil, now)).toBe(expected.recentlyLapsed);
    });
  }
});

describe("scanStart", () => {
  for (const {
    name,
    watermarks,
    unfinishedStart,
    expect: expected,
  } of fixtures.start) {
    test(name, () => {
      expect(scanStart(map(watermarks), unfinishedStart)).toEqual(expected);
    });
  }
});

describe("stillFound", () => {
  for (const {
    name,
    found,
    scanned,
    current,
    expect: expected,
  } of fixtures.still) {
    test(name, () => {
      expect([
        ...stillFound(new Set(found), map(scanned), map(current)),
      ]).toEqual(expected);
    });
  }
});

test("history ids compare as big integers, not strings or doubles", () => {
  expect(compareHistoryIds("9", "10")).toBe(-1);
  expect(compareHistoryIds("9007199254740993", "9007199254740992")).toBe(1);
  expect(compareHistoryIds("42", "42")).toBe(0);
  expect(lowestHistoryId(["100", "99", "1000"])).toBe("99");
  expect(lowestHistoryId([])).toBeNull();
});
