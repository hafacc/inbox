import { formatAddressList, type Mailbox, quotedString } from "./address";
import {
  base64Lines,
  CRLF,
  encodedWords,
  encodeHeaderText,
  foldHeader,
  isPrintableAscii,
  quotedPrintable,
  utf8,
} from "./encoding";
import { bodyParts } from "./markdown";
import type { QuotedMessage } from "./quote";

/** A file sent with a message. */
export type MimeAttachment = {
  /** file name shown to the recipient */
  filename: string;
  /** content type, e.g. `application/pdf` */
  mimeType: string;
  /** contents */
  data: Uint8Array;
};

/** An image shown inside the HTML body, referenced there as `cid:<contentId>`. */
export type MimeInlineImage = MimeAttachment & {
  /** Content-ID without angle brackets */
  contentId: string;
};

/** Everything a message is built from. */
export type ComposeInput = {
  /** sender */
  from: Mailbox;
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
  /** `Bcc`, kept in the draft; Gmail and the server drop it from what recipients get */
  bcc: Mailbox[];
  /** decoded subject */
  subject: string;
  /** body, markdown: the plain-text part */
  markdown: string;
  /** body as the editor's HTML (`richToHtml` of what it holds); absent or null to build it from `markdown` */
  html?: string | null;
  /** signature, markdown; null for none */
  signature: string | null;
  /** `In-Reply-To` id, or null when not a reply (see `replyHeaders`) */
  inReplyTo: string | null;
  /** `References` ids, oldest first; empty when not a reply */
  references: string[];
  /** the message a reply quotes, after the signature; absent or null for none */
  quote?: QuotedMessage | null;
  /** images referenced from the body as `cid:` */
  inlineImages: MimeInlineImage[];
  /** attachments */
  attachments: MimeAttachment[];
  /** a text/calendar part added last to multipart/alternative; absent or null for none */
  calendar?: CalendarPart | null;
  /** extra headers as `[name, value]`, already encoded (e.g. `Autocrypt`), placed before `MIME-Version` */
  extraHeaders: [string, string][];
  /** unique token for multipart boundaries (e.g. a random hex string); boundaries are `=_<token>_<n>` */
  boundary: string;
};

/** An iCalendar part sent beside the text, e.g. an RSVP (`buildReply`). */
export type CalendarPart = {
  /** iTIP method, e.g. `REPLY` */
  method: string;
  /** the iCalendar text, CRLF line ends */
  content: string;
};

/** A header value that can't be written as it is: it holds a line break or another control character. */
export class InvalidHeaderError extends Error {
  /** the header's name */
  readonly header: string;

  /** Create an error for the header `header`. */
  constructor(header: string) {
    super(`The ${header} header holds a control character`);
    this.name = "InvalidHeaderError";
    this.header = header;
  }
}

// Any control character but tab, which may stand in a header value.
const HEADER_CONTROL = /[^\P{Cc}\t]/u;

/** `foldHeader(name, value)`, refusing a value with a control character other than tab. */
function header(name: string, value: string): string {
  if (HEADER_CONTROL.test(name) || HEADER_CONTROL.test(value)) {
    throw new InvalidHeaderError(name);
  }
  return foldHeader(name, value);
}

type Part = { headers: string[]; body: string };

function textPart(subtype: "plain" | "html", content: string): Part {
  return {
    headers: [
      `Content-Type: text/${subtype}; charset=UTF-8`,
      "Content-Transfer-Encoding: quoted-printable",
    ],
    body: quotedPrintable(content),
  };
}

function calendarPart(calendar: CalendarPart): Part {
  return {
    headers: [
      header(
        "Content-Type",
        `text/calendar; charset=UTF-8; method=${calendar.method}`,
      ),
      "Content-Transfer-Encoding: base64",
    ],
    body: base64Lines(utf8(calendar.content)),
  };
}

function percentEncoded(value: string): string {
  return encodeURIComponent(value).replace(
    /['()*!]/g,
    (character) =>
      `%${character.charCodeAt(0).toString(16).toUpperCase().padStart(2, "0")}`,
  );
}

/** `name="…"` for Content-Type: quoted when ASCII, else RFC 2047 words inside quotes (what mail clients read). */
function nameParameter(filename: string): string {
  return isPrintableAscii(filename)
    ? `name=${quotedString(filename)}`
    : `name="${encodedWords(filename).join(" ")}"`;
}

/** `filename="…"` when ASCII, else RFC 2231 `filename*=UTF-8''…`. */
function filenameParameter(filename: string): string {
  return isPrintableAscii(filename)
    ? `filename=${quotedString(filename)}`
    : `filename*=UTF-8''${percentEncoded(filename)}`;
}

function filePart(
  file: MimeAttachment,
  disposition: "inline" | "attachment",
  contentId: string | null,
): Part {
  const headers = [
    header("Content-Type", `${file.mimeType}; ${nameParameter(file.filename)}`),
    header(
      "Content-Disposition",
      `${disposition}; ${filenameParameter(file.filename)}`,
    ),
  ];
  if (contentId !== null) headers.push(header("Content-ID", `<${contentId}>`));
  headers.push("Content-Transfer-Encoding: base64");
  return { headers, body: base64Lines(file.data) };
}

function multipart(
  subtype: "alternative" | "related" | "mixed",
  boundary: string,
  parts: Part[],
): Part {
  const related = subtype === "related" ? '; type="multipart/alternative"' : "";
  const lines: string[] = [];
  for (const part of parts) {
    lines.push(`--${boundary}`, ...part.headers, "", part.body);
  }
  lines.push(`--${boundary}--`);
  return {
    headers: [
      header(
        "Content-Type",
        `multipart/${subtype}; boundary="${boundary}"${related}`,
      ),
    ],
    body: lines.join(CRLF),
  };
}

/**
 * Build a message as RFC 5322 text with CRLF line breaks, ready for Gmail's `drafts.create`
 * (base64url of its UTF-8 bytes as `message.raw`).
 *
 * Structure, outermost first, each level only when needed:
 * `multipart/mixed` (attachments) → `multipart/related` (inline images, `cid:`; `type="multipart/alternative"`) →
 * `multipart/alternative` (`text/plain`, `text/html`, then `text/calendar` when `calendar` is set,
 * base64). Text parts are UTF-8 quoted-printable;
 * files are base64 in 76-character lines. Boundaries are `=_<boundary>_0` for the outermost
 * multipart, `_1`, `_2` inward.
 *
 * Headers, in order: From, To, Cc, Bcc (each only when non-empty), Subject, In-Reply-To and
 * References (replies only), `extraHeaders`, MIME-Version, then the body's Content-Type. No `Date`
 * or `Message-ID`: Gmail adds both, and the server sets them for mail sent through Resend.
 *
 * Throws `InvalidAddressError` for an address that can't be written, and `InvalidHeaderError` when
 * any header value (In-Reply-To, References and `extraHeaders` included) holds a line break or
 * another control character but tab.
 */
export function buildMime(input: ComposeInput): string {
  const { html, text } = bodyParts(
    input.markdown,
    input.signature,
    input.quote ?? null,
    input.html ?? null,
  );
  const boundaries: string[] = [];
  const nextBoundary = () => {
    const boundary = `=_${input.boundary}_${boundaries.length}`;
    boundaries.push(boundary);
    return boundary;
  };
  const levels: ("mixed" | "related")[] = [];
  if (input.attachments.length > 0) levels.push("mixed");
  if (input.inlineImages.length > 0) levels.push("related");
  const outer = levels.map(() => nextBoundary());
  const alternatives = [textPart("plain", text), textPart("html", html)];
  if (input.calendar) alternatives.push(calendarPart(input.calendar));
  let body = multipart("alternative", nextBoundary(), alternatives);
  for (let level = levels.length - 1; level >= 0; level--) {
    const kind = levels[level];
    const boundary = outer[level] ?? "";
    body =
      kind === "related"
        ? multipart("related", boundary, [
            body,
            ...input.inlineImages.map((image) =>
              filePart(image, "inline", image.contentId),
            ),
          ])
        : multipart("mixed", boundary, [
            body,
            ...input.attachments.map((file) =>
              filePart(file, "attachment", null),
            ),
          ]);
  }

  const headers: string[] = [header("From", formatAddressList([input.from]))];
  if (input.to.length > 0) {
    headers.push(header("To", formatAddressList(input.to)));
  }
  if (input.cc.length > 0) {
    headers.push(header("Cc", formatAddressList(input.cc)));
  }
  if (input.bcc.length > 0) {
    headers.push(header("Bcc", formatAddressList(input.bcc)));
  }
  headers.push(header("Subject", encodeHeaderText(input.subject)));
  if (input.inReplyTo !== null) {
    headers.push(header("In-Reply-To", input.inReplyTo));
  }
  if (input.references.length > 0) {
    headers.push(header("References", input.references.join(" ")));
  }
  for (const [name, value] of input.extraHeaders) {
    headers.push(header(name, value));
  }
  headers.push("MIME-Version: 1.0", ...body.headers);
  return [...headers, "", body.body].join(CRLF) + CRLF;
}
