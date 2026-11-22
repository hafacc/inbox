import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type HeldPlaces, holdPlaces, type ThreadPlace } from "./held-places";

type PlacesJson = {
  threads: Record<string, ThreadPlace>;
  bundles: Record<string, number>;
  pins?: Record<string, boolean>;
};

type Fixtures = {
  hold: {
    name: string;
    held: PlacesJson;
    open: PlacesJson;
    release: boolean;
    expect: PlacesJson;
    same: boolean;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/held-place-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function places({ threads, bundles, pins = {} }: PlacesJson): HeldPlaces {
  return {
    threads: new Map(Object.entries(threads)),
    bundles: new Map(Object.entries(bundles)),
    pins: new Map(Object.entries(pins)),
  };
}

function plain({ threads, bundles, pins }: HeldPlaces): PlacesJson {
  return {
    threads: Object.fromEntries(threads),
    bundles: Object.fromEntries(bundles),
    pins: Object.fromEntries(pins),
  };
}

describe("held place fixtures (must match Android)", () => {
  for (const fixture of fixtures.hold) {
    test(fixture.name, () => {
      const held = places(fixture.held);
      const next = holdPlaces(held, places(fixture.open), fixture.release);
      expect(plain(next)).toEqual({ pins: {}, ...fixture.expect });
      expect(next === held).toBe(fixture.same);
    });
  }
});
