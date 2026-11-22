import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  anchorRepeat,
  nextOccurrence,
  type Repeat,
  type RepeatDraft,
  readRepeat,
  repeatText,
} from "./repeat";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/repeat-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  next: { name: string; repeat: Repeat; after: string; next: string }[];
  anchor: { name: string; repeat: RepeatDraft; now: string; anchor: string }[];
  text: { repeat: Repeat; text: string }[];
  read: { name: string; in: unknown; out: Repeat | null }[];
};

describe("nextOccurrence", () => {
  for (const fixture of fixtures.next) {
    test(fixture.name, () => {
      expect(nextOccurrence(fixture.repeat, fixture.after)).toBe(fixture.next);
    });
  }
});

describe("anchorRepeat", () => {
  for (const fixture of fixtures.anchor) {
    test(fixture.name, () => {
      expect(anchorRepeat(fixture.repeat, fixture.now).anchor).toBe(
        fixture.anchor,
      );
    });
  }
});

describe("repeatText", () => {
  for (const fixture of fixtures.text) {
    test(fixture.text, () => {
      expect(repeatText(fixture.repeat)).toBe(fixture.text);
    });
  }
});

describe("readRepeat", () => {
  for (const fixture of fixtures.read) {
    test(fixture.name, () => {
      expect(readRepeat(fixture.in)).toEqual(fixture.out);
    });
  }
});
