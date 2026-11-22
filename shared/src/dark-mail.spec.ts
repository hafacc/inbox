import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  attributeColor,
  type ColorArgument,
  type ColorRole,
  colorRole,
  contrast,
  cssColor,
  type DarkPalette,
  darkColor,
  functionColor,
  hasOwnDarkDesign,
  hexColor,
  isDarkMail,
  MIN_CONTRAST,
  mediaApplies,
  namedColor,
  type Rgba,
  readableText,
  visibleLength,
} from "./dark-mail";

type Channels = [number, number, number] | [number, number, number, number];

type Fixtures = {
  palette: Record<keyof DarkPalette, Channels>;
  parsing: {
    kind: "hex" | "named" | "function" | "attribute";
    input: string;
    args?: ColorArgument[];
    expect: string | null;
  }[];
  turned: { color: Channels; role: ColorRole; expect: string }[];
  readable: { text: Channels; background: Channels; expect: string }[];
  roles: { property: string; role: ColorRole | null }[];
  media: { query: string; applies: boolean }[];
  ownDark: { media: string[]; colorSchemes: string[]; own: boolean }[];
  darkMail: {
    samples: { background: Channels; weight: number }[];
    dark: boolean;
  }[];
  lengths: { text: string; length: number }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/dark-mail-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function color([red, green, blue, alpha = 1]: Channels): Rgba {
  return { red, green, blue, alpha };
}

const palette: DarkPalette = {
  page: color(fixtures.palette.page),
  text: color(fixtures.palette.text),
  link: color(fixtures.palette.link),
};

/** Within 1 per channel of the fixture's color, allowing for float differences between platforms. */
function expectColor(actual: Rgba, expected: string): void {
  const written = cssColor(actual);
  if (written === expected) return;
  const numbers = (css: string): number[] =>
    css.startsWith("#")
      ? [1, 3, 5].map((start) =>
          Number.parseInt(css.slice(start, start + 2), 16),
        )
      : css.slice(5, -1).split(",").map(Number);
  const [have, want] = [numbers(written), numbers(expected)];
  expect(have.length).toBe(want.length);
  have.forEach((value, index) => {
    expect(Math.abs(value - want[index]!)).toBeLessThanOrEqual(
      index < 3 ? 1 : 0.001,
    );
  });
}

describe("dark mail fixtures (must match Android)", () => {
  for (const fixture of fixtures.parsing) {
    test(`${fixture.kind} color ${JSON.stringify(fixture.input)}`, () => {
      const parsed =
        fixture.kind === "hex"
          ? hexColor(fixture.input)
          : fixture.kind === "named"
            ? namedColor(fixture.input)
            : fixture.kind === "attribute"
              ? attributeColor(fixture.input)
              : functionColor(fixture.input, fixture.args!);
      expect(parsed === null ? null : cssColor(parsed)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.turned) {
    test(`${fixture.role} ${fixture.color.join(",")} turns to ${fixture.expect}`, () => {
      expectColor(
        darkColor(color(fixture.color), fixture.role, palette),
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.readable) {
    test(`${fixture.text.join(",")} reads on ${fixture.background.join(",")}`, () => {
      const result = readableText(
        color(fixture.text),
        color(fixture.background),
      );
      expectColor(result, fixture.expect);
      expect(
        contrast(result, color(fixture.background)),
      ).toBeGreaterThanOrEqual(MIN_CONTRAST);
    });
  }
  test("color roles", () => {
    for (const { property, role } of fixtures.roles) {
      expect([property, colorRole(property)]).toEqual([property, role]);
    }
  });
  test("media queries", () => {
    for (const { query, applies } of fixtures.media) {
      expect([query, mediaApplies(query)]).toEqual([query, applies]);
    }
  });
  test("own dark designs", () => {
    for (const { own, ...signals } of fixtures.ownDark) {
      expect([signals, hasOwnDarkDesign(signals)]).toEqual([signals, own]);
    }
  });
  test("already dark mail", () => {
    for (const { samples, dark } of fixtures.darkMail) {
      const read = samples.map(({ background, weight }) => ({
        background: color(background),
        weight,
      }));
      expect([samples, isDarkMail(read)]).toEqual([samples, dark]);
    }
  });
  test("visible text lengths", () => {
    for (const { text, length } of fixtures.lengths) {
      expect([text, visibleLength(text)]).toEqual([text, length]);
    }
  });
});
