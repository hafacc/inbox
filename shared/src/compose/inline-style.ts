// latr's text styles (`*bold*`, `_italic_`), ported from latr's web/src/lib/utils/inline-style.ts
// (parseInlineStyles, plainText, wrapSelection; not its #tags or styledTree) with only the changes
// `noUncheckedIndexedAccess` needs. shared/testdata/inline-style-fixtures.json is latr's
// testdata/inline-style-fixtures.json byte for byte; Android :core ports this (InlineStyle.kt).
// Keep all three in step with latr until they move to a common package.

/** A text style latr's markers give. */
export type InlineStyle = "bold" | "italic";

/** A styled stretch: `start`/`end` are UTF-16 offsets covering both markers, text[start] and text[end - 1]. */
export type StyleSpan = { style: InlineStyle; start: number; end: number };

// Shared spec with Android's InlineStyle.kt: letters, decimal digits and combining marks count as word characters.
const LETTER_OR_DIGIT = /[\p{L}\p{Nd}\p{M}]/u;
// JS `\s`, which is exactly U+0009-000D, 0020, 0085, 00A0, 1680, 2000-200A, 2028, 2029, 202F, 205F, 3000, FEFF (Android matches).
const WHITESPACE = /\s/u;

function styleOf(marker: string): InlineStyle {
  return marker === "*" ? "bold" : "italic";
}

function codePointBefore(text: string, index: number): string | null {
  if (index <= 0) return null;
  const low = text.charCodeAt(index - 1);
  if (low >= 0xdc00 && low <= 0xdfff && index >= 2) {
    const high = text.charCodeAt(index - 2);
    if (high >= 0xd800 && high <= 0xdbff) return text.slice(index - 2, index);
  }
  return text[index - 1] ?? null;
}

function codePointAfter(text: string, index: number): string | null {
  if (index + 1 >= text.length) return null;
  return String.fromCodePoint(text.codePointAt(index + 1) as number);
}

/** `*bold*` and `_italic_` spans; markers stay in the text, so offsets are the text's own. */
export function parseInlineStyles(text: string): StyleSpan[] {
  if (!text.includes("*") && !text.includes("_")) return [];
  const spans: StyleSpan[] = [];
  const stack: number[] = [];
  // Per-marker indices into `stack`; an entry is live only while `stack[idx]` still holds its position.
  const open: Record<"*" | "_", { idx: number; pos: number }[]> = {
    "*": [],
    _: [],
  };
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (char === "\n") {
      stack.length = 0;
      open["*"].length = 0;
      open._.length = 0;
      continue;
    }
    if (char !== "*" && char !== "_") continue;
    const prev = codePointBefore(text, index);
    const next = codePointAfter(text, index);
    if (prev === char || next === char) continue;
    const canClose =
      prev !== null &&
      !WHITESPACE.test(prev) &&
      (next === null || !LETTER_OR_DIGIT.test(next));
    const canOpen =
      next !== null &&
      !WHITESPACE.test(next) &&
      (prev === null || !LETTER_OR_DIGIT.test(prev));
    let opener: { idx: number; pos: number } | null = null;
    if (canClose) {
      const list = open[char];
      while (list.length > 0) {
        const top = list[list.length - 1]!;
        if (stack[top.idx] === top.pos) {
          opener = top;
          break;
        }
        list.pop();
      }
    }
    if (opener) {
      spans.push({ style: styleOf(char), start: opener.pos, end: index + 1 });
      open[char].pop();
      stack.length = opener.idx;
    } else if (canOpen) {
      open[char].push({ idx: stack.length, pos: index });
      stack.push(index);
    }
  }
  return spans.sort((a, b) => a.start - b.start || b.end - a.end);
}

/** The text with every span's markers removed. */
export function plainText(text: string): string {
  const spans = parseInlineStyles(text);
  if (spans.length === 0) return text;
  const markers = new Set<number>();
  for (const span of spans) {
    markers.add(span.start);
    markers.add(span.end - 1);
  }
  let out = "";
  for (let index = 0; index < text.length; index++) {
    if (!markers.has(index)) out += text[index];
  }
  return out;
}

/** A replacement of [from, to) with `insert`, and the selection to restore afterwards. */
export type WrapEdit = {
  from: number;
  to: number;
  insert: string;
  selStart: number;
  selEnd: number;
};

/**
 * What Bold/Italic replaces [start, end) with, and the selection to restore afterwards. Toggles: a
 * selection already wrapped in `marker` (just outside it, or as its own first/last characters) is
 * unwrapped.
 */
export function wrapSelection(
  value: string,
  start: number,
  end: number,
  marker: "*" | "_",
): WrapEdit {
  while (start < end && WHITESPACE.test(value[start] ?? "")) start++;
  while (end > start && WHITESPACE.test(value[end - 1] ?? "")) end--;
  const inner = value.slice(start, end);
  if (value[start - 1] === marker && value[end] === marker) {
    return {
      from: start - 1,
      to: end + 1,
      insert: inner,
      selStart: start - 1,
      selEnd: end - 1,
    };
  }
  if (
    inner.length >= 2 &&
    inner[0] === marker &&
    inner[inner.length - 1] === marker
  ) {
    return {
      from: start,
      to: end,
      insert: inner.slice(1, -1),
      selStart: start,
      selEnd: end - 2,
    };
  }
  return {
    from: start,
    to: end,
    insert: `${marker}${inner}${marker}`,
    selStart: start + 1,
    selEnd: end + 1,
  };
}
