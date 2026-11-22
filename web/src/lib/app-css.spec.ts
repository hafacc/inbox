import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { themeColors } from "@inbox/shared/themes";

const css = readFileSync(new URL("../app.css", import.meta.url), "utf8");

function colorsIn(block: string): Record<string, string> {
  const colors: Record<string, string> = {};
  for (const [, name, value] of block.matchAll(
    /--color-([a-z0-9-]+):\s*(#[0-9a-f]{6});/g,
  )) {
    colors[name!] = value!;
  }
  return colors;
}

// The stylesheet's colors are what paints before the saved theme is applied, so they must be green's.
describe("app.css matches the green theme", () => {
  test("light", () => {
    const block = /@theme \{([\s\S]*?)\n\}/.exec(css)![1]!;
    expect(colorsIn(block)).toEqual(themeColors("green", "light"));
  });
  test("dark", () => {
    const block = /\n\.dark \{([\s\S]*?)\n\}/.exec(css)![1]!;
    expect(colorsIn(block)).toEqual(themeColors("green", "dark"));
  });
});
