import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { unwrapLink } from "./links";

type Fixtures = { unwrap: { name: string; link: string; expect: string }[] };

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/link-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("link fixtures (must match Android)", () => {
  for (const fixture of fixtures.unwrap) {
    test(fixture.name, () => {
      expect(unwrapLink(fixture.link)).toBe(fixture.expect);
    });
  }
});
