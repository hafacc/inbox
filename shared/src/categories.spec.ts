import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  CATEGORY_CHOICE_ICONS,
  CATEGORY_CHOICE_TONES,
  CATEGORY_IDS,
  categoryChange,
  keepListed,
  type LabelDelta,
  threadCategory,
} from "./categories";
import { LABEL_ICONS } from "./label-prefs";

type Fixtures = {
  current: { name: string; labelIds: string[]; expect: string }[];
  change: {
    name: string;
    labelIds: string[];
    chosen: string;
    expect: LabelDelta;
  }[];
  keepListed: {
    name: string;
    ids: string[];
    dates: Record<string, number>;
    id: string;
    expect: string[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/category-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("category fixtures (must match Android)", () => {
  for (const { name, labelIds, expect: want } of fixtures.current) {
    test(`current: ${name}`, () => {
      expect(threadCategory(labelIds)).toBe(want);
    });
  }
  for (const { name, labelIds, chosen, expect: want } of fixtures.change) {
    test(`change: ${name}`, () => {
      expect(categoryChange(labelIds, chosen)).toEqual(want);
    });
  }
  for (const { name, ids, dates, id, expect: want } of fixtures.keepListed) {
    test(`keepListed: ${name}`, () => {
      expect(keepListed(ids, id, (key) => dates[key])).toEqual(want);
    });
  }
});

describe("category choices", () => {
  test("every category has a known icon and a tone", () => {
    const known = new Set(LABEL_ICONS.map((icon) => icon.id));
    for (const id of CATEGORY_IDS) {
      expect(known.has(CATEGORY_CHOICE_ICONS[id] ?? "")).toBe(true);
      expect(CATEGORY_CHOICE_TONES[id]).toBeDefined();
    }
  });
});
