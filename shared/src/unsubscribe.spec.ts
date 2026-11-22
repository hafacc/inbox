import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { parseUnsubscribe, type Unsubscribe } from "./unsubscribe";

type Fixtures = {
  parse: {
    name: string;
    listUnsubscribe: string | null;
    listUnsubscribePost: string | null;
    expect: Unsubscribe;
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/unsubscribe-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("unsubscribe fixtures (must match Android)", () => {
  for (const fixture of fixtures.parse) {
    test(fixture.name, () => {
      expect(
        parseUnsubscribe(fixture.listUnsubscribe, fixture.listUnsubscribePost),
      ).toEqual(fixture.expect);
    });
  }
});
