// The composer's rich text as both editors hand it over (web: Tiptap, Android: Compose Rich Editor),
// and the two forms a message is sent in: HTML and markdown with this app's markers (`*bold*`,
// `_italic_`). Android :core ports this (core/compose/Rich.kt); both run
// shared/testdata/rich-fixtures.json.

import { escapeHtml } from "../format";
import { markdownSpans } from "./markdown-spans";

/** An inline style. */
export type RichMark = "bold" | "italic" | "strike" | "code";

/** Inline styles in the order runs list them and HTML nests them, outermost first. */
export const RICH_MARKS: readonly RichMark[] = [
  "bold",
  "italic",
  "strike",
  "code",
];

/** A stretch of text with one set of styles. */
export type RichRun = {
  /** the text */
  text: string;
  /** its styles in `RICH_MARKS` order; absent for none. Code text has no other style. */
  marks?: RichMark[];
  /** the link it is part of */
  href?: string;
};

/** One line of the composer: a paragraph, heading, list item or quote line, or a whole code block. */
export type RichBlock =
  | {
      /** plain paragraph */
      kind: "paragraph";
      /** its text */
      runs: RichRun[];
    }
  | {
      /** heading */
      kind: "heading";
      /** 1 to 6 */
      level: number;
      /** its text */
      runs: RichRun[];
    }
  | {
      /** bulleted list item */
      kind: "bullet";
      /** its text */
      runs: RichRun[];
    }
  | {
      /** numbered list item */
      kind: "ordered";
      /** its number */
      number: number;
      /** its text */
      runs: RichRun[];
    }
  | {
      /** quoted line */
      kind: "quote";
      /** its text */
      runs: RichRun[];
    }
  | {
      /** code block */
      kind: "code";
      /** its lines, joined with LF */
      text: string;
    };

const sameMarks = (left?: RichMark[], right?: RichMark[]): boolean =>
  (left ?? []).join() === (right ?? []).join();

/** Runs with empty ones dropped, neighbors of the same style joined, marks in order, code alone. */
export function normalizeRuns(runs: readonly RichRun[]): RichRun[] {
  const out: RichRun[] = [];
  for (const run of runs) {
    if (run.text === "") continue;
    const marks = run.marks?.includes("code")
      ? (["code"] as RichMark[])
      : RICH_MARKS.filter((mark) => run.marks?.includes(mark));
    const next: RichRun = { text: run.text };
    if (marks.length > 0) next.marks = marks;
    if (run.href !== undefined && !marks.includes("code")) next.href = run.href;
    const last = out[out.length - 1];
    if (
      last !== undefined &&
      last.href === next.href &&
      sameMarks(last.marks, next.marks)
    ) {
      last.text += next.text;
    } else {
      out.push(next);
    }
  }
  return out;
}

// A stretch of a line read before the style markers: inline code or a `[text](url)` link.
type Literal = {
  start: number;
  end: number;
  code?: string;
  link?: { text: string; href: string };
};

// A link target: no spaces, parentheses only in balanced pairs one deep, as `runsToMarkdown` writes it.
const LINK_TARGET = String.raw`(?:[^\s()]|\([^\s()]*\))+`;
const LINK = new RegExp(String.raw`^\[([^\]\n]+)\]\((${LINK_TARGET})\)`);
const WHOLE_LINK_TARGET = new RegExp(`^${LINK_TARGET}$`);

function literals(line: string): Literal[] {
  const found: Literal[] = [];
  let index = 0;
  while (index < line.length) {
    const char = line[index];
    if (char === "`") {
      const close = line.indexOf("`", index + 1);
      if (close > index + 1) {
        found.push({
          start: index,
          end: close + 1,
          code: line.slice(index + 1, close),
        });
        index = close + 1;
        continue;
      }
    } else if (char === "[" && line[index - 1] !== "!") {
      const match = LINK.exec(line.slice(index));
      if (match !== null) {
        const [whole, text, href] = match;
        found.push({
          start: index,
          end: index + whole.length,
          link: { text: text!, href: href! },
        });
        index += whole.length;
        continue;
      }
    }
    index++;
  }
  return found;
}

const STRIKE = /~~(?=[^\s~])((?:(?!~~)[^\n])*?[^\s~])~~/g;

/** One line's runs: `` `code` ``, `[text](url)`, `~~strike~~`, then latr's `*bold*` and `_italic_`. */
export function parseRuns(line: string, links = true): RichRun[] {
  const found = links
    ? literals(line)
    : literals(line).filter((literal) => literal.code !== undefined);
  let masked = line;
  for (const literal of found) {
    masked =
      masked.slice(0, literal.start) +
      "a".repeat(literal.end - literal.start) +
      masked.slice(literal.end);
  }
  const markers = new Set<number>();
  const ranges: { start: number; end: number; mark: RichMark }[] = [];
  for (const match of masked.matchAll(STRIKE)) {
    const start = match.index;
    const end = start + match[0].length;
    ranges.push({ start, end, mark: "strike" });
    for (const at of [start, start + 1, end - 2, end - 1]) markers.add(at);
  }
  for (const span of markdownSpans(masked)) {
    if (span.kind === "bold" || span.kind === "italic") {
      ranges.push({ start: span.start, end: span.end, mark: span.kind });
    } else if (span.kind === "marker") {
      markers.add(span.start);
    }
  }
  const marksAt = (at: number): RichMark[] =>
    ranges
      .filter((range) => range.start <= at && at < range.end)
      .map((range) => range.mark);
  const runs: RichRun[] = [];
  let index = 0;
  while (index < line.length) {
    const literal = found.find((each) => each.start === index);
    if (literal?.code !== undefined) {
      runs.push({ text: literal.code, marks: ["code"] });
      index = literal.end;
    } else if (literal?.link !== undefined) {
      const outer = marksAt(index);
      for (const run of parseRuns(literal.link.text, false)) {
        runs.push({
          text: run.text,
          marks: [...outer, ...(run.marks ?? [])],
          href: literal.link.href,
        });
      }
      index = literal.end;
    } else {
      if (!markers.has(index)) {
        runs.push({ text: line[index]!, marks: marksAt(index) });
      }
      index++;
    }
  }
  return normalizeRuns(runs);
}

const FENCE = /^```/;
const HEADING = /^(#{1,6}) (.*)$/;
const BULLET = /^[-*+] (.*)$/;
const ORDERED = /^(\d{1,9})\. (.*)$/;
const QUOTE = /^>(?: (.*))?$/;

/** This app's markdown as rich text, a block per line (a code block per fenced stretch). */
export function markdownToRich(markdown: string): RichBlock[] {
  const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
  const blocks: RichBlock[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index]!;
    let match: RegExpExecArray | null;
    if (FENCE.test(line)) {
      const body: string[] = [];
      index++;
      while (index < lines.length && lines[index]!.trim() !== "```") {
        body.push(lines[index]!);
        index++;
      }
      blocks.push({ kind: "code", text: body.join("\n") });
    } else if ((match = HEADING.exec(line)) !== null) {
      blocks.push({
        kind: "heading",
        level: match[1]!.length,
        runs: parseRuns(match[2]!),
      });
    } else if ((match = BULLET.exec(line)) !== null) {
      blocks.push({ kind: "bullet", runs: parseRuns(match[1]!) });
    } else if ((match = ORDERED.exec(line)) !== null) {
      blocks.push({
        kind: "ordered",
        number: Number(match[1]),
        runs: parseRuns(match[2]!),
      });
    } else if ((match = QUOTE.exec(line)) !== null) {
      blocks.push({ kind: "quote", runs: parseRuns(match[1] ?? "") });
    } else {
      blocks.push({ kind: "paragraph", runs: parseRuns(line) });
    }
    index++;
  }
  return blocks;
}

const MARKERS: Record<Exclude<RichMark, "code">, string> = {
  bold: "*",
  italic: "_",
  strike: "~~",
};

// Runs without links as markdown: each style's stretches wrapped in its markers, spaces at a
// stretch's ends left outside them.
function styledMarkdown(runs: readonly RichRun[]): string {
  const pieces = runs.map((run) =>
    run.marks?.includes("code") ? `\`${run.text}\`` : run.text,
  );
  const starts: number[] = [];
  let length = 0;
  for (const piece of pieces) {
    starts.push(length);
    length += piece.length;
  }
  const text = pieces.join("");
  const opens = new Map<number, string[]>();
  const closes = new Map<number, string[]>();
  for (const mark of ["bold", "italic", "strike"] as const) {
    let rangeStart: number | null = null;
    const finish = (end: number) => {
      if (rangeStart === null) return;
      let start = rangeStart;
      let stop = end;
      while (start < stop && /\s/.test(text[start]!)) start++;
      while (stop > start && /\s/.test(text[stop - 1]!)) stop--;
      if (stop > start) {
        opens.set(start, [...(opens.get(start) ?? []), MARKERS[mark]]);
        closes.set(stop, [MARKERS[mark], ...(closes.get(stop) ?? [])]);
      }
      rangeStart = null;
    };
    runs.forEach((run, index) => {
      if (run.marks?.includes(mark)) rangeStart ??= starts[index]!;
      else finish(starts[index]!);
    });
    finish(length);
  }
  let out = "";
  for (let index = 0; index <= text.length; index++) {
    out += (closes.get(index) ?? []).join("");
    out += (opens.get(index) ?? []).join("");
    if (index < text.length) out += text[index];
  }
  return out;
}

// A link's address as a markdown link target: whitespace percent-encoded, and parentheses too unless balanced one deep.
function linkTarget(href: string): string {
  const spaced = href.replace(/\s/g, (space) => encodeURIComponent(space));
  return WHOLE_LINK_TARGET.test(spaced)
    ? spaced
    : spaced.replace(/[()]/g, (paren) => (paren === "(" ? "%28" : "%29"));
}

/** One line's runs as markdown: a link whose text is its address stays a bare URL. */
export function runsToMarkdown(runs: readonly RichRun[]): string {
  let out = "";
  let index = 0;
  while (index < runs.length) {
    const href = runs[index]!.href;
    let end = index + 1;
    while (end < runs.length && runs[end]!.href === href) end++;
    const group = runs.slice(index, end);
    const inner = styledMarkdown(group);
    if (href === undefined || (group.length === 1 && inner === href)) {
      out += inner;
    } else {
      out += `[${inner}](${linkTarget(href)})`;
    }
    index = end;
  }
  return out;
}

/** Rich text as this app's markdown, the message's plain-text part: a line per block. */
export function richToMarkdown(blocks: readonly RichBlock[]): string {
  return blocks
    .map((block) => {
      switch (block.kind) {
        case "paragraph":
          return runsToMarkdown(block.runs);
        case "heading":
          return `${"#".repeat(block.level)} ${runsToMarkdown(block.runs)}`;
        case "bullet":
          return `- ${runsToMarkdown(block.runs)}`;
        case "ordered":
          return `${block.number}. ${runsToMarkdown(block.runs)}`;
        case "quote": {
          const line = runsToMarkdown(block.runs);
          return line === "" ? ">" : `> ${line}`;
        }
        default:
          return `\`\`\`\n${block.text}\n\`\`\``;
      }
    })
    .join("\n");
}

const IMAGE_PARTS = /^!\[([^\]]*)\]\(cid:([^)]*)\)$/i;
const TAGS: Record<RichMark, string> = {
  bold: "b",
  italic: "i",
  strike: "s",
  code: "code",
};

// Plain text as HTML: escaped, bare URLs as links (`www.` ones to `http://`), `![alt](cid:id)` as an image.
function textHtml(text: string): string {
  let html = "";
  let at = 0;
  for (const { start, end, kind } of markdownSpans(text)) {
    if ((kind !== "url" && kind !== "image") || start < at) continue;
    html += escapeHtml(text.slice(at, start));
    const slice = text.slice(start, end);
    if (kind === "url") {
      const href = /^www\./i.test(slice) ? `http://${slice}` : slice;
      html += `<a href="${escapeHtml(href)}">${escapeHtml(slice)}</a>`;
    } else {
      const [, alt, contentId] = IMAGE_PARTS.exec(slice)!;
      html += `<img src="cid:${escapeHtml(contentId!)}" alt="${escapeHtml(alt!)}">`;
    }
    at = end;
  }
  return html + escapeHtml(text.slice(at));
}

/** One line's runs as HTML: `<a href>` around each link's runs, then `<b>`, `<i>`, `<s>` and `<code>` inward. */
export function runsToHtml(runs: readonly RichRun[]): string {
  let html = "";
  let index = 0;
  while (index < runs.length) {
    const href = runs[index]!.href;
    let inner = "";
    while (index < runs.length && runs[index]!.href === href) {
      const run = runs[index]!;
      const marks = run.marks ?? [];
      let piece =
        href === undefined && marks.length === 0
          ? textHtml(run.text)
          : escapeHtml(run.text);
      for (const mark of [...marks].reverse()) {
        piece = `<${TAGS[mark]}>${piece}</${TAGS[mark]}>`;
      }
      inner += piece;
      index++;
    }
    html +=
      href === undefined ? inner : `<a href="${escapeHtml(href)}">${inner}</a>`;
  }
  return html;
}

/**
 * Rich text as the message's HTML. Paragraphs are lines joined with `<br>`, as typed; headings are
 * `<h1>`–`<h6>`; neighboring list items share one `<ul>` or `<ol>` (with `start` when the first
 * isn't 1); neighboring quote lines one `<blockquote>`, joined with `<br>`; a code block is
 * `<pre><code>`. Blank lines at the start and end are left out, as the plain-text part leaves them.
 */
export function richToHtml(all: readonly RichBlock[]): string {
  const blank = (block: RichBlock | undefined): boolean =>
    block?.kind === "paragraph" &&
    block.runs.every((run) => /^[ \t]*$/.test(run.text));
  let first = 0;
  let last = all.length;
  while (first < last && blank(all[first])) first++;
  while (last > first && blank(all[last - 1])) last--;
  const blocks = all.slice(first, last);
  let html = "";
  let afterLine = false;
  let index = 0;
  while (index < blocks.length) {
    const block = blocks[index]!;
    if (block.kind === "paragraph") {
      if (afterLine) html += "<br>";
      html += runsToHtml(block.runs);
      afterLine = true;
      index++;
      continue;
    }
    afterLine = false;
    if (block.kind === "heading") {
      html += `<h${block.level}>${runsToHtml(block.runs)}</h${block.level}>`;
      index++;
    } else if (block.kind === "code") {
      html += `<pre><code>${escapeHtml(block.text)}</code></pre>`;
      index++;
    } else {
      const kind = block.kind;
      const group: RichBlock[] = [];
      while (index < blocks.length && blocks[index]!.kind === kind) {
        group.push(blocks[index]!);
        index++;
      }
      const lines = group.map((item) =>
        "runs" in item ? runsToHtml(item.runs) : "",
      );
      if (kind === "quote") {
        html += `<blockquote>${lines.join("<br>")}</blockquote>`;
      } else {
        const items = lines.map((line) => `<li>${line}</li>`).join("");
        const first = group[0]!;
        const start =
          first.kind === "ordered" && first.number !== 1
            ? ` start="${first.number}"`
            : "";
        html +=
          kind === "bullet" ? `<ul>${items}</ul>` : `<ol${start}>${items}</ol>`;
      }
    }
  }
  return html;
}
