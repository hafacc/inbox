import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  avatarIndex,
  type ColorMode,
  contrastRatio,
  emailStyle,
  labelTone,
  THEMES,
  type ThemeId,
  themeColors,
  themeIdFrom,
} from "./themes";

type Fixtures = {
  labelTone: { hex: string; tone: string }[];
  avatarIndex: { email: string; count: number; index: number }[];
  colors: { theme: ThemeId; mode: ColorMode; expect: Record<string, string> }[];
  emailStyle: { theme: ThemeId; mode: ColorMode; css: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/theme-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("theme fixtures (must match Android)", () => {
  for (const fixture of fixtures.labelTone) {
    test(`labelTone ${JSON.stringify(fixture.hex)}`, () => {
      expect(labelTone(fixture.hex)).toBe(fixture.tone as never);
    });
  }
  for (const fixture of fixtures.avatarIndex) {
    test(`avatarIndex ${fixture.email}`, () => {
      expect(avatarIndex(fixture.email, fixture.count)).toBe(fixture.index);
    });
  }
  for (const fixture of fixtures.colors) {
    test(`themeColors ${fixture.theme} ${fixture.mode}`, () => {
      const colors = themeColors(fixture.theme, fixture.mode);
      for (const [token, value] of Object.entries(fixture.expect)) {
        expect(colors[token]).toBe(value);
      }
    });
  }
});

describe("message bodies", () => {
  for (const fixture of fixtures.emailStyle) {
    test(`emailStyle ${fixture.theme} ${fixture.mode} (must match Android)`, () => {
      expect(emailStyle(fixture.theme, fixture.mode)).toBe(fixture.css);
    });
  }

  const colorIn = (css: string, rule: RegExp): string => rule.exec(css)![1]!;

  for (const { id } of THEMES) {
    test(`${id}: dark mail's unset text and links read on its page and the app's`, () => {
      const css = emailStyle(id, "dark");
      const colors = themeColors(id, "dark");
      const text = colorIn(css, /body\{color:(#[0-9a-f]{6})/);
      const link = colorIn(css, /a\{color:(#[0-9a-f]{6})/);
      for (const ground of [
        colorIn(css, /html\{background:(#[0-9a-f]{6})/),
        colors.surface!,
        colors.bg!,
      ]) {
        expect(contrastRatio(text, ground)).toBeGreaterThanOrEqual(7);
        expect(contrastRatio(link, ground)).toBeGreaterThanOrEqual(4.5);
      }
    });
    test(`${id}: light mail links read on white`, () => {
      const css = emailStyle(id, "light");
      expect(
        contrastRatio(colorIn(css, /a\{color:(#[0-9a-f]{6})/), "#ffffff"),
      ).toBeGreaterThanOrEqual(4.5);
    });
  }
});

// Text needs 4.5:1 (WCAG AA); large or decorative marks such as label dots need 3:1.
const PAIRS: [string, string, number][] = [
  ["text", "bg", 7],
  ["text", "surface", 7],
  ["text", "sidebar", 7],
  ["text-secondary", "surface", 4.5],
  ["text-secondary", "bg", 4.5],
  ["muted", "surface", 4.5],
  ["on-accent", "accent", 4.5],
  ["accent-strong", "surface", 4.5],
  ["accent-strong", "accent-soft", 4.5],
  ["accent", "surface", 3],
  ["focus", "surface", 3],
  ["focus", "bg", 3],
  ["on-done", "done", 4.5],
  ["done", "done-soft", 3],
  ["on-snooze-soft", "snooze-soft", 4.5],
  ["on-danger", "danger", 4.5],
  ["danger", "surface", 4.5],
  ["on-snackbar", "snackbar", 7],
  ["snackbar-accent", "snackbar", 4.5],
];

describe("contrast", () => {
  for (const { id } of THEMES) {
    for (const mode of ["light", "dark"] as const) {
      test(`${id} ${mode}`, () => {
        const colors = themeColors(id, mode);
        for (const [foreground, background, minimum] of PAIRS) {
          const ratio = contrastRatio(colors[foreground]!, colors[background]!);
          expect({
            pair: `${foreground} on ${background}`,
            ok: ratio >= minimum,
            ratio,
          }).toMatchObject({ ok: true });
        }
        for (let index = 0; index < 6; index++) {
          const ratio = contrastRatio(
            colors[`on-avatar-${index}`]!,
            colors[`avatar-${index}`]!,
          );
          expect({ avatar: index, ok: ratio >= 4.5, ratio }).toMatchObject({
            ok: true,
          });
        }
        for (const tone of [
          "red",
          "orange",
          "yellow",
          "green",
          "teal",
          "blue",
          "purple",
          "pink",
          "gray",
        ]) {
          const ratio = contrastRatio(
            colors[`label-${tone}`]!,
            colors.surface!,
          );
          expect({ tone, ok: ratio >= 3, ratio }).toMatchObject({ ok: true });
        }
      });
    }
  }
});

test("unknown or missing theme ids fall back to green", () => {
  expect(themeIdFrom("plum")).toBe("plum");
  expect(themeIdFrom("neon")).toBe("green");
  expect(themeIdFrom(undefined)).toBe("green");
});
