import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type ChipSpot, typeFree } from "./chip-or";
import type { Chip } from "./search";

type Fixtures = {
  type: {
    name: string;
    tags?: boolean;
    chips?: Chip[];
    spot?: ChipSpot | null;
    steps: {
      text: string;
      cursor: number | null;
      expect: {
        chips: Chip[];
        free: string;
        cursor: number;
        spot: ChipSpot | null;
      };
    }[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/chip-or-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

for (const fixture of fixtures.type) {
  test(fixture.name, () => {
    let chips = fixture.chips ?? [];
    let spot = fixture.spot ?? null;
    for (const step of fixture.steps) {
      const typed = typeFree(
        chips,
        spot,
        step.text,
        step.cursor,
        fixture.tags ?? false,
      );
      expect({
        chips: typed.chips,
        free: typed.parsed.free,
        cursor: typed.parsed.cursor,
        spot: typed.spot,
      }).toEqual(step.expect);
      chips = typed.chips;
      spot = typed.spot;
    }
  });
}
