import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type MarkdownSpanKind, markdownSpans, wrapSelection } from "./index";

type SpanFixture = {
  name: string;
  text: string;
  spans: [number, number, MarkdownSpanKind][];
};

type WrapFixture = {
  name: string;
  text: string;
  start: number;
  end: number;
  marker: "*" | "_";
  from: number;
  to: number;
  insert: string;
  selStart: number;
  selEnd: number;
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/markdown-span-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { spans: SpanFixture[]; wrap: WrapFixture[] };

describe("markdownSpans", () => {
  for (const fixture of fixtures.spans) {
    test(fixture.name, () => {
      const spans = markdownSpans(fixture.text).map(
        ({ start, end, kind }) => [start, end, kind] as const,
      );
      expect(spans).toEqual(fixture.spans);
    });
  }
});

describe("wrapSelection", () => {
  for (const fixture of fixtures.wrap) {
    test(fixture.name, () => {
      const { name, text, start, end, marker, ...expected } = fixture;
      expect(wrapSelection(text, start, end, marker)).toEqual(expected);
    });
  }
});
