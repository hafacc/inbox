import { trimLines } from "../format";
import { type QuotedMessage, quoteHtml, quoteText } from "./quote";
import { markdownToRich, plainLinks, richToHtml } from "./rich";

/** Separator line between body and signature in the plain-text part ("dash dash space"). */
export const SIGNATURE_SEPARATOR = "-- ";

/** The two readable versions of a message body. */
export type BodyParts = {
  /** `text/html` part */
  html: string;
  /** `text/plain` part, LF line breaks */
  text: string;
};

/**
 * Markdown as an HTML fragment, rendered from `markdownToText(source)` so both parts start from the
 * same trimmed lines: read as rich text by `markdownToRich` (bare web addresses linked with
 * `bareLinks`, as for a signature; not for the composer's own markdown, which writes its links
 * out) and written by `richToHtml`.
 */
export function markdownToHtml(source: string, bareLinks = true): string {
  return richToHtml(markdownToRich(markdownToText(source), bareLinks));
}

/** Markdown as its plain-text alternative: the source as typed, LF breaks, trailing spaces and blank lines trimmed. */
export function markdownToText(source: string): string {
  return trimLines(source);
}

/**
 * The HTML and plain-text parts of a body with an optional signature (both markdown) and, for a
 * reply, the quoted original.
 *
 * HTML: `<div dir="auto">` + body (`bodyHtml`, the editor's HTML of it, when given) + `</div>`, then `<div class="signature">` + signature + `</div>`,
 * then for a reply `<br>` and `quoteHtml(quote)`.
 * Text: body (`plainLinks`: a link whose text is its address as that bare address), then a blank line, `-- `, and the signature; then for a reply a blank line and
 * `quoteText(quote)`. An empty or blank signature adds nothing.
 */
export function bodyParts(
  body: string,
  signature: string | null,
  quote: QuotedMessage | null = null,
  bodyHtml: string | null = null,
): BodyParts {
  const sign = signature === null ? "" : markdownToText(signature);
  let html = `<div dir="auto">${bodyHtml ?? markdownToHtml(body, false)}</div>`;
  let text = plainLinks(markdownToText(body));
  if (sign !== "") {
    html += `<div class="signature">${markdownToHtml(sign)}</div>`;
    text = `${text === "" ? "" : `${text}\n\n`}${SIGNATURE_SEPARATOR}\n${sign}`;
  }
  if (quote !== null) {
    html += `<br>${quoteHtml(quote)}`;
    text = `${text === "" ? "" : `${text}\n\n`}${quoteText(quote)}`;
  }
  return { html, text };
}

/**
 * HTML reduced to the form fixtures compare: self-closing slashes dropped (`<br />` → `<br>`), `&#39;` read as `'`, whitespace between
 * tags removed, ends trimmed.
 */
export function normalizeHtml(html: string): string {
  return html
    .replace(/\s*\/>/g, ">")
    .replace(/&#39;/g, "'")
    .replace(/>\s+</g, "><")
    .trim();
}
