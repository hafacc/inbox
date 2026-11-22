// Byte-level encodings for building MIME messages: UTF-8, base64, quoted-printable, RFC 2047 encoded
// words and header folding. Pure; no DOM, no Node Buffer.

/** Line break used throughout a built message. */
export const CRLF = "\r\n";

/** Longest line, without its CRLF, a base64 or quoted-printable body produces. */
export const MAX_BODY_LINE = 76;

/** Folded header lines are kept to this length where a break point allows. */
export const MAX_HEADER_LINE = 78;

// UTF-8 bytes per encoded word: 45 bytes is 60 base64 characters, 72 with `=?UTF-8?B?` and `?=`.
const ENCODED_WORD_BYTES = 45;

const encoder = new TextEncoder();

/** Encode a string as UTF-8 bytes. */
export function utf8(text: string): Uint8Array<ArrayBuffer> {
  return encoder.encode(text);
}

/** Bytes a code point takes in UTF-8; a lone surrogate counts as U+FFFD's 3. */
export function utf8Length(codePoint: number): number {
  if (codePoint < 0x80) return 1;
  else if (codePoint < 0x800) return 2;
  else if (codePoint < 0x10000) return 3;
  else return 4;
}

/** Whether every character is printable ASCII (space through `~`). */
export function isPrintableAscii(text: string): boolean {
  return /^[\x20-\x7e]*$/.test(text);
}

/** Standard base64 (with `=` padding) of bytes, on one line. */
export function base64(bytes: Uint8Array): string {
  return bytes.toBase64();
}

/** Base64 of bytes in lines of 76 characters joined by CRLF, with no trailing break. */
export function base64Lines(bytes: Uint8Array): string {
  const encoded = base64(bytes);
  const lines: string[] = [];
  for (let start = 0; start < encoded.length; start += MAX_BODY_LINE) {
    lines.push(encoded.slice(start, start + MAX_BODY_LINE));
  }
  return lines.join(CRLF);
}

/** Line breaks of any kind (CRLF, CR, LF) turned into LF. */
export function normalizeNewlines(text: string): string {
  return text.replace(/\r\n?/g, "\n");
}

function hexByte(byte: number): string {
  return `=${byte.toString(16).toUpperCase().padStart(2, "0")}`;
}

/**
 * Quoted-printable (RFC 2045) of text as UTF-8, lines joined by CRLF with no trailing break.
 *
 * Bytes 33–60 and 62–126 stay as they are; space and tab stay unless they end a line; everything
 * else is `=XX` in upper-case hex. An encoded line holds at most 75 characters before a soft break
 * (`=` CRLF), and an escape is never split.
 */
export function quotedPrintable(text: string): string {
  const out: string[] = [];
  for (const line of normalizeNewlines(text).split("\n")) {
    const bytes = utf8(line);
    let current = "";
    for (let index = 0; index < bytes.length; index++) {
      const byte = bytes[index] ?? 0;
      const last = index === bytes.length - 1;
      let token: string;
      if ((byte === 0x20 || byte === 0x09) && !last) {
        token = String.fromCharCode(byte);
      } else if (byte >= 33 && byte <= 126 && byte !== 61) {
        token = String.fromCharCode(byte);
      } else {
        token = hexByte(byte);
      }
      if (current.length + token.length > MAX_BODY_LINE - 1) {
        out.push(`${current}=`);
        current = "";
      }
      current += token;
    }
    out.push(current);
  }
  return out.join(CRLF);
}

/**
 * RFC 2047 encoded words (`=?UTF-8?B?…?=`) for text, each holding at most 45 bytes of UTF-8 and
 * never splitting a character, so each word is at most 72 characters long.
 */
export function encodedWords(text: string): string[] {
  const words: string[] = [];
  let chunk = "";
  let chunkBytes = 0;
  for (const character of text) {
    const size = utf8Length(character.codePointAt(0)!);
    if (chunkBytes + size > ENCODED_WORD_BYTES) {
      words.push(`=?UTF-8?B?${base64(utf8(chunk))}?=`);
      chunk = "";
      chunkBytes = 0;
    }
    chunk += character;
    chunkBytes += size;
  }
  if (chunk !== "" || words.length === 0) {
    words.push(`=?UTF-8?B?${base64(utf8(chunk))}?=`);
  }
  return words;
}

/**
 * Unstructured header text (such as a subject) as it goes on the wire: unchanged when it is
 * printable ASCII without `=?`, otherwise the whole text as encoded words separated by spaces.
 */
export function encodeHeaderText(text: string): string {
  if (isPrintableAscii(text) && !text.includes("=?")) return text;
  return encodedWords(text).join(" ");
}

/**
 * A header line `Name: value`, folded (CRLF and a space) at spaces so lines stay within 78
 * characters where possible. A word longer than a line is never split.
 */
export function foldHeader(name: string, value: string): string {
  const words = value.split(" ");
  const lines: string[] = [];
  let current = `${name}:`;
  let lineHasWord = false;
  for (const word of words) {
    if (lineHasWord && current.length + 1 + word.length > MAX_HEADER_LINE) {
      lines.push(current);
      current = ` ${word}`;
    } else {
      current += ` ${word}`;
      lineHasWord = true;
    }
  }
  lines.push(current);
  return lines.join(CRLF);
}

/** URL-safe base64 without padding, as Gmail's `raw` fields take it. */
export function base64Url(bytes: Uint8Array): string {
  return bytes.toBase64({ alphabet: "base64url", omitPadding: true });
}

/** A built message as Gmail's `message.raw`: base64url of its UTF-8 bytes. */
export function gmailRaw(message: string): string {
  return base64Url(utf8(message));
}

/** A fresh random token for `ComposeInput.boundary`: 24 hex digits from the platform's secure random source. */
export function randomBoundary(): string {
  return globalThis.crypto.getRandomValues(new Uint8Array(12)).toHex();
}
