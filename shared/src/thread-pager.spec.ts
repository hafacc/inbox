import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type PageStep,
  pageOffset,
  pageStarts,
  pageStep,
} from "./thread-pager";

type Fixtures = {
  starts: { name: string; x: number; width: number; expect: boolean }[];
  offset: {
    name: string;
    dx: number;
    hasPrevious: boolean;
    hasNext: boolean;
    expect: number;
  }[];
  step: {
    name: string;
    dx: number;
    width: number;
    velocity: number;
    hasPrevious: boolean;
    hasNext: boolean;
    expect: PageStep | null;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-pager-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("pageStarts", () => {
  for (const fixture of fixtures.starts) {
    test(fixture.name, () => {
      expect(pageStarts(fixture.x, fixture.width)).toBe(fixture.expect);
    });
  }
});

describe("pageOffset", () => {
  for (const fixture of fixtures.offset) {
    test(fixture.name, () => {
      expect(
        pageOffset(fixture.dx, fixture.hasPrevious, fixture.hasNext),
      ).toBeCloseTo(fixture.expect);
    });
  }
});

describe("pageStep", () => {
  for (const fixture of fixtures.step) {
    test(fixture.name, () => {
      expect(
        pageStep(
          fixture.dx,
          fixture.width,
          fixture.velocity,
          fixture.hasPrevious,
          fixture.hasNext,
        ),
      ).toBe(fixture.expect);
    });
  }
});
