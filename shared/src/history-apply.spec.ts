import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  applyHistory,
  type HistoryChanges,
  type HistoryRecord,
  readOrder,
  type SavedMessages,
  threadLabels,
} from "./history-apply";

type Fixtures = {
  apply: {
    name: string;
    saved: Record<string, SavedMessages>;
    days: number;
    records: HistoryRecord[];
    expect: HistoryChanges;
  }[];
  order: {
    name: string;
    records: HistoryRecord[];
    fetch: string[];
    expect: string[];
  }[];
  labels: { name: string; messages: SavedMessages; expect: string[] }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/history-apply-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("applyHistory", () => {
  for (const {
    name,
    saved,
    days,
    records,
    expect: expected,
  } of fixtures.apply) {
    test(name, () => {
      const before = structuredClone(saved);
      expect(applyHistory(records, (id) => saved[id], days)).toEqual(expected);
      expect(saved).toEqual(before);
    });
  }
});

describe("readOrder", () => {
  for (const { name, records, fetch, expect: expected } of fixtures.order) {
    test(name, () => expect(readOrder(records, fetch)).toEqual(expected));
  }
});

describe("threadLabels", () => {
  for (const { name, messages, expect: expected } of fixtures.labels) {
    test(name, () => expect(threadLabels(messages)).toEqual(expected));
  }
});
