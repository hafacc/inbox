import { randomUUID } from "node:crypto";
import addressparser from "nodemailer/lib/addressparser";
import { addressKey } from "../../../shared/src/compose/plain-address.js";

/** One header field as it appears in the message, folding kept. */
export interface RawHeader {
  /** field name as written */
  name: string;
  /** unfolded value, trimmed */
  value: string;
  /** the field's full text, continuation lines included, without the final line break */
  text: string;
}

/** A message split into its header fields and the rest. */
export interface SplitMessage {
  /** header fields in order */
  headers: RawHeader[];
  /** everything after the blank line that ends the header, untouched */
  body: Buffer;
  /** the line break the message uses */
  newline: "\r\n" | "\n";
  /**
   * whether the header block holds a CR or LF that isn't part of `newline`, which another server
   * could read as a line break this split doesn't see
   */
  strayLineBreak: boolean;
}

/** What the server sends through SMTP and what it files in Sent. */
export interface PreparedMessage {
  /** message to transmit: `Bcc` removed, `Date` set to the send time, `Message-ID` present */
  outgoing: Buffer;
  /** copy for the Sent folder: as `outgoing` but with `Bcc` kept */
  sentCopy: Buffer;
  /** SMTP envelope */
  envelope: { from: string; to: string[] };
  /** the `Message-ID`, angle brackets included */
  messageId: string;
}

/** Decode Gmail's base64url `raw` into bytes. */
export function decodeRaw(raw: string): Buffer {
  return Buffer.from(raw, "base64url");
}

/** Encode bytes as Gmail's base64url `raw`. */
export function encodeRaw(message: Buffer): string {
  return message.toString("base64url");
}

/** Split a message at the first blank line; header bytes are read as Latin-1 so they round-trip. */
export function splitMessage(message: Buffer): SplitMessage {
  const text = message.toString("latin1");
  const crlfEnd = text.indexOf("\r\n\r\n");
  const lfEnd = text.indexOf("\n\n");
  const crlf = crlfEnd >= 0 && (lfEnd < 0 || crlfEnd < lfEnd);
  const newline = crlf ? "\r\n" : "\n";
  const end = crlf ? crlfEnd : lfEnd;
  const headerText = end >= 0 ? text.slice(0, end) : text;
  const strayLineBreak = /[\r\n]/.test(headerText.split(newline).join(""));
  const bodyStart = end >= 0 ? end + 2 * newline.length : text.length;
  const headers: RawHeader[] = [];
  for (const line of headerText.split(newline)) {
    const last = headers.at(-1);
    if (/^[ \t]/.test(line) && last !== undefined) {
      last.text += `${newline}${line}`;
      last.value = `${last.value} ${line.trim()}`.trim();
      continue;
    }
    const colon = line.indexOf(":");
    if (colon <= 0) continue;
    headers.push({
      name: line.slice(0, colon).trim(),
      value: line.slice(colon + 1).trim(),
      text: line,
    });
  }
  return {
    headers,
    // Latin-1 maps each byte to one character, so a string index is a byte offset.
    body: message.subarray(bodyStart),
    newline,
    strayLineBreak,
  };
}

/** The value of the first header with this name (case-insensitive), or undefined. */
export function headerValue(
  headers: readonly { name: string; value: string }[],
  name: string,
): string | undefined {
  const lower = name.toLowerCase();
  return headers.find((header) => header.name.toLowerCase() === lower)?.value;
}

/** Every mailbox address in the named headers, lower-cased, groups flattened, each once. */
export function addressesIn(
  headers: readonly RawHeader[],
  names: readonly string[],
): string[] {
  const wanted = new Set(names.map((name) => name.toLowerCase()));
  const found = new Set<string>();
  for (const header of headers) {
    if (!wanted.has(header.name.toLowerCase())) continue;
    for (const mailbox of addressparser(header.value, { flatten: true })) {
      const address = addressKey(mailbox.address);
      if (address !== "") found.add(address);
    }
  }
  return [...found];
}

/**
 * The `From` address, lower-cased, or undefined unless the message has exactly one `From` header
 * holding exactly one mailbox (no group, no second address): the sender is checked against the
 * addresses the user owns, so a message naming anyone else as a sender is refused.
 */
export function fromAddress(
  headers: readonly { name: string; value: string }[],
): string | undefined {
  const fields = headers.filter((header) => /^from$/i.test(header.name));
  const [field] = fields;
  if (fields.length !== 1 || field === undefined) return undefined;
  const parsed = addressparser(field.value);
  const [mailbox] = parsed;
  if (parsed.length !== 1 || mailbox === undefined || "group" in mailbox) {
    return undefined;
  }
  const address = addressKey(mailbox.address);
  return /^[^@\s]+@[^@\s]+$/.test(address) ? address : undefined;
}

/** Whether text holds an ASCII control character other than tab. */
function hasControl(text: string): boolean {
  for (const character of text) {
    const code = character.charCodeAt(0);
    if ((code < 0x20 && code !== 0x09) || code === 0x7f) return true;
  }
  return false;
}

// Nothing but whitespace-separated `<id>`s (or nothing at all).
const MESSAGE_ID_LIST = /^\s*(<[^<>\s]+>\s*)*$/;

/**
 * Why a draft's header can't be sent safely, said for the user, or null when it can: a control
 * character other than tab in a name or unfolded value, an `In-Reply-To` or `References` holding
 * anything but `<id>`s, or more than one `To`, `Cc` or `Bcc` line. A client never writes these, so
 * they mean the header was tampered with (a copied id smuggling in a `Bcc`, say).
 */
export function headerProblem(
  headers: readonly { name: string; value: string }[],
): string | null {
  for (const { name, value } of headers) {
    if (hasControl(name) || hasControl(value)) {
      return `The draft's ${name.trim() || "header"} line holds a hidden control character, so it can't be sent safely.`;
    }
    if (
      /^(in-reply-to|references)$/i.test(name) &&
      !MESSAGE_ID_LIST.test(value)
    ) {
      return `The draft's ${name} line holds something other than message ids, so it can't be sent safely.`;
    }
  }
  for (const field of ["To", "Cc", "Bcc"]) {
    const lines = headers.filter(
      (header) => header.name.toLowerCase() === field.toLowerCase(),
    );
    if (lines.length > 1) {
      return `The draft has more than one ${field} line, so it can't be sent safely.`;
    }
  }
  return null;
}

function decodeWord(charset: string, encoding: string, text: string): string {
  const bytes =
    encoding.toUpperCase() === "B"
      ? Buffer.from(text, "base64")
      : Buffer.from(
          text
            .replace(/_/g, " ")
            .replace(/=([0-9A-Fa-f]{2})/g, (_match, hex: string) =>
              String.fromCharCode(Number.parseInt(hex, 16)),
            ),
          "latin1",
        );
  return new TextDecoder((charset.split("*")[0] ?? "utf-8") as "utf-8").decode(
    bytes,
  );
}

/** A header phrase with RFC 2047 encoded words decoded; a word that can't be decoded stays as written. */
export function decodeWords(value: string): string {
  return value
    .replace(/(=\?[^?]+\?[BbQq]\?[^?]*\?=)\s+(?==\?)/g, "$1")
    .replace(
      /=\?([^?]+)\?([BbQq])\?([^?]*)\?=/g,
      (word: string, charset: string, encoding: string, text: string) => {
        try {
          return decodeWord(charset, encoding, text);
        } catch {
          return word;
        }
      },
    );
}

// Something shaped like `local@domain.tld`, as a reader would take for an address.
const ADDRESS_LIKE =
  /[^\s@<>()[\]"',;:\\]+@[^\s@<>()[\]"',;:\\.]+(\.[^\s@<>()[\]"',;:\\.]+)+/;

/**
 * Why mail from the user's own `@domain` address can't go, said for the user, or null when it can:
 * its `From` holds something that looks like an email address besides `from` itself, in the name
 * or a comment (`"bob@x.example.com" <me@domain>`),
 * or its `Sender` or `Reply-To` names another address on `domain` than `from`.
 */
export function ownAddressProblem(
  headers: readonly RawHeader[],
  from: string,
  domain: string,
): string | null {
  const fromLine = headers.find((header) => /^from$/i.test(header.name));
  if (fromLine !== undefined) {
    // Everything but the address itself (the last time it appears): the name and any comment.
    const shown = decodeWords(fromLine.value);
    const at = shown.toLowerCase().lastIndexOf(from);
    const rest =
      at < 0 ? shown : shown.slice(0, at) + shown.slice(at + from.length);
    if (ADDRESS_LIKE.test(rest)) {
      return "Your name can't contain an email address, since it would make the mail look like it came from that address. Change your name in Settings.";
    }
  }
  const suffix = `@${domain.toLowerCase()}`;
  const others = addressesIn(headers, ["Sender", "Reply-To"]).filter(
    (address) => address.endsWith(suffix) && address !== from,
  );
  if (others.length > 0) {
    return `Mail from your ${suffix} address can only use that address as its reply address.`;
  }
  return null;
}

/** A header value with every CR and LF removed, so it can't start another header line. */
function oneLine(value: string): string {
  return value.replace(/[\r\n]/g, "");
}

/** An RFC 5322 date in UTC, e.g. `Wed, 04 Nov 2026 13:00:00 +0000`. */
export function rfc5322Date(epochMillis: number): string {
  return new Date(epochMillis).toUTCString().replace("GMT", "+0000");
}

/** A new `Message-ID` on a domain. */
export function newMessageId(domain: string): string {
  return `<${randomUUID()}@${domain}>`;
}

/**
 * Get a draft ready for SMTP: `Date` replaced by the send time, `Message-ID` kept or else set to
 * `fallbackMessageId`, `Bcc` removed from what goes out but kept in the Sent copy, and the envelope
 * taken from `From` and every `To`, `Cc` and `Bcc` address. `outgoingHeaders` (ASCII, unfolded) go
 * at the top of what goes out only, never into the Sent copy.
 */
export function prepareForSmtp(
  message: Buffer,
  sentAt: number,
  fallbackMessageId: string,
  outgoingHeaders: readonly [string, string][] = [],
): PreparedMessage {
  const { headers, body, newline } = splitMessage(message);
  const from = fromAddress(headers);
  if (from === undefined) throw new Error("message has no From address");
  const messageId = headerValue(headers, "Message-ID") ?? fallbackMessageId;
  const kept = headers.filter(
    (header) => !/^(date|message-id)$/i.test(header.name),
  );
  const lead = [
    `Date: ${rfc5322Date(sentAt)}`,
    `Message-ID: ${oneLine(messageId)}`,
  ];
  const assemble = (fields: RawHeader[], extra: readonly string[] = []) =>
    Buffer.concat([
      Buffer.from(
        [...extra, ...lead, ...fields.map((header) => header.text)].join(
          newline,
        ) +
          newline +
          newline,
        "latin1",
      ),
      body,
    ]);
  return {
    outgoing: assemble(
      kept.filter((header) => !/^bcc$/i.test(header.name)),
      outgoingHeaders.map(
        ([name, value]) => `${oneLine(name)}: ${oneLine(value)}`,
      ),
    ),
    sentCopy: assemble(kept),
    envelope: { from, to: addressesIn(headers, ["To", "Cc", "Bcc"]) },
    messageId: oneLine(messageId),
  };
}
