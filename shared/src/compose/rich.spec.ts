import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { markdownToHtml, normalizeHtml } from "./markdown";
import {
  draftLinks,
  markdownToRich,
  plainLinks,
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
    html?: string;
  }[];
  plain: { name: string; markdown: string; text: string }[];
  draft: {
    name: string;
    text: string;
    html: string | null;
    markdown: string;
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
  for (const { name, blocks, text, reread, html } of fixtures.write) {
    test(name, () => {
      expect(richToMarkdown(blocks)).toBe(text);
      expect(markdownToRich(text, false)).toEqual(reread);
      if (html !== undefined) {
        expect(normalizeHtml(richToHtml(blocks))).toBe(normalizeHtml(html));
      }
    });
  }
});

describe("plain-text links fixtures (must match Android)", () => {
  for (const { name, markdown, text } of fixtures.plain) {
    test(name, () => {
      expect(plainLinks(markdown)).toBe(text);
      expect(draftLinks(text, markdownToHtml(markdown, false))).toBe(markdown);
    });
  }
});

describe("draft links fixtures (must match Android)", () => {
  for (const { name, text, html, markdown } of fixtures.draft) {
    test(name, () => {
      expect(draftLinks(text, html)).toBe(markdown);
    });
  }
});
