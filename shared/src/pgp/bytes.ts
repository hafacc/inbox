// Byte helpers that run the same in browsers, service workers, bun and Workers.

import { base64, base64Url, utf8 } from "../compose/encoding";

const strictDecoder = new TextDecoder("utf-8", { fatal: true });

/** UTF-8 bytes of a string. */
export const utf8Bytes = utf8;

/** A string from UTF-8 bytes; malformed input throws. */
export function utf8Text(bytes: Uint8Array): string {
  return strictDecoder.decode(bytes);
}

/** Standard base64 with padding. */
export const toBase64 = base64;

/** Bytes from base64 or base64url, padded or not; whitespace is ignored. */
export function fromBase64(text: string): Uint8Array<ArrayBuffer> {
  const standard = text
    .replace(/\s+/g, "")
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/=+$/, "");
  try {
    return Uint8Array.fromBase64(standard);
  } catch {
    throw new Error("not base64");
  }
}

/** URL-safe base64 without padding (WebAuthn's encoding). */
export const toBase64Url = base64Url;

/** Lower-case hex. */
export function toHex(bytes: Uint8Array): string {
  return bytes.toHex();
}

/** Bytes from hex. */
export function fromHex(text: string): Uint8Array<ArrayBuffer> {
  try {
    return Uint8Array.fromHex(text);
  } catch {
    throw new Error("not hex");
  }
}

/** A "binary string": one character per byte (Latin-1), so MIME text round-trips byte for byte. */
export function bytesToBinary(bytes: Uint8Array): string {
  let binary = "";
  for (let start = 0; start < bytes.length; start += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(start, start + 0x8000));
  }
  return binary;
}

/** Bytes from a binary string; characters above 0xFF are an error. */
export function binaryToBytes(binary: string): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    const code = binary.charCodeAt(index);
    if (code > 0xff) throw new Error("not a binary string");
    bytes[index] = code;
  }
  return bytes;
}

/** Fresh random bytes from the platform's secure generator. */
export function randomBytes(length: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(length));
}
