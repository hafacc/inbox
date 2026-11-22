// The unlocked OpenPGP key on this device (docs/pgp-contract.md "The unlocked key on the device"):
// a non-extractable AES-GCM key kept in IndexedDB wraps the armored private key, and seals the
// decrypted mail the offline cache keeps.

import { bytesToBase64 } from "@inbox/shared/mime";

/** Key of the one record in the `device` store. */
export const DEVICE_RECORD_KEY = "key";

const NONCE_LENGTH = 12;

/** What the `device` store holds: the wrapping key itself and the private key it sealed. */
export type DeviceKeyRecord = {
  /** AES-GCM 256, `extractable: false`; IndexedDB keeps it that way */
  wrappingKey: CryptoKey;
  /** nonce and AES-GCM ciphertext of the armored private key */
  sealedPrivateKey: Uint8Array<ArrayBuffer>;
  /** the key pair's fingerprint, lower-case hex */
  fingerprint: string;
};

/** A new non-extractable AES-GCM key for this device. */
export function newWrappingKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, [
    "encrypt",
    "decrypt",
  ]);
}

/** Encrypt bytes: nonce, then ciphertext and tag. */
export async function sealBytes(
  key: CryptoKey,
  plain: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_LENGTH));
  const cipher = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, plain),
  );
  const sealed = new Uint8Array(NONCE_LENGTH + cipher.length);
  sealed.set(nonce);
  sealed.set(cipher, NONCE_LENGTH);
  return sealed;
}

/** Decrypt what `sealBytes` made; throws on a wrong key or damaged data. */
export async function openBytes(
  key: CryptoKey,
  sealed: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(
    await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: sealed.subarray(0, NONCE_LENGTH) },
      key,
      sealed.subarray(NONCE_LENGTH),
    ),
  );
}

/** Seal any JSON value. */
export async function sealJson(
  key: CryptoKey,
  value: unknown,
): Promise<Uint8Array<ArrayBuffer>> {
  return sealBytes(key, new TextEncoder().encode(JSON.stringify(value)));
}

/** Open a value `sealJson` made. */
export async function openJson<Value>(
  key: CryptoKey,
  sealed: Uint8Array<ArrayBuffer>,
): Promise<Value> {
  return JSON.parse(new TextDecoder().decode(await openBytes(key, sealed)));
}

const BYTES_TAG = "\u0000bytes";

/** Seal a value that may hold `Uint8Array`s anywhere inside (JSON alone would lose them). */
export async function sealValue(
  key: CryptoKey,
  value: unknown,
): Promise<Uint8Array<ArrayBuffer>> {
  const text = JSON.stringify(value, (_name, field: unknown) =>
    field instanceof Uint8Array ? { [BYTES_TAG]: bytesToBase64(field) } : field,
  );
  return sealBytes(key, new TextEncoder().encode(text));
}

/** Open a value `sealValue` made, its byte arrays restored. */
export async function openValue<Value>(
  key: CryptoKey,
  sealed: Uint8Array<ArrayBuffer>,
): Promise<Value> {
  const text = new TextDecoder().decode(await openBytes(key, sealed));
  return JSON.parse(text, (_name, field: unknown) => {
    if (
      typeof field === "object" &&
      field !== null &&
      typeof (field as Record<string, unknown>)[BYTES_TAG] === "string"
    ) {
      const binary = atob((field as Record<string, string>)[BYTES_TAG] ?? "");
      return Uint8Array.from(binary, (char) => char.charCodeAt(0));
    }
    return field;
  });
}

/** Wrap an unlocked private key under a fresh device key. */
export async function makeDeviceRecord(
  privateKey: string,
  fingerprint: string,
  wrappingKey?: CryptoKey,
): Promise<DeviceKeyRecord> {
  const key = wrappingKey ?? (await newWrappingKey());
  return {
    wrappingKey: key,
    sealedPrivateKey: await sealBytes(
      key,
      new TextEncoder().encode(privateKey),
    ),
    fingerprint,
  };
}

/** The armored private key a device record holds. */
export async function privateKeyOf(record: DeviceKeyRecord): Promise<string> {
  return new TextDecoder().decode(
    await openBytes(record.wrappingKey, record.sealedPrivateKey),
  );
}
