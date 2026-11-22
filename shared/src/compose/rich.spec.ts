import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { normalizeHtml } from "./markdown";
import {
  markdownToRich,
  type RichBlock,
  richToHtml,
  richToMarkdown,
} from "./rich";

type Fixtures = {
  markdown: {
    name: string;
    markdown: string;
    blocks: RichBlock[];
    html: string;
    text: string;
  }[];
  write: {
    name: string;
    blocks: RichBlock[];
    text: string;
    reread: RichBlock[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/rich-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("rich fixtures (must match Android)", () => {
  for (const { name, markdown, blocks, html, text } of fixtures.markdown) {
    test(name, () => {
      expect(markdownToRich(markdown)).toEqual(blocks);
      expect(normalizeHtml(richToHtml(blocks))).toBe(normalizeHtml(html));
      expect(richToMarkdown(blocks)).toBe(text);
    });
  }
});

describe("rich write fixtures (must match Android)", () => {
  for (const { name, blocks, text, reread } of fixtures.write) {
    test(name, () => {
      expect(richToMarkdown(blocks)).toBe(text);
      expect(markdownToRich(text)).toEqual(reread);
    });
  }
});
