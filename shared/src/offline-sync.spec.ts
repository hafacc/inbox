import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  afterHistory,
  dropsUnlisted,
  type HistoryRead,
  type SyncMode,
  type SyncRun,
  type SyncState,
  syncAfter,
  syncMode,
} from "./offline-sync";

type Fixtures = {
  mode: {
    name: string;
    historyId: string | null;
    filled: boolean;
    expect: SyncMode;
  }[];
  history: {
    name: string;
    read: HistoryRead;
    expect: "apply" | "rebuild" | "stop";
  }[];
  after: { name: string; before: SyncState; run: SyncRun; expect: SyncState }[];
  dropsUnlisted: {
    name: string;
    run: Pick<SyncRun, "mode" | "listed">;
    expect: boolean;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/offline-sync-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("syncMode", () => {
  for (const { name, historyId, filled, expect: expected } of fixtures.mode) {
    test(name, () => expect(syncMode(historyId, filled)).toBe(expected));
  }
});

describe("afterHistory", () => {
  for (const { name, read, expect: expected } of fixtures.history) {
    test(name, () => expect(afterHistory(read)).toBe(expected));
  }
});

describe("syncAfter", () => {
  for (const { name, before, run, expect: expected } of fixtures.after) {
    test(name, () => expect(syncAfter(before, run)).toEqual(expected));
  }
});

describe("dropsUnlisted", () => {
  for (const { name, run, expect: expected } of fixtures.dropsUnlisted) {
    test(name, () => expect(dropsUnlisted(run)).toBe(expected));
  }
});
