import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type InitialsPerson, initials } from "./initials";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/initials-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  initials: {
    name: string;
    person: InitialsPerson;
    expect: string;
    single: string;
  }[];
};

describe("initials", () => {
  for (const fixture of fixtures.initials) {
    test(fixture.name, () => {
      expect(initials(fixture.person)).toBe(fixture.expect);
      expect(initials(fixture.person, true)).toBe(fixture.single);
    });
  }
});
