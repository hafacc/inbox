import { escapeHtml, MONTH_ABBREVIATIONS, trimLines, zeroPad } from "../format";
import type { Mailbox } from "./address";
import { normalizeNewlines } from "./encoding";

/** The message a reply quotes. */
export type QuotedMessage = {
  /** its sender (the first `From` mailbox) */
  from: Mailbox;
  /** when it was sent, epoch milliseconds (its `Date`, or Gmail's `internalDate`) */
  date: number;
  /** its plain-text body */
  text: string;
  /** its HTML body, or null to quote `text` in the HTML part too */
  html: string | null;
};

/** A body split into what the sender wrote and the quoted message trailing it. */
export type QuoteSplit = {
  /** everything before the quote; the whole input when there is none */
  body: string;
  /** the trailing quote, attribution line included, or null when none was found */
  quote: string | null;
};

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

/** Inline style Gmail puts on its quote blockquote. */
export const QUOTE_BLOCKQUOTE_STYLE =
  "margin:0px 0px 0px 0.8ex;border-left:1px solid rgb(204,204,204);padding-left:1ex";

/**
 * The line above a quote, in the device's time zone and 24-hour time:
 * `On Mon, Nov 2, 2026 at 11:50, Maya Chen <maya.chen@gmail.example.com> wrote:`; without a name the
 * address alone in angle brackets (`On …, <maya@example.com> wrote:`).
 */
export function quoteAttribution(from: Mailbox, date: number): string {
  const when = new Date(date);
  const day = `${WEEKDAYS[when.getDay()]}, ${MONTH_ABBREVIATIONS[when.getMonth()]} ${when.getDate()}, ${when.getFullYear()}`;
  const time = `${zeroPad(when.getHours())}:${zeroPad(when.getMinutes())}`;
  const name = from.name.trim();
  const who = name === "" ? `<${from.email}>` : `${name} <${from.email}>`;
  return `On ${day} at ${time}, ${who} wrote:`;
}

function textLines(text: string): string[] {
  const trimmed = trimLines(text);
  return trimmed === "" ? [] : trimmed.split("\n");
}

/**
 * The quote for the plain-text part: the attribution, a blank line, then each line of the
 * original's text (trailing spaces and leading/trailing blank lines removed) as `> line`, a blank
 * line as `>`. Lines already quoted gain another level (`> > …`).
 */
export function quoteText(quote: QuotedMessage): string {
  const lines = textLines(quote.text).map((line) =>
    line === "" ? ">" : `> ${line}`,
  );
  const attribution = quoteAttribution(quote.from, quote.date);
  return lines.length === 0
    ? attribution
    : `${attribution}\n\n${lines.join("\n")}`;
}

/**
 * The original's HTML as it goes inside the quote: the inside of `<body>` when there is one, with
 * the doctype, `<head>`, comments, scripts and `<html>`/`<body>` tags removed. Styles inside the
 * body stay.
 */
export function quotableHtml(html: string): string {
  let inner = html;
  const body = /<body\b[^>]*>([\s\S]*?)(<\/body\s*>|$)/i.exec(inner);
  if (body !== null) inner = body[1] ?? "";
  return inner
    .replace(/<!doctype[^>]*>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<head\b[\s\S]*?<\/head\s*>/gi, "")
    .replace(/<script\b[\s\S]*?<\/script\s*>/gi, "")
    .replace(/<\/?(html|body)\b[^>]*>/gi, "")
    .trim();
}

/**
 * The Content-IDs a quoted original's HTML refers to as `cid:` URLs, in order of first use, each
 * once: the reply must attach those inline parts of the original again (`ComposeInput.inlineImages`,
 * same Content-ID) or they show broken. Looks only at what `quotableHtml` keeps; a `cid:` URL ends
 * at a quote, whitespace, `)`, `>` or `;`, and is percent-decoded (RFC 2392; kept as is when that
 * fails). Empty for null.
 */
export function quotedContentIds(html: string | null): string[] {
  if (html === null) return [];
  const ids: string[] = [];
  for (const match of quotableHtml(html).matchAll(/\bcid:([^"'\s)>;]+)/gi)) {
    let id = match[1] ?? "";
    try {
      id = decodeURIComponent(id);
    } catch {}
    if (id !== "" && !ids.includes(id)) ids.push(id);
  }
  return ids;
}

/** Plain text as HTML for a quote: escaped, lines joined by `<br>`, in `<div dir="auto">`. */
function textAsHtml(text: string): string {
  return `<div dir="auto">${textLines(text).map(escapeHtml).join("<br>")}</div>`;
}

/**
 * The quote for the HTML part, in Gmail's markup so Gmail (and clients that know it) fold it away:
 * `<div class="gmail_quote gmail_quote_container">` holding `<div dir="ltr" class="gmail_attr">`
 * with the attribution and `<br>`, then `<blockquote class="gmail_quote" style="…">` holding the
 * original (`quotableHtml` of its HTML, or its text as HTML).
 */
export function quoteHtml(quote: QuotedMessage): string {
  const inner =
    quote.html === null ? textAsHtml(quote.text) : quotableHtml(quote.html);
  return (
    '<div class="gmail_quote gmail_quote_container">' +
    `<div dir="ltr" class="gmail_attr">${escapeHtml(quoteAttribution(quote.from, quote.date))}<br></div>` +
    `<blockquote class="gmail_quote" style="${QUOTE_BLOCKQUOTE_STYLE}">${inner}</blockquote>` +
    "</div>"
  );
}

const ORIGINAL_MESSAGE = /^-{2,}\s*Original Message\s*-{2,}$/i;

/**
 * Find the quote trailing a plain-text body.
 *
 * A quote is the last run of lines starting with `>` (blank lines allowed inside), with nothing but
 * blank lines after it, under an attribution: the nearest non-blank line above it, ending in `:`
 * and not itself quoted (two lines when the attribution wrapped: the line above starts with `On `
 * and this one doesn't). Outlook's `-----Original Message-----` line starts a quote that runs to
 * the end. The body keeps everything above, trailing blank lines removed. Without an attribution,
 * or when the body above would be blank, there is no quote.
 */
export function splitQuotedText(text: string): QuoteSplit {
  const lines = normalizeNewlines(text).split("\n");
  const isBlank = (index: number) => (lines[index] ?? "").trim() === "";
  const bodyBefore = (index: number) =>
    lines.slice(0, index).join("\n").replace(/\s+$/, "");
  const split = (index: number): QuoteSplit => {
    const body = bodyBefore(index);
    return body.trim() === ""
      ? { body: text, quote: null }
      : {
          body,
          quote: lines.slice(index).join("\n").replace(/\s+$/, ""),
        };
  };

  const outlook = lines.findIndex((line) => ORIGINAL_MESSAGE.test(line.trim()));
  if (outlook >= 0) return split(outlook);

  let end = lines.length - 1;
  while (end >= 0 && isBlank(end)) end--;
  let start = end + 1;
  for (let index = end; index >= 0; index--) {
    const line = lines[index] ?? "";
    if (line.startsWith(">")) start = index;
    else if (line.trim() !== "") break;
  }
  if (start > end) return { body: text, quote: null };

  let attribution = start - 1;
  while (attribution >= 0 && isBlank(attribution)) attribution--;
  const line = (lines[attribution] ?? "").trim();
  if (attribution < 0 || !line.endsWith(":")) {
    return { body: text, quote: null };
  }
  const above = (lines[attribution - 1] ?? "").trim();
  if (!/^On\s/.test(line) && /^On\s/.test(above)) attribution--;
  return split(attribution);
}

const HTML_QUOTE_START = new RegExp(
  [
    String.raw`<(div|blockquote)\b[^>]*\bclass\s*=\s*["'][^"']*\bgmail_quote(?:_container)?\b[^>]*>`,
    String.raw`<(blockquote)\b[^>]*\btype\s*=\s*["']?cite\b[^>]*>`,
    String.raw`<(div)\b[^>]*\bclass\s*=\s*["'][^"']*\b(?:moz-cite-prefix|yahoo_quoted)\b[^>]*>`,
    String.raw`<(div)\b[^>]*\bid\s*=\s*["']?(?:divRplyFwdMsg|appendonsend)\b[^>]*>`,
  ].join("|"),
  "gi",
);

function visibleText(html: string): string {
  return html
    .replace(/<(style|script)\b[\s\S]*?<\/\1\s*>/gi, "")
    .replace(/<[^>]*>/g, "")
    .replace(/&nbsp;|&#160;| /gi, " ")
    .trim();
}

// Opening and closing tags of each element a quote marker can be, `/` captured on a closing tag.
const ELEMENT_TAGS: Readonly<Record<string, RegExp>> = {
  div: /<(\/?)div\b[^>]*>/gi,
  blockquote: /<(\/?)blockquote\b[^>]*>/gi,
};

/** Index just past the tag closing the `name` element opened at `start`, or the end of `html`. */
function elementEnd(html: string, name: string, start: number): number {
  const tags = ELEMENT_TAGS[name]!;
  tags.lastIndex = start;
  let depth = 0;
  for (let match = tags.exec(html); match !== null; match = tags.exec(html)) {
    depth += match[1] === "/" ? -1 : 1;
    if (depth === 0) return match.index + match[0].length;
  }
  return html.length;
}

type QuoteMarker = {
  /** index of the opening tag */
  start: number;
  /** element name, lower-case */
  name: string;
  /** whether the quote runs to the end of the body whatever follows (Outlook) */
  toEnd: boolean;
};

function quoteMarkers(html: string): QuoteMarker[] {
  const markers: QuoteMarker[] = [];
  HTML_QUOTE_START.lastIndex = 0;
  for (
    let match = HTML_QUOTE_START.exec(html);
    match !== null;
    match = HTML_QUOTE_START.exec(html)
  ) {
    markers.push({
      start: match.index,
      name: (
        match.slice(1).find((group) => group !== undefined) ?? "div"
      ).toLowerCase(),
      toEnd: /divRplyFwdMsg|appendonsend/i.test(match[0]),
    });
  }
  return markers;
}

/**
 * Find the quote trailing an HTML body, by the markup mail clients put around one: Gmail's
 * `gmail_quote` (or `gmail_quote_container`) div or blockquote, Apple Mail's and Thunderbird's
 * `<blockquote type="cite">`, Thunderbird's `moz-cite-prefix` and Yahoo's `yahoo_quoted` divs, and
 * Outlook's `divRplyFwdMsg` / `appendonsend` divs. The quote starts at the first such element
 * after which every bit of visible text lies inside other such elements (so Apple Mail's separate
 * attribution blockquote goes with the quote, while an inline reply's interleaved answers stop it);
 * Outlook's markers start a quote that runs to the end whatever follows.
 *
 * The split is plain string slicing at the element's opening tag, so either half may hold
 * unbalanced tags; render each through the HTML sanitizer, which balances them. When the body
 * before would have no visible text, there is no quote. Either way the body ends at its last
 * visible content (`trimTrailingBlankHtml`), so the line break mail puts before a quote doesn't
 * show as a blank line.
 */
export function splitQuotedHtml(html: string): QuoteSplit {
  const markers = quoteMarkers(html);
  // Each element's end is found on first need, and only once.
  const ends: (number | undefined)[] = [];
  const endOf = (index: number): number => {
    const marker = markers[index]!;
    ends[index] ??= elementEnd(html, marker.name, marker.start);
    return ends[index];
  };
  // Whether only quotes follow a marker's element. The walk from one marker reaches the next
  // marker after it and then goes on exactly as that marker's own walk does, so answers are shared.
  const quotesOnly: (boolean | undefined)[] = [];
  const onlyQuotesAfter = (index: number): boolean => {
    const chain: number[] = [];
    let current = index;
    let answer: boolean | undefined;
    while (answer === undefined) {
      const known = quotesOnly[current];
      if (known !== undefined) {
        answer = known;
        break;
      }
      chain.push(current);
      const cursor = endOf(current);
      let next = current + 1;
      while (next < markers.length && markers[next]!.start < cursor) next++;
      const nextMarker = markers[next];
      if (nextMarker === undefined) {
        answer = visibleText(html.slice(cursor)) === "";
      } else if (visibleText(html.slice(cursor, nextMarker.start)) !== "") {
        answer = false;
      } else if (nextMarker.toEnd) {
        answer = true;
      } else {
        current = next;
      }
    }
    for (const visited of chain) quotesOnly[visited] = answer;
    return answer;
  };
  for (const [index, marker] of markers.entries()) {
    if (!marker.toEnd && !onlyQuotesAfter(index)) continue;
    const body = html.slice(0, marker.start);
    return visibleText(body) === ""
      ? { body: trimTrailingBlankHtml(html), quote: null }
      : { body: trimTrailingBlankHtml(body), quote: html.slice(marker.start) };
  }
  return { body: trimTrailingBlankHtml(html), quote: null };
}

// Elements that are blank when they hold nothing visible, unless sized or colored by attributes.
const BLANK_ELEMENTS = new Set([
  "div",
  "p",
  "span",
  "font",
  "b",
  "i",
  "u",
  "strong",
  "em",
]);
const OPENING_TAG = /^<([a-z]+)\b[^>]*>$/i;
const CLOSING_TAG = /^<\/([a-z]+)\s*>$/i;
const LINE_BREAK = /^<br\b[^>]*>$/i;
const DRAWN_ATTRIBUTE = /\s(?:style|height|bgcolor|background)\s*=/i;
const SPACE_ENTITIES = ["&nbsp;", "&#160;", "&#xa0;"];

/** Whether `tag` opens a blank element named `name` (any such name when omitted). */
function opensBlank(tag: string, name?: string): boolean {
  const match = OPENING_TAG.exec(tag);
  const tagName = match?.[1]?.toLowerCase();
  return (
    tagName !== undefined &&
    BLANK_ELEMENTS.has(tagName) &&
    (name === undefined || tagName === name) &&
    !DRAWN_ATTRIBUTE.test(tag)
  );
}

// Where `html` up to `end` stops once trailing blanks are cut; opening tags only with `cutOpening`.
function blankStart(html: string, end: number, cutOpening: boolean): number {
  let cut = end;
  for (;;) {
    while (cut > 0 && /\s/.test(html[cut - 1]!)) cut--;
    const entity = SPACE_ENTITIES.find(
      (candidate) =>
        html.slice(Math.max(0, cut - candidate.length), cut).toLowerCase() ===
        candidate,
    );
    if (entity !== undefined) {
      cut -= entity.length;
      continue;
    }
    if (html[cut - 1] !== ">") return cut;
    const tagStart = html.lastIndexOf("<", cut - 1);
    if (tagStart < 0) return cut;
    const tag = html.slice(tagStart, cut);
    if (LINE_BREAK.test(tag) || (cutOpening && opensBlank(tag))) {
      cut = tagStart;
      continue;
    }
    const closing = CLOSING_TAG.exec(tag)?.[1]?.toLowerCase();
    if (closing === undefined || !BLANK_ELEMENTS.has(closing)) return cut;
    const inner = blankStart(html, tagStart, false);
    const openStart = html.lastIndexOf("<", inner - 1);
    if (
      html[inner - 1] !== ">" ||
      openStart < 0 ||
      !opensBlank(html.slice(openStart, inner), closing)
    ) {
      return cut;
    }
    cut = openStart;
  }
}

/**
 * HTML without the blank lines at its end: trailing whitespace, `&nbsp;`, `<br>`s, and `div`, `p`,
 * `span`, `font`, `b`, `i`, `u`, `strong` and `em` elements holding only those, left open or closed.
 * An element with a `style`, `height`, `bgcolor` or `background` attribute counts as drawn and stays.
 */
export function trimTrailingBlankHtml(html: string): string {
  return html.slice(0, blankStart(html, html.length, true));
}
