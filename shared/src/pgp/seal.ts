import {
  fromBase64,
  randomBytes,
  toBase64,
  utf8Bytes,
  utf8Text,
} from "./bytes";

/** Length in bytes of the secret a seal is made with (a passkey's PRF output, or the Argon2id output). */
export const SEAL_SECRET_LENGTH = 32;

/** Format version, the first byte of every sealed value. */
export const SEAL_VERSION = 1;

/** HKDF `info`, binding the derived key to this use. */
export const SEAL_INFO = "inbox.hafa.cc/pgp-seal/v1";

/** AES-GCM nonce length in bytes. */
export const SEAL_IV_LENGTH = 12;

const TAG_LENGTH = 16;

/** Unsealing failed: wrong secret, or a damaged or unknown sealed value. */
export class SealError extends Error {
  /** Create an error with a reason. */
  constructor(message: string) {
    super(message);
    this.name = "SealError";
  }
}

async function sealKey(secret: Uint8Array): Promise<CryptoKey> {
  if (secret.length !== SEAL_SECRET_LENGTH) {
    throw new SealError(`the secret must be ${SEAL_SECRET_LENGTH} bytes`);
  }
  const material = await crypto.subtle.importKey(
    "raw",
    new Uint8Array(secret),
    "HKDF",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new Uint8Array(0),
      info: utf8Bytes(SEAL_INFO),
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

/**
 * Seal text (an armored private key) under a 32-byte secret.
 *
 * Key: HKDF-SHA256 of the secret with an empty salt and `SEAL_INFO`, 32 bytes, used as an
 * AES-256-GCM key. Result: base64 of `SEAL_VERSION` (one byte), the 12-byte nonce, then the
 * ciphertext with its 16-byte tag; no associated data. `iv` is for test vectors only; leave it out.
 */
export async function seal(
  secret: Uint8Array,
  plaintext: string,
  iv: Uint8Array = randomBytes(SEAL_IV_LENGTH),
): Promise<string> {
  if (iv.length !== SEAL_IV_LENGTH) throw new SealError("bad nonce length");
  const key = await sealKey(secret);
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: new Uint8Array(iv) },
      key,
      utf8Bytes(plaintext),
    ),
  );
  const sealed = new Uint8Array(1 + iv.length + ciphertext.length);
  sealed[0] = SEAL_VERSION;
  sealed.set(iv, 1);
  sealed.set(ciphertext, 1 + iv.length);
  return toBase64(sealed);
}

/** Open a value made by `seal`; throws `SealError` for a wrong secret or damaged value. */
export async function unseal(
  secret: Uint8Array,
  sealed: string,
): Promise<string> {
  let bytes: Uint8Array;
  try {
    bytes = fromBase64(sealed);
  } catch {
    throw new SealError("the sealed value is not base64");
  }
  if (bytes.length < 1 + SEAL_IV_LENGTH + TAG_LENGTH) {
    throw new SealError("the sealed value is too short");
  }
  if (bytes[0] !== SEAL_VERSION) {
    throw new SealError(`unknown seal version ${bytes[0]}`);
  }
  const key = await sealKey(secret);
  let plaintext: ArrayBuffer;
  try {
    plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: bytes.slice(1, 1 + SEAL_IV_LENGTH) },
      key,
      bytes.slice(1 + SEAL_IV_LENGTH),
    );
  } catch {
    throw new SealError("wrong secret, or the sealed value is damaged");
  }
  return utf8Text(new Uint8Array(plaintext));
}
