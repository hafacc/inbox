// What the composer's text means: latr's `*bold*` and `_italic_` (inline-style.ts), plus bare
// URLs (shared/src/web-links.ts) and inline images (`![alt](cid:id)`), which the styles never reach into. The composers draw
// these spans as the text is typed and markdownToHtml renders them. Android :core ports this
// (core/compose/MarkdownSpans.kt); both run shared/testdata/markdown-span-fixtures.json.

import { findWebLinks } from "../web-links";
import { parseInlineStyles } from "./inline-style";

/** What a styled stretch of the composer's text is. */
export type MarkdownSpanKind = "image" | "url" | "bold" | "italic" | "marker";

/** A styled stretch of the text, from `start` up to (not including) `end`, in UTF-16 units. */
export type MarkdownSpan = {
  start: number;
  end: number;
  kind: MarkdownSpanKind;
};

/** Span kinds in their tie-break order when two spans cover the same range. */
export const MARKDOWN_SPAN_KINDS: readonly MarkdownSpanKind[] = [
  "image",
  "url",
  "bold",
  "italic",
  "marker",
];

const IMAGE = /^!\[[^\]\n]*\]\(cid:[^\s()]+\)/i;

/** Inline images and bare URLs (web addresses, as `findWebLinks` finds them), in order; they never overlap. */
function literalSpans(text: string): MarkdownSpan[] {
  const spans: MarkdownSpan[] = [];
  const links = new Map(findWebLinks(text).map((link) => [link.start, link]));
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (char === "!" && text[index + 1] === "[") {
      const match = IMAGE.exec(text.slice(index));
      if (match !== null) {
        const end = index + match[0].length;
        spans.push({ start: index, end, kind: "image" });
        index = end;
        continue;
      }
    }
    const link = links.get(index);
    if (link !== undefined) {
      spans.push({ start: index, end: link.end, kind: "url" });
      index = link.end;
      continue;
    }
    index++;
  }
  return spans;
}

/**
 * The spans of the composer's text: each `*bold*`/`_italic_` (markers included) with a `marker`
 * span for each of its two markers, by latr's rules; each bare URL; each inline image. Images and
 * URLs are read first and count as letters to the style rules, so no marker inside one opens or
 * closes a style. Sorted by start, then end descending, then kind in `MARKDOWN_SPAN_KINDS` order.
 */
export function markdownSpans(text: string): MarkdownSpan[] {
  const spans = literalSpans(text);
  let masked = text;
  if (spans.length > 0) {
    let built = "";
    let at = 0;
    for (const span of spans) {
      built += text.slice(at, span.start) + "a".repeat(span.end - span.start);
      at = span.end;
    }
    masked = built + text.slice(at);
  }
  for (const { style, start, end } of parseInlineStyles(masked)) {
    spans.push(
      { start, end, kind: style },
      { start, end: start + 1, kind: "marker" },
      { start: end - 1, end, kind: "marker" },
    );
  }
  return spans.sort(
    (a, b) =>
      a.start - b.start ||
      b.end - a.end ||
      MARKDOWN_SPAN_KINDS.indexOf(a.kind) - MARKDOWN_SPAN_KINDS.indexOf(b.kind),
  );
}
