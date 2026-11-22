import { beforeAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  cssColor,
  type DarkPalette,
  functionColor,
  hexColor,
  namedColor,
  type Rgba,
} from "@inbox/shared/dark-mail";
import * as csstree from "css-tree";
import { JSDOM } from "jsdom";
import type { darkenMessageHtml as Darken } from "./dark-mail";

type EmailCase = {
  name: string;
  html: string;
  colorSchemes: string[];
  plain: boolean;
  unchanged?: boolean;
  expect?: { selector: string; color?: string; background?: string }[];
  sheetColors?: string[];
  sheetAbsent?: string[];
  inline?: { selector: string; property: string; colors: string[] }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL(
      "../../../shared/testdata/dark-mail-fixtures.json",
      import.meta.url,
    ),
    "utf8",
  ),
) as {
  palette: Record<keyof DarkPalette, [number, number, number]>;
  emails: EmailCase[];
};

const toRgba = ([red, green, blue]: [number, number, number]): Rgba => ({
  red,
  green,
  blue,
  alpha: 1,
});
const palette: DarkPalette = {
  page: toRgba(fixtures.palette.page),
  text: toRgba(fixtures.palette.text),
  link: toRgba(fixtures.palette.link),
};

let darken: typeof Darken;

beforeAll(async () => {
  // The DOM parser comes from the browser; jsdom stands in for it.
  Object.assign(globalThis, { window: new JSDOM("").window });
  ({ darkenMessageHtml: darken } = await import("./dark-mail"));
});

/** The colors in a CSS tree, as `cssColor` writes them; transparent ones left out. */
function colorsIn(node: csstree.CssNode): string[] {
  const found: string[] = [];
  csstree.walk(node, (child) => {
    let color: Rgba | null = null;
    if (child.type === "Hash") color = hexColor(child.value);
    else if (child.type === "Identifier") color = namedColor(child.name);
    else if (child.type === "Function") {
      const args = [...child.children]
        .filter((argument) => argument.type !== "Operator")
        .map((argument) => ({
          value: Number((argument as csstree.NumberNode).value),
          unit: (argument.type === "Percentage" ? "%" : "") as "" | "%",
        }));
      color = functionColor(child.name, args);
    }
    if (color && color.alpha > 0) found.push(cssColor(color));
  });
  return found;
}

function declarationColors(style: string, property: string): string[] {
  let colors: string[] = [];
  csstree.walk(csstree.parse(style, { context: "declarationList" }), {
    visit: "Declaration",
    enter(node) {
      if (node.property.toLowerCase() === property)
        colors = colorsIn(node.value);
    },
  });
  return colors;
}

function expectNear(actual: string | undefined, expected: string): void {
  expect(actual).toBeDefined();
  const channels = (hex: string): number[] =>
    [1, 3, 5].map((start) => Number.parseInt(hex.slice(start, start + 2), 16));
  const [have, want] = [channels(actual!), channels(expected)];
  have.forEach((value, index) => {
    expect(Math.abs(value - want[index]!)).toBeLessThanOrEqual(1);
  });
}

describe("shared dark mail fixtures", () => {
  for (const fixture of fixtures.emails) {
    test(fixture.name, () => {
      const output = darken(fixture.html, {
        palette,
        colorSchemes: fixture.colorSchemes,
        plain: fixture.plain,
      });
      if (fixture.unchanged) {
        expect(output).toBe(fixture.html);
        return;
      }
      const document = new globalThis.window.DOMParser().parseFromString(
        `<body>${output}`,
        "text/html",
      );
      for (const { selector, color, background } of fixture.expect ?? []) {
        const style =
          document.querySelector(selector)?.getAttribute("style") ?? "";
        if (color) expectNear(declarationColors(style, "color").at(-1), color);
        if (background) {
          expectNear(
            declarationColors(style, "background-color").at(-1),
            background,
          );
        }
      }
      const sheetColors = new Set(
        [...document.querySelectorAll("style")].flatMap((sheet) =>
          colorsIn(csstree.parse(sheet.textContent ?? "")),
        ),
      );
      for (const color of fixture.sheetColors ?? []) {
        expect(sheetColors).toContain(color);
      }
      for (const color of fixture.sheetAbsent ?? []) {
        expect(sheetColors).not.toContain(color);
      }
      for (const { selector, property, colors } of fixture.inline ?? []) {
        const style =
          document.querySelector(selector)?.getAttribute("style") ?? "";
        const found = declarationColors(style, property);
        expect(found.length).toBe(colors.length);
        found.forEach((value, index) => {
          expectNear(value, colors[index]!);
        });
      }
    });
  }
});

test("a mail darkened again for the same palette gives the same HTML, another palette its own", () => {
  const html =
    '<style>.box { background: #ffffff; color: #333333; }</style><div class="box">Hello there, a line of text.</div>';
  const first = darken(html, { palette });
  expect(darken(html, { palette })).toBe(first);
  const other = { ...palette, page: toRgba([40, 10, 10]) };
  expect(darken(html, { palette: other })).not.toBe(first);
  expect(darken(html, { palette })).toBe(first);
});

test("a mail past the element limit shows as it is", () => {
  const html = `<div style="background-color:#ffffff;color:#000000">${"<span>word</span>".repeat(20_001)}</div>`;
  expect(darken(html, { palette })).toBe(html);
});
