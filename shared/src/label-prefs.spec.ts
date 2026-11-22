import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import palette from "../themes/themes.json";
import {
  CATEGORY_ICONS,
  CATEGORY_TONES,
  LABEL_ICONS,
  type LabelPrefs,
  labelPrefsFrom,
} from "./label-prefs";

const LABEL_TONES: readonly string[] = palette.labelTones.order;

type Fixtures = {
  prefs: {
    name: string;
    label: { id: string; labelListVisibility?: string };
    stored: unknown;
    expect: LabelPrefs;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/label-prefs-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("label prefs fixtures (must match Android)", () => {
  for (const fixture of fixtures.prefs) {
    test(fixture.name, () => {
      expect(labelPrefsFrom(fixture.stored, fixture.label)).toEqual(
        fixture.expect,
      );
    });
  }
});

test("icon ids are unique and plain", () => {
  const ids = LABEL_ICONS.map((icon) => icon.id);
  expect(new Set(ids).size).toBe(ids.length);
  for (const id of ids) expect(id).toMatch(/^[a-z][a-z-]*$/);
});

describe("categories", () => {
  test("each has its own fixed color and an icon", () => {
    const tones = Object.values(CATEGORY_TONES);
    expect(Object.keys(CATEGORY_TONES).sort()).toEqual(
      Object.keys(CATEGORY_ICONS).sort(),
    );
    expect(new Set(tones).size).toBe(tones.length);
    for (const tone of tones) expect(LABEL_TONES).toContain(tone);
  });
});
