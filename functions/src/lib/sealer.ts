import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

/** Encrypts secrets (Google refresh tokens) for storage in Firestore. */
export interface TokenSealer {
  /** Encrypt a plaintext secret into a storable string. */
  seal(plain: string): Promise<string>;
  /** Decrypt a string produced by {@link TokenSealer.seal}. */
  open(sealed: string): Promise<string>;
}

const AES_PREFIX = "aes:";
const KMS_PREFIX = "kms:";
const IV_BYTES = 12;
const TAG_BYTES = 16;

/**
 * Key for the emulator sealer. DEV ONLY: it is public, so anything sealed with it is readable by
 * anyone. Production uses Cloud KMS; override with `INBOX_DEV_SEAL_KEY` (base64, 32 bytes).
 */
export const DEV_ONLY_SEAL_KEY = Buffer.from(
  "aW5ib3gtZW11bGF0b3Itb25seS1rZXktMzJieXRlcyE=",
  "base64",
);

/** Seals with AES-256-GCM under a local key; for the emulator and tests. */
export class AesGcmSealer implements TokenSealer {
  readonly #key: Buffer;

  /** Create a sealer for a 32-byte key. */
  constructor(key: Buffer) {
    if (key.length !== 32) {
      throw new Error(`AES-256 key must be 32 bytes, got ${key.length}`);
    }
    this.#key = key;
  }

  /** Encrypt as `aes:` + base64(iv | tag | ciphertext). */
  async seal(plain: string): Promise<string> {
    const iv = randomBytes(IV_BYTES);
    const cipher = createCipheriv("aes-256-gcm", this.#key, iv);
    const ciphertext = Buffer.concat([
      cipher.update(plain, "utf8"),
      cipher.final(),
    ]);
    return (
      AES_PREFIX +
      Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64")
    );
  }

  /** Decrypt and authenticate a sealed string. */
  async open(sealed: string): Promise<string> {
    if (!sealed.startsWith(AES_PREFIX)) {
      throw new Error("not an AES-sealed value");
    }
    const packed = Buffer.from(sealed.slice(AES_PREFIX.length), "base64");
    if (packed.length < IV_BYTES + TAG_BYTES) {
      throw new Error("sealed value is truncated");
    }
    const decipher = createDecipheriv(
      "aes-256-gcm",
      this.#key,
      packed.subarray(0, IV_BYTES),
    );
    decipher.setAuthTag(packed.subarray(IV_BYTES, IV_BYTES + TAG_BYTES));
    return Buffer.concat([
      decipher.update(packed.subarray(IV_BYTES + TAG_BYTES)),
      decipher.final(),
    ]).toString("utf8");
  }
}

/** The subset of the Cloud KMS client the KMS sealer uses. */
export interface KmsClient {
  /** Encrypt with a KMS key. */
  encrypt(request: {
    name: string;
    plaintext: Buffer;
  }): Promise<[{ ciphertext?: Uint8Array | string | null }, ...unknown[]]>;
  /** Decrypt with a KMS key. */
  decrypt(request: {
    name: string;
    ciphertext: Buffer;
  }): Promise<[{ plaintext?: Uint8Array | string | null }, ...unknown[]]>;
}

function toBuffer(
  value: Uint8Array | string | null | undefined,
  field: string,
): Buffer {
  if (value === null || value === undefined) {
    throw new Error(`KMS response has no ${field}`);
  }
  return typeof value === "string"
    ? Buffer.from(value, "base64")
    : Buffer.from(value);
}

/** Seals with a Cloud KMS symmetric key. */
export class KmsSealer implements TokenSealer {
  readonly #keyName: string;
  readonly #client: KmsClient;

  /** Create a sealer for a key name (`projects/…/cryptoKeys/…`). */
  constructor(keyName: string, client: KmsClient) {
    this.#keyName = keyName;
    this.#client = client;
  }

  /** Encrypt as `kms:` + base64(ciphertext). */
  async seal(plain: string): Promise<string> {
    const [response] = await this.#client.encrypt({
      name: this.#keyName,
      plaintext: Buffer.from(plain, "utf8"),
    });
    return (
      KMS_PREFIX +
      toBuffer(response.ciphertext, "ciphertext").toString("base64")
    );
  }

  /** Decrypt a KMS-sealed string. */
  async open(sealed: string): Promise<string> {
    if (!sealed.startsWith(KMS_PREFIX)) {
      throw new Error("not a KMS-sealed value");
    }
    const [response] = await this.#client.decrypt({
      name: this.#keyName,
      ciphertext: Buffer.from(sealed.slice(KMS_PREFIX.length), "base64"),
    });
    return toBuffer(response.plaintext, "plaintext").toString("utf8");
  }
}

/** Build the emulator sealer from `INBOX_DEV_SEAL_KEY`, falling back to {@link DEV_ONLY_SEAL_KEY}. */
export function devSealer(env: NodeJS.ProcessEnv = process.env): AesGcmSealer {
  const override = env.INBOX_DEV_SEAL_KEY;
  return new AesGcmSealer(
    override === undefined
      ? DEV_ONLY_SEAL_KEY
      : Buffer.from(override, "base64"),
  );
}
