import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { futureSection, pastSection, sectionize } from "./sections";

test("sectionize keeps order and merges consecutive items only", () => {
  const sections = sectionize([1, 2, 5, 6, 2], (value) => ({
    key: value < 4 ? "low" : "high",
    title: value < 4 ? "Low" : "High",
  }));
  expect(sections.map((section) => [section.key, section.items])).toEqual([
    ["low", [1, 2]],
    ["high", [5, 6]],
    ["low", [2]],
  ]);
});

type SectionFixture = {
  name?: string;
  now: string;
  date: string;
  key: string;
  title: string;
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/section-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { past: SectionFixture[]; future: SectionFixture[] };

describe("section fixtures (must match Android)", () => {
  for (const [section, head] of [
    ["past", pastSection],
    ["future", futureSection],
  ] as const) {
    for (const fixture of fixtures[section]) {
      test(`${section}: ${fixture.name ?? `${fixture.date} at ${fixture.now}`}`, () => {
        expect(
          head(
            new Date(fixture.date).getTime(),
            new Date(fixture.now).getTime(),
          ),
        ).toEqual({ key: fixture.key, title: fixture.title });
      });
    }
  }
});
