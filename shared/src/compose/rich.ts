// The composer's rich text as both editors hand it over (web: Tiptap, Android: Compose Rich Editor),
// and the two forms a message is sent in: HTML and markdown with this app's markers (`*bold*`,
// `_italic_`). Android :core ports this (core/compose/Rich.kt); both run
// shared/testdata/rich-fixtures.json.

import { escapeHtml } from "../format";
import { findWebLinks, linkAddress, safeHref } from "../web-links";
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
  /** a bare web address, whose markers style nothing */
  bare?: boolean;
};

// A link target: no spaces, parentheses only in balanced pairs one deep, as `runsToMarkdown` writes it.
const LINK_TARGET = String.raw`(?:[^\s()]|\([^\s()]*\))+`;
const LINK = new RegExp(String.raw`^\[([^\]\n]+)\]\((${LINK_TARGET})\)`);
const WHOLE_LINK_TARGET = new RegExp(`^${LINK_TARGET}$`);

// A line's literals; `bare` adds its bare web addresses as links to themselves.
function literals(line: string, bare: boolean): Literal[] {
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
      // A link to anything but a web or mail address stays as typed.
      const href = match === null ? null : safeHref(match[2]!);
      if (match !== null && href !== null) {
        const [whole, text] = match;
        found.push({
          start: index,
          end: index + whole.length,
          link: { text: text!, href },
        });
        index += whole.length;
        continue;
      }
    }
    index++;
  }
  // Bare web addresses outside those (and outside inline images) are links to themselves.
  for (const span of bare ? markdownSpans(line) : []) {
    if (span.kind !== "url") continue;
    if (found.some((each) => each.start < span.end && span.start < each.end)) {
      continue;
    }
    const text = line.slice(span.start, span.end);
    const href = linkAddress(text);
    if (href === null) continue;
    found.push({
      start: span.start,
      end: span.end,
      link: { text, href },
      bare: true,
    });
  }
  return found.sort((first, second) => first.start - second.start);
}

const STRIKE = /~~(?=[^\s~])((?:(?!~~)[^\n])*?[^\s~])~~/g;

/**
 * One line's runs: `` `code` ``, `[text](url)` (only to an http, https or mailto address), and
 * with `bare` its bare web addresses as links, `~~strike~~`, then latr's `*bold*` and `_italic_`.
 */
export function parseRuns(line: string, links = true, bare = true): RichRun[] {
  const found = links
    ? literals(line, bare)
    : literals(line, false).filter((literal) => literal.code !== undefined);
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
    } else if (literal?.link !== undefined && literal.bare) {
      runs.push({
        text: literal.link.text,
        marks: marksAt(index),
        href: literal.link.href,
      });
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

/**
 * This app's markdown as rich text, a block per line (a code block per fenced stretch). With
 * `bareLinks` (markdown typed elsewhere: a signature, a shared text) a bare web address is a link
 * to itself; without it (markdown the composer wrote, which writes every link out) only a
 * `[text](url)` is, so an address the user took a link off stays plain.
 */
export function markdownToRich(
  markdown: string,
  bareLinks = true,
): RichBlock[] {
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
        runs: parseRuns(match[2]!, true, bareLinks),
      });
    } else if ((match = BULLET.exec(line)) !== null) {
      blocks.push({
        kind: "bullet",
        runs: parseRuns(match[1]!, true, bareLinks),
      });
    } else if ((match = ORDERED.exec(line)) !== null) {
      blocks.push({
        kind: "ordered",
        number: Number(match[1]),
        runs: parseRuns(match[2]!, true, bareLinks),
      });
    } else if ((match = QUOTE.exec(line)) !== null) {
      blocks.push({
        kind: "quote",
        runs: parseRuns(match[1] ?? "", true, bareLinks),
      });
    } else {
      blocks.push({
        kind: "paragraph",
        runs: parseRuns(line, true, bareLinks),
      });
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

/**
 * One line's runs as markdown, every link written out as `[text](url)`, so the composer reading
 * it back (`markdownToRich` without bare links) has the same links and no others.
 */
export function runsToMarkdown(runs: readonly RichRun[]): string {
  let out = "";
  let index = 0;
  while (index < runs.length) {
    const href = runs[index]!.href;
    let end = index + 1;
    while (end < runs.length && runs[end]!.href === href) end++;
    const group = runs.slice(index, end);
    const inner = styledMarkdown(group);
    if (href === undefined) {
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

// Plain text as HTML: escaped, `![alt](cid:id)` as an image. A web address in it is no link: the
// runs' links are already marked.
function textHtml(text: string): string {
  let html = "";
  let at = 0;
  for (const { start, end, kind } of markdownSpans(text)) {
    if (kind !== "image" || start < at) continue;
    html += escapeHtml(text.slice(at, start));
    const [, alt, contentId] = IMAGE_PARTS.exec(text.slice(start, end))!;
    html += `<img src="cid:${escapeHtml(contentId!)}" alt="${escapeHtml(alt!)}">`;
    at = end;
  }
  return html + escapeHtml(text.slice(at));
}

/**
 * One line's runs as HTML: `<a href>` around each link's runs (only to an http, https or mailto
 * address), then `<b>`, `<i>`, `<s>` and `<code>` inward.
 */
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
    const safe = href === undefined ? null : safeHref(href);
    html +=
      safe === null ? inner : `<a href="${escapeHtml(safe)}">${inner}</a>`;
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

// An address as a link's text is compared with it: no http, https or mailto scheme, no trailing slash, lower case.
function linkKey(address: string): string {
  return address
    .replace(/^(?:https?:\/\/|mailto:)/i, "")
    .replace(/\/$/, "")
    .toLowerCase();
}

/** Whether a link's text is just its address: equal once a scheme (http, https, mailto), a trailing slash and case are ignored. */
export function selfNamedLink(text: string, href: string): boolean {
  return linkKey(text) === linkKey(href);
}

const EMAIL = /(?<![\w.%+-])[\w.%+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+(?![\w-])/gi;
const IMAGE = /!\[[^\]\n]*\]\(cid:[^\s()]+\)/gi;

type Span = { start: number; end: number };

const overlaps = (span: Span, others: readonly Span[]): boolean =>
  others.some((other) => other.start < span.end && span.start < other.end);

// The web and email addresses in plain text, in order.
function addressSpans(text: string): Span[] {
  const spans: Span[] = findWebLinks(text).map(({ start, end }) => ({
    start,
    end,
  }));
  for (const match of text.matchAll(EMAIL)) {
    const span = { start: match.index, end: match.index + match[0].length };
    if (!overlaps(span, spans)) spans.push(span);
  }
  return spans.sort((first, second) => first.start - second.start);
}

// An address in a markdown line: a `[text](url)` link (`href` set) or a bare one (`href` null).
type LineAddress = Span & { text: string; href: string | null };

// A markdown line's links and its bare addresses outside them, inline code and images, in order.
function lineAddresses(line: string): LineAddress[] {
  const found = literals(line, false);
  const hidden: Span[] = [...found];
  for (const match of line.matchAll(IMAGE)) {
    hidden.push({ start: match.index, end: match.index + match[0].length });
  }
  let masked = line;
  for (const { start, end } of hidden) {
    masked =
      masked.slice(0, start) + "a".repeat(end - start) + masked.slice(end);
  }
  const addresses: LineAddress[] = [];
  for (const { start, end, link } of found) {
    if (link !== undefined) addresses.push({ start, end, ...link });
  }
  for (const span of addressSpans(masked)) {
    if (overlaps(span, hidden)) continue;
    addresses.push({
      ...span,
      text: line.slice(span.start, span.end),
      href: null,
    });
  }
  return addresses.sort((first, second) => first.start - second.start);
}

// Markdown with `change` applied to each line outside fenced code blocks.
function mapProseLines(
  markdown: string,
  change: (line: string) => string,
): string {
  let fenced = false;
  return markdown
    .split("\n")
    .map((line) => {
      if (fenced) {
        fenced = line.trim() !== "```";
        return line;
      } else if (FENCE.test(line)) {
        fenced = true;
        return line;
      } else {
        return change(line);
      }
    })
    .join("\n");
}

/**
 * The composer's markdown as the message's plain-text part: each link whose text is just its address
 * (`selfNamedLink`) written as that bare address, unless the line read back wouldn't find the
 * address there (as when it runs into the next word); every other link stays `[text](url)`.
 * `draftLinks` takes the links back from the HTML part.
 */
export function plainLinks(markdown: string): string {
  return mapProseLines(markdown, (line) => {
    let bare = lineAddresses(line).filter(
      (address) =>
        address.href !== null && selfNamedLink(address.text, address.href),
    );
    for (;;) {
      let text = "";
      let at = 0;
      const spans: Span[] = [];
      for (const address of bare) {
        text += line.slice(at, address.start);
        spans.push({
          start: text.length,
          end: text.length + address.text.length,
        });
        text += address.text;
        at = address.end;
      }
      text += line.slice(at);
      const found = lineAddresses(text).filter(
        (address) => address.href === null,
      );
      const kept = bare.filter((_, index) =>
        found.some(
          (address) =>
            address.start === spans[index]!.start &&
            address.end === spans[index]!.end,
        ),
      );
      if (kept.length === bare.length) return text;
      bare = kept;
    }
  });
}

const ENTITY = /&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));/gi;
const NAMED_ENTITIES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: "\u00a0",
};
const HTML_PIECE = /<[^>]*>|[^<]+/g;
const TAG_NAME = /^<\/?\s*([a-z][a-z0-9]*)/i;
const HREF = /\shref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/i;
const LINE_TAGS: ReadonlySet<string> = new Set([
  "br",
  "p",
  "div",
  "li",
  "ul",
  "ol",
  "blockquote",
  "pre",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "hr",
  "tr",
]);

// HTML text with its character references read.
function unescapeHtml(text: string): string {
  return text.replace(ENTITY, (whole, decimal, hex, name) => {
    if (decimal !== undefined) return String.fromCodePoint(Number(decimal));
    if (hex !== undefined)
      return String.fromCodePoint(Number.parseInt(hex, 16));
    return NAMED_ENTITIES[name.toLowerCase()] ?? whole;
  });
}

// Each address's appearances in HTML's text, in order, by `linkKey`: the link's address where a
// link of plain text that is just its address shows it, null where it shows unlinked. Text in other
// links and in code doesn't count.
function htmlAddresses(html: string): Map<string, (string | null)[]> {
  let visible = "";
  const hidden: Span[] = [];
  const linked: { start: number; key: string; href: string }[] = [];
  let anchor: { start: number; href: string | null; plain: boolean } | null =
    null;
  let codeStart: number | null = null;
  let codeDepth = 0;
  for (const [piece] of html.matchAll(HTML_PIECE)) {
    if (!piece.startsWith("<")) {
      visible += unescapeHtml(piece);
      continue;
    }
    const name = TAG_NAME.exec(piece)?.[1]?.toLowerCase();
    if (name === undefined) continue;
    const closing = piece.startsWith("</");
    if (name === "a") {
      if (!closing) {
        const match = HREF.exec(piece);
        const href =
          match === null
            ? null
            : unescapeHtml(match[1] ?? match[2] ?? match[3]!);
        anchor = { start: visible.length, href, plain: true };
      } else if (anchor !== null) {
        const span = { start: anchor.start, end: visible.length };
        hidden.push(span);
        const text = visible.slice(span.start, span.end);
        const safe = anchor.href === null ? null : safeHref(anchor.href);
        if (anchor.plain && safe !== null && selfNamedLink(text, safe)) {
          linked.push({ start: span.start, key: linkKey(text), href: safe });
        }
        anchor = null;
      }
      continue;
    }
    if (anchor !== null) anchor.plain = false;
    if (name === "code" || name === "pre") {
      if (!closing) {
        if (codeDepth === 0) codeStart = visible.length;
        codeDepth++;
      } else if (codeDepth > 0) {
        codeDepth--;
        if (codeDepth === 0)
          hidden.push({ start: codeStart!, end: visible.length });
      }
    }
    if (LINE_TAGS.has(name)) visible += "\n";
  }
  const appearances: { start: number; key: string; href: string | null }[] = [
    ...linked,
  ];
  for (const span of addressSpans(visible)) {
    if (overlaps(span, hidden)) continue;
    appearances.push({
      start: span.start,
      key: linkKey(visible.slice(span.start, span.end)),
      href: null,
    });
  }
  const byKey = new Map<string, (string | null)[]>();
  for (const { key, href } of appearances.sort(
    (first, second) => first.start - second.start,
  )) {
    byKey.set(key, [...(byKey.get(key) ?? []), href]);
  }
  return byKey;
}

/**
 * A reopened draft's markdown (read from its plain-text part, see `plainLinks`) with its links back
 * from its HTML part: a bare address becomes `[address](url)` only where the HTML links it, matched
 * by order among that address's appearances in each, so an address whose link was taken off stays
 * plain. Links already written out stay as they are.
 */
export function draftLinks(markdown: string, html: string | null): string {
  if (html === null) return markdown;
  const inHtml = htmlAddresses(html);
  const seen = new Map<string, number>();
  return mapProseLines(markdown, (line) => {
    let out = "";
    let at = 0;
    for (const address of lineAddresses(line)) {
      if (address.href !== null && !selfNamedLink(address.text, address.href)) {
        continue;
      }
      const key = linkKey(address.text);
      const index = seen.get(key) ?? 0;
      seen.set(key, index + 1);
      const href = inHtml.get(key)?.[index] ?? null;
      if (address.href === null && href !== null) {
        out += `${line.slice(at, address.start)}[${address.text}](${linkTarget(href)})`;
        at = address.end;
      }
    }
    return out + line.slice(at);
  });
}
