import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type FocusEntry,
  focusQuery,
  focusSections,
  type ImportantChange,
  importantChange,
} from "./focus";

type FixtureEntry =
  | { id: string; labels: string[] }
  | { todo: string; pinned: boolean }
  | { bundle: string; threads: string[] };
type FixtureSection = { key: string; entries: FixtureEntry[] };

type Fixtures = {
  mark: {
    name: string;
    labelIds: string[];
    important: boolean;
    expect: ImportantChange;
  }[];
  sections: {
    name: string;
    openId: string | null;
    heldPins?: Record<string, boolean>;
    sections: FixtureSection[];
    expect: {
      sections: { key: string; entries: string[] }[];
      hidden: number;
    };
  }[];
  query: { name: string; query: string; expect: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/focus-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function entryOf(entry: FixtureEntry): FocusEntry {
  if ("bundle" in entry) return { kind: "bundle", threadIds: entry.threads };
  else if ("todo" in entry) return { kind: "todo", pinned: entry.pinned };
  else return { kind: "thread", id: entry.id, labelIds: entry.labels };
}

function entryName(entry: FixtureEntry): string {
  if ("bundle" in entry) return entry.bundle;
  else if ("todo" in entry) return `todo:${entry.todo}`;
  else return entry.id;
}

describe("focus fixtures (must match Android)", () => {
  for (const { name, labelIds, important, expect: want } of fixtures.mark) {
    test(`mark: ${name}`, () => {
      expect(importantChange(labelIds, important)).toEqual(want);
    });
  }

  for (const {
    name,
    openId,
    heldPins,
    sections,
    expect: want,
  } of fixtures.sections) {
    test(`sections: ${name}`, () => {
      const result = focusSections<FixtureEntry, FixtureSection>(
        sections,
        entryOf,
        openId,
        new Map(Object.entries(heldPins ?? {})),
      );
      expect({
        sections: result.sections.map((section) => ({
          key: section.key,
          entries: section.entries.map(entryName),
        })),
        hidden: result.hidden,
      }).toEqual(want);
    });
  }

  for (const { name, query, expect: want } of fixtures.query) {
    test(`query: ${name}`, () => {
      expect(focusQuery(query)).toBe(want);
    });
  }
});
