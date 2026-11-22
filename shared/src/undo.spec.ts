import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  mergeUndoItems,
  restoresSnooze,
  type UndoKind,
  undoChange,
  undoLabels,
  undoText,
} from "./undo";

type Item = { id: string; labelIds: string[] };

type Fixtures = {
  labels: {
    name: string;
    kind: UndoKind;
    touched: string[];
    expect: string[];
  }[];
  change: {
    name: string;
    before: string[];
    current: string[];
    restore: string[];
    expect: { add: string[]; remove: string[] };
  }[];
  snooze: Record<UndoKind, boolean>;
  merge: { name: string; earlier: Item[]; later: Item[]; expect: Item[] }[];
  text: { kind: UndoKind; count: number; pinned: boolean; expect: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/undo-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("undoLabels", () => {
  for (const { name, kind, touched, expect: expected } of fixtures.labels) {
    test(name, () => {
      expect(undoLabels(kind, touched)).toEqual(expected);
    });
  }
});

describe("undoChange", () => {
  for (const {
    name,
    before,
    current,
    restore,
    expect: expected,
  } of fixtures.change) {
    test(name, () => {
      expect(undoChange(before, current, restore)).toEqual(expected);
    });
  }
});

test("restoresSnooze", () => {
  for (const [kind, expected] of Object.entries(fixtures.snooze)) {
    expect([kind, restoresSnooze(kind as UndoKind)]).toEqual([kind, expected]);
  }
});

describe("mergeUndoItems", () => {
  for (const { name, earlier, later, expect: expected } of fixtures.merge) {
    test(name, () => {
      expect(mergeUndoItems(earlier, later)).toEqual(expected);
    });
  }
});

test("undoText", () => {
  for (const { kind, count, pinned, expect: expected } of fixtures.text) {
    expect(undoText(kind, count, pinned)).toBe(expected);
  }
});
