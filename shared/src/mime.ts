// Reading Gmail's MIME trees: bodies, attachments, inline and calendar parts, charsets, address
// headers and snippet entities. Web and Android (:core Mime.kt) both run
// shared/testdata/mime-fixtures.json. Charsets follow the WHATWG Encoding Standard: web through
// TextDecoder, Android through its own label table.

import { addressKey } from "./compose/address";

/** One header of a MIME part. */
export type MimeHeader = {
  /** field name as written */
  name: string;
  /** field value */
  value: string;
};

/** A node of a Gmail message's MIME tree, as Gmail's API gives it. */
export type MimePart = {
  /** place in the message's tree (`0`, `1.2`) */
  partId?: string;
  /** content type, such as `text/html` */
  mimeType: string;
  /** file name, empty or missing when none */
  filename?: string;
  /** the part's headers */
  headers?: MimeHeader[];
  /** the part's content */
  body?: {
    /** id to fetch the content by, when Gmail didn't send it inline */
    attachmentId?: string;
    /** size in bytes */
    size: number;
    /** base64url content, when Gmail sent it inline */
    data?: string;
  };
  /** child parts of a multipart */
  parts?: MimePart[];
};

/** A person on a message: display name (may be empty) and address. */
export type Person = {
  /** display name, empty when none */
  name: string;
  /** address */
  email: string;
};

/** A downloadable attachment of a message. */
export type Attachment = {
  /** place in the message's tree, which unlike `attachmentId` stays the same on every fetch */
  partId: string;
  /** file name */
  filename: string;
  /** content type */
  mimeType: string;
  /** size in bytes, as Gmail gives it */
  size: number;
  /** id to fetch the data by, or null when Gmail sent it inline */
  attachmentId: string | null;
  /** base64url data when Gmail sent it inline with the message, else null */
  data: string | null;
};

/** An inline part referenced from the HTML body by `cid:`. */
export type InlinePart = {
  /** place in the message's tree, which unlike `attachmentId` stays the same on every fetch */
  partId: string;
  /** `Content-ID` without its angle brackets, as `cid:` names it */
  contentId: string;
  /** content type */
  mimeType: string;
  /** file name, or "" when it has none */
  filename: string;
  /** size in bytes, as Gmail gives it */
  size: number;
  /** id to fetch the data by, or null when Gmail sent it inline */
  attachmentId: string | null;
  /** base64url data when Gmail sent it inline with the message, else null */
  data: string | null;
};

/** A message's iCalendar part (an invite or an answer to one), to read or fetch. */
export type CalendarPart = {
  /** place in the message's tree, which unlike `attachmentId` stays the same on every fetch */
  partId: string;
  /** base64url data when Gmail sent it inline with the message, else null */
  data: string | null;
  /** id to fetch the data by, or null when Gmail sent it inline */
  attachmentId: string | null;
  /** the part's `charset`, lower-cased; `utf-8` when it names none */
  charset: string;
};

/** The readable parts of one message. */
export type MessageBody = {
  /** HTML body, or null when there is none */
  html: string | null;
  /** plain-text body, or null when there is none */
  text: string | null;
  /** downloadable attachments */
  attachments: Attachment[];
  /** parts the HTML body shows by `cid:` */
  inline: InlinePart[];
  /** the iCalendar part, or null when there is none */
  calendar: CalendarPart | null;
};

/** Decode Gmail's base64url (with or without padding, `-` and `_`) into bytes. */
export function decodeBase64Url(data: string): Uint8Array {
  const base64 = data
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/[\s=]/g, "");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

/** Turn bytes into standard base64, for `data:` URLs. */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let start = 0; start < bytes.length; start += chunk) {
    binary += String.fromCharCode(...bytes.subarray(start, start + chunk));
  }
  return btoa(binary);
}

/**
 * Decode bytes in the charset a label names, as the WHATWG Encoding Standard reads labels: so
 * `iso-8859-1` and `us-ascii` mean windows-1252, a leading byte order mark goes, and a label it
 * doesn't know (or one it maps to "replacement") reads as UTF-8.
 */
export function decodeText(bytes: Uint8Array, charset = "utf-8"): string {
  try {
    // Any label may come in; TextDecoder throws on those it doesn't know.
    const label = charset as ConstructorParameters<typeof TextDecoder>[0];
    return new TextDecoder(label).decode(bytes);
  } catch {
    return new TextDecoder("utf-8").decode(bytes);
  }
}

/** Decode base64url text in the given charset (see `decodeText`). */
export function decodeBase64UrlText(data: string, charset = "utf-8"): string {
  return decodeText(decodeBase64Url(data), charset);
}

/** Case-insensitive header lookup; the first match wins. */
export function headerValue(
  headers: readonly MimeHeader[] | undefined,
  name: string,
): string | null {
  const wanted = name.toLowerCase();
  for (const header of headers ?? []) {
    if (header.name.toLowerCase() === wanted) return header.value;
  }
  return null;
}

/** The `charset` parameter of a Content-Type value, lower-cased; UTF-8 when absent. */
export function charsetOf(contentType: string | null): string {
  const match = /charset\s*=\s*"?([^";\s]+)"?/i.exec(contentType ?? "");
  return match ? match[1]!.toLowerCase() : "utf-8";
}

/** Visit every part of a MIME tree depth-first, parents before children. */
export function walkParts<Part extends MimePart>(
  root: Part,
  visit: (part: Part, ancestors: Part[]) => void,
): void {
  const walk = (part: Part, ancestors: Part[]) => {
    visit(part, ancestors);
    for (const child of (part.parts ?? []) as Part[]) {
      walk(child, [...ancestors, part]);
    }
  };
  walk(root, []);
}

function isAttachmentDisposition(part: MimePart): boolean {
  const disposition = headerValue(part.headers, "Content-Disposition") ?? "";
  return /^\s*attachment/i.test(disposition);
}

function contentIdOf(part: MimePart): string | null {
  const raw = headerValue(part.headers, "Content-ID");
  if (!raw) return null;
  return raw.trim().replace(/^<|>$/g, "") || null;
}

function partText(part: MimePart): string {
  const data = part.body?.data;
  if (!data) return "";
  return decodeBase64UrlText(
    data,
    charsetOf(headerValue(part.headers, "Content-Type")),
  );
}

const CALENDAR_TYPES = new Set(["text/calendar", "application/ics"]);

/**
 * Pull the HTML and plain-text bodies, attachments, `cid:` parts and invite out of a message.
 *
 * The first `text/html` and first `text/plain` that aren't attachments win (so the main
 * `multipart/alternative` beats a forwarded message further down). A non-text part with a
 * Content-ID that isn't marked as an attachment is inline; any other part with a filename or an
 * attachment disposition is an attachment. The invite is the first `text/calendar` part that isn't
 * a file, else the first calendar file (`text/calendar` or `application/ics`), which also lists as
 * an attachment; a calendar part with neither data nor an attachment id is skipped.
 */
export function extractBody(payload: MimePart): MessageBody {
  let html: string | null = null;
  let text: string | null = null;
  const attachments: Attachment[] = [];
  const inline: InlinePart[] = [];
  let calendar: CalendarPart | null = null;
  let calendarFile: CalendarPart | null = null;
  walkParts(payload, (part) => {
    const mimeType = part.mimeType.toLowerCase();
    if (mimeType.startsWith("multipart/")) return;
    const partId = part.partId ?? "";
    const filename = part.filename ?? "";
    const asAttachment = isAttachmentDisposition(part);
    const data = part.body?.data ?? null;
    const attachmentId = part.body?.attachmentId ?? null;
    if (CALENDAR_TYPES.has(mimeType)) {
      const named = filename !== "" || asAttachment;
      if (data !== null || attachmentId !== null) {
        const found: CalendarPart = {
          partId,
          data,
          attachmentId,
          charset: charsetOf(headerValue(part.headers, "Content-Type")),
        };
        if (!named && mimeType === "text/calendar") calendar ??= found;
        else calendarFile ??= found;
      }
      if (!named) return;
    }
    const contentId = contentIdOf(part);
    const size = part.body?.size ?? 0;
    if (contentId && !asAttachment && !mimeType.startsWith("text/")) {
      inline.push({
        partId,
        contentId,
        mimeType,
        filename,
        size,
        attachmentId,
        data,
      });
    } else if (filename !== "" || asAttachment) {
      attachments.push({
        partId,
        filename: filename || "attachment",
        mimeType,
        size,
        attachmentId,
        data,
      });
    } else if (mimeType === "text/html" && html === null) {
      html = partText(part);
    } else if (mimeType === "text/plain" && text === null) {
      text = partText(part);
    }
  });
  return {
    html,
    text,
    attachments,
    inline,
    calendar: calendar ?? calendarFile,
  };
}

/**
 * Split an address list into one string per mailbox, on commas outside quoted strings, comments
 * and angle brackets. A group (`Friends: a@x.example.com, b@x.example.com;`) gives its members, without its name;
 * a colon after an `@` doesn't start one.
 */
function splitAddresses(value: string): string[] {
  const out: string[] = [];
  let current = "";
  let quoted = false;
  let escaped = false;
  let comment = 0;
  let angled = false;
  for (const character of value) {
    if (escaped) {
      escaped = false;
      current += character;
      continue;
    }
    if ((quoted || comment > 0) && character === "\\") {
      escaped = true;
      current += character;
      continue;
    }
    if (quoted) {
      if (character === '"') quoted = false;
    } else if (comment > 0) {
      if (character === "(") comment++;
      else if (character === ")") comment--;
    } else if (angled) {
      if (character === ">") angled = false;
    } else if (character === '"') {
      quoted = true;
    } else if (character === "(") {
      comment = 1;
    } else if (character === "<") {
      angled = true;
    } else if (character === ":" && !current.includes("@")) {
      current = "";
      continue;
    } else if (character === "," || character === ";") {
      out.push(current);
      current = "";
      continue;
    }
    current += character;
  }
  out.push(current);
  return out.map((entry) => entry.trim()).filter((entry) => entry !== "");
}

/** Text with each run of whitespace that breaks a line, and the ends, as plain spaces. */
function oneLine(text: string): string {
  return text.replace(/\s*[\r\n]+\s*/g, " ").trim();
}

/**
 * Parse one mailbox such as `"Chen, Maya" <maya@example.com>`, `maya@example.com` or
 * `maya@example.com (Maya Chen)`.
 *
 * The address is what the last `<…>` holds, else everything outside quotes and comments, as
 * `addressKey` gives it. The name is the text before the `<…>` with its quotes and backslash escapes
 * undone, else the first comment; line breaks in it read as spaces.
 */
export function parseAddress(value: string): Person {
  let phrase = "";
  let angle: string | null = null;
  let firstComment: string | null = null;
  let commentText = "";
  let quoted = false;
  let escaped = false;
  let comment = 0;
  let angled = false;
  for (const character of value) {
    if (escaped) {
      escaped = false;
      if (comment > 0) commentText += character;
      else phrase += character;
    } else if ((quoted || comment > 0) && character === "\\") {
      escaped = true;
    } else if (quoted) {
      if (character === '"') quoted = false;
      else phrase += character;
    } else if (comment > 0) {
      if (character === "(") comment++;
      else if (character === ")") comment--;
      if (comment === 0) {
        firstComment ??= commentText;
        commentText = "";
      } else {
        commentText += character;
      }
    } else if (angled) {
      if (character === ">") angled = false;
      else angle += character;
    } else if (character === '"') {
      quoted = true;
    } else if (character === "(") {
      comment = 1;
    } else if (character === "<") {
      angled = true;
      angle = "";
    } else {
      phrase += character;
    }
  }
  if (comment > 0) firstComment ??= commentText;
  const commentName = oneLine(firstComment ?? "");
  if (angle !== null) {
    return {
      name: oneLine(phrase) || commentName,
      email: addressKey(angle),
    };
  } else {
    return { name: commentName, email: addressKey(phrase) };
  }
}

/** Parse a To/Cc/From header into people. */
export function parseAddressList(value: string | null): Person[] {
  if (!value) return [];
  return splitAddresses(value)
    .map(parseAddress)
    .filter((person) => person.email !== "" || person.name !== "");
}

const NAMED_ENTITIES: Readonly<Record<string, string>> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
};

// What HTML reads a numeric reference to 0x80–0x9F as: windows-1252's character for that byte.
const C1_AS_WINDOWS_1252 =
  "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f" + "\u0090‘’“”•–—˜™š›œ\u009džŸ";

function numericReference(digits: string, radix: number): string {
  const significant = digits.replace(/^0+/, "");
  // Anything past 8 significant digits is past U+10FFFF in either radix.
  const code =
    significant.length > 8
      ? Number.POSITIVE_INFINITY
      : Number.parseInt(significant || "0", radix);
  if (code === 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
    return "�";
  } else if (code >= 0x80 && code <= 0x9f) {
    return C1_AS_WINDOWS_1252[code - 0x80]!;
  } else {
    return String.fromCodePoint(code);
  }
}

/**
 * Decode the HTML entities Gmail leaves in snippets: a few named ones (others stay as written) and
 * numeric ones, which read as HTML reads them: 0, surrogates and anything past U+10FFFF as U+FFFD,
 * and 0x80–0x9F as windows-1252.
 */
export function decodeEntities(value: string): string {
  return value.replace(
    /&(?:#[xX]([0-9a-fA-F]+)|#(\d+)|([a-zA-Z]+));/g,
    (whole, hex?: string, decimal?: string, name?: string) => {
      if (hex !== undefined) return numericReference(hex, 16);
      else if (decimal !== undefined) return numericReference(decimal, 10);
      else return NAMED_ENTITIES[name!.toLowerCase()] ?? whole;
    },
  );
}
