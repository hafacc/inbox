// A small MIME reader for decrypted and encrypted messages. Works on binary strings (one character
// per byte) so signed parts can be checked byte for byte.

import { CRLF } from "../compose/encoding";
import { binaryToBytes } from "./bytes";

/** One header field. */
export type MimeHeader = {
  /** name as written */
  name: string;
  /** value unfolded and trimmed, still encoded */
  value: string;
  /** the field as written, folded lines joined by CRLF */
  raw: string;
};

/** One MIME entity: a whole message, or a part of one. */
export type MimeEntity = {
  /** header fields in order */
  headers: MimeHeader[];
  /** media type, lower-case (`text/plain` when absent) */
  type: string;
  /** Content-Type parameters, names lower-case */
  params: Record<string, string>;
  /** the body as sent, a binary string, before transfer decoding */
  rawBody: string;
  /** the whole entity as sent (headers and body), a binary string */
  raw: string;
  /** child parts of a multipart, else empty */
  parts: MimeEntity[];
};

/** The value of the first header with this name, case-insensitive. */
export function header(entity: MimeEntity, name: string): string | undefined {
  const wanted = name.toLowerCase();
  return entity.headers.find((field) => field.name.toLowerCase() === wanted)
    ?.value;
}

/** Split a header block and body at the first blank line, CRLF or LF. */
export function splitHeaderBody(raw: string): {
  headerText: string;
  body: string;
} {
  const match = /\r?\n\r?\n/.exec(raw);
  if (match === null) {
    return /^[\r\n]/.test(raw)
      ? { headerText: "", body: raw.replace(/^\r?\n/, "") }
      : { headerText: raw, body: "" };
  }
  if (/^\r?\n/.test(raw)) {
    return { headerText: "", body: raw.replace(/^\r?\n/, "") };
  }
  return {
    headerText: raw.slice(0, match.index),
    body: raw.slice(match.index + match[0].length),
  };
}

/** Header fields of a header block, unfolded. */
export function parseHeaders(headerText: string): MimeHeader[] {
  const headers: MimeHeader[] = [];
  for (const line of headerText.split(/\r?\n/)) {
    const last = headers.at(-1);
    if (/^[ \t]/.test(line) && last !== undefined) {
      last.raw += `${CRLF}${line}`;
      last.value = `${last.value} ${line.trim()}`.trim();
      continue;
    }
    const colon = line.indexOf(":");
    if (colon <= 0) continue;
    headers.push({
      name: line.slice(0, colon).trim(),
      value: line.slice(colon + 1).trim(),
      raw: line,
    });
  }
  return headers;
}

/** A Content-Type value as `{ type, params }`; quoted values unquoted, RFC 2231 `*` forms joined. */
export function parseContentType(value: string | undefined): {
  type: string;
  params: Record<string, string>;
} {
  const params: Record<string, string> = {};
  if (value === undefined) return { type: "text/plain", params };
  const semicolon = value.indexOf(";");
  const type = (semicolon < 0 ? value : value.slice(0, semicolon))
    .trim()
    .toLowerCase();
  const rest = semicolon < 0 ? "" : value.slice(semicolon + 1);
  const pattern = /\s*([^=;\s]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]*))\s*;?/g;
  const continued: Record<string, string[]> = {};
  for (const match of rest.matchAll(pattern)) {
    const [, rawName = "", quoted, plain] = match;
    const name = rawName.toLowerCase();
    const text =
      quoted !== undefined
        ? quoted.replace(/\\(.)/g, "$1")
        : (plain ?? "").trim();
    const section = /^([^*]+)\*(\d+)\*?$/.exec(name);
    if (section) {
      const [, base = "", position = "0"] = section;
      (continued[base] ??= [])[Number(position)] = text;
    } else {
      params[name.replace(/\*$/, "")] = text;
    }
  }
  for (const [name, pieces] of Object.entries(continued)) {
    params[name] = pieces.join("");
  }
  return { type: type === "" ? "text/plain" : type, params };
}

/** Parse a message or part (a binary string) into a tree. */
export function parseMime(raw: string, depth = 0): MimeEntity {
  const { headerText, body } = splitHeaderBody(raw);
  const headers = parseHeaders(headerText);
  const contentType = headers.find(
    (field) => field.name.toLowerCase() === "content-type",
  )?.value;
  const { type, params } = parseContentType(contentType);
  const entity: MimeEntity = {
    headers,
    type,
    params,
    rawBody: body,
    raw,
    parts: [],
  };
  const boundary = params.boundary;
  if (type.startsWith("multipart/") && boundary && depth < 20) {
    entity.parts = splitMultipart(body, boundary).map((part) =>
      parseMime(part, depth + 1),
    );
  }
  return entity;
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * The parts of a multipart body, each exactly as sent: the text after a delimiter line's line
 * break up to (not including) the line break before the next delimiter (RFC 2046 §5.1.1).
 */
export function splitMultipart(body: string, boundary: string): string[] {
  const delimiter = new RegExp(
    `(?:^|\\r?\\n)--${escapeRegExp(boundary)}(--)?[ \\t]*(?:\\r?\\n|$)`,
    "g",
  );
  const parts: string[] = [];
  let start: number | null = null;
  for (const match of body.matchAll(delimiter)) {
    if (start !== null) parts.push(body.slice(start, match.index));
    if (match[1] === "--") return parts;
    start = match.index + match[0].length;
  }
  if (start !== null) parts.push(body.slice(start));
  return parts;
}

/** A part's body with its Content-Transfer-Encoding undone, as bytes. */
export function decodedBody(entity: MimeEntity): Uint8Array<ArrayBuffer> {
  const encoding = (header(entity, "Content-Transfer-Encoding") ?? "")
    .trim()
    .toLowerCase();
  if (encoding === "base64") {
    // Padding is dropped with everything else outside the alphabet; a lone leftover character
    // holds no whole byte.
    const clean = entity.rawBody.replace(/[^A-Za-z0-9+/]/g, "");
    return Uint8Array.fromBase64(
      clean.length % 4 === 1 ? clean.slice(0, -1) : clean,
    );
  }
  if (encoding === "quoted-printable") {
    return binaryToBytes(
      hexEscapesDecoded(entity.rawBody.replace(/=\r?\n/g, "")),
    );
  }
  return binaryToBytes(entity.rawBody);
}

/** `=XX` escapes turned into the characters they stand for, in a binary string. */
function hexEscapesDecoded(text: string): string {
  return text.replace(/=([0-9A-Fa-f]{2})/g, (_, hex: string) =>
    String.fromCharCode(Number.parseInt(hex, 16)),
  );
}

function decodeCharset(bytes: Uint8Array, charset: string | undefined): string {
  try {
    return new TextDecoder((charset ?? "utf-8") as "utf-8").decode(bytes);
  } catch {
    return new TextDecoder("utf-8").decode(bytes);
  }
}

/** A text part's body as a string, transfer encoding and charset undone. */
export function decodedText(entity: MimeEntity): string {
  return decodeCharset(decodedBody(entity), entity.params.charset);
}

/** Decode RFC 2047 encoded words (`=?charset?B|Q?…?=`) in a header value; malformed ones stay as written. */
export function decodeHeaderValue(value: string): string {
  return value
    .replace(/(=\?[^?]+\?[BbQq]\?[^?]*\?=)\s+(?==\?)/g, "$1")
    .replace(
      /=\?([^?*]+)(?:\*[^?]*)?\?([BbQq])\?([^?]*)\?=/g,
      (word: string, charset: string, encoding: string, text: string) => {
        try {
          return decodeWord(charset, encoding, text);
        } catch {
          return word;
        }
      },
    );
}

function decodeWord(charset: string, encoding: string, text: string): string {
  const bytes =
    encoding.toUpperCase() === "B"
      ? Uint8Array.fromBase64(text)
      : binaryToBytes(hexEscapesDecoded(text.replace(/_/g, " ")));
  return decodeCharset(bytes, charset);
}

/** Every entity in the tree, depth first, the root first. */
export function walk(entity: MimeEntity): MimeEntity[] {
  return [entity, ...entity.parts.flatMap(walk)];
}
