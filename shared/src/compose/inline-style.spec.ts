import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type InlineStyle, parseInlineStyles, plainText } from "./index";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/inline-style-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  cases: {
    name: string;
    in: string;
    spans: [InlineStyle, number, number][];
    plain: string;
  }[];
};

describe("latr's inline-style fixtures", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      expect(
        parseInlineStyles(fixture.in).map(({ style, start, end }) => [
          style,
          start,
          end,
        ]),
      ).toEqual(fixture.spans);
      expect(plainText(fixture.in)).toBe(fixture.plain);
    });
  }

  test("an adversarial 120KB line parses in linear time", () => {
    const text = "*a ".repeat(20_000) + "b_ ".repeat(20_000);
    const started = performance.now();
    parseInlineStyles(text);
    expect(performance.now() - started).toBeLessThan(200);
  });
});
