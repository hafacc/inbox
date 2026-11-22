import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type ParkKind, parksOnRelease, parkTitle } from "./compose-park";

type Fixtures = {
  title: { name: string; subject: string; kind: ParkKind; expect: string }[];
  release: {
    name: string;
    dragged: number;
    velocity: number;
    expect: boolean;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/compose-park-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("parkTitle", () => {
  for (const fixture of fixtures.title) {
    test(fixture.name, () => {
      expect(parkTitle(fixture.subject, fixture.kind)).toBe(fixture.expect);
    });
  }
});

describe("parksOnRelease", () => {
  for (const fixture of fixtures.release) {
    test(fixture.name, () => {
      expect(parksOnRelease(fixture.dragged, fixture.velocity)).toBe(
        fixture.expect,
      );
    });
  }
});
