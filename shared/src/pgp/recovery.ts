import { argon2id } from "hash-wasm";
import { fromBase64, randomBytes, toBase64, utf8Bytes } from "./bytes";
import { EFF_LARGE_WORDLIST } from "./eff-wordlist";
import { SEAL_SECRET_LENGTH, seal, unseal } from "./seal";

/** Words in a recovery phrase: 8 × log2(7776) ≈ 103.4 bits. */
export const PHRASE_WORDS = 8;

/** Argon2id settings stored beside each recovery-sealed key, so they can change later. */
export type RecoveryKdf = {
  /** always `argon2id` (version 0x13) */
  name: "argon2id";
  /** memory in KiB */
  memoryKiB: number;
  /** passes over memory */
  iterations: number;
  /** lanes */
  parallelism: number;
};

/**
 * Settings for new recovery seals: 64 MiB, 3 passes, 1 lane, 32-byte output (RFC 9106's second
 * recommended option). About a second on a phone; fine for a one-time recovery.
 */
export const RECOVERY_KDF: RecoveryKdf = {
  name: "argon2id",
  memoryKiB: 65_536,
  iterations: 3,
  parallelism: 1,
};

/** Salt length in bytes for new recovery seals. */
export const RECOVERY_SALT_LENGTH = 16;

/** `users/{uid}.pgp.recoverySealed`. */
export type RecoverySealed = {
  /** Argon2id salt, base64 */
  salt: string;
  /** the private key sealed with the Argon2id output (`seal`) */
  sealedPrivateKey: string;
  /** the Argon2id settings used */
  kdf: RecoveryKdf;
};

const ACCEPT_LIMIT =
  Math.floor(65_536 / EFF_LARGE_WORDLIST.length) * EFF_LARGE_WORDLIST.length;

/**
 * Turn random bytes into phrase words: each 2 bytes, big-endian, give a number below 65,536;
 * numbers ≥ 62,208 (8 × 7,776) are skipped so every word is equally likely, the rest pick word
 * `number % 7776`. Returns the first `count` words; throws when the bytes run out first.
 */
export function wordsFromBytes(
  bytes: Uint8Array,
  count: number = PHRASE_WORDS,
): string[] {
  const words: string[] = [];
  for (let index = 0; index + 1 < bytes.length; index += 2) {
    if (words.length === count) break;
    const value = ((bytes[index] ?? 0) << 8) | (bytes[index + 1] ?? 0);
    if (value >= ACCEPT_LIMIT) continue;
    words.push(EFF_LARGE_WORDLIST[value % EFF_LARGE_WORDLIST.length] ?? "");
  }
  if (words.length < count) throw new Error("not enough random bytes");
  return words;
}

/** A fresh recovery phrase: `PHRASE_WORDS` words joined by single spaces. */
export function generatePhrase(): string {
  for (;;) {
    try {
      return wordsFromBytes(randomBytes(4 * PHRASE_WORDS)).join(" ");
    } catch {
      // Too many skipped numbers in this draw (about 1 in 10^6); draw again.
    }
  }
}

/** A typed phrase checked against the wordlist. */
export type ParsedPhrase = {
  /** the words, lower-cased, in order */
  words: string[];
  /** positions of words not in the list */
  unknown: number[];
  /** the phrase as Argon2id takes it, when it has `PHRASE_WORDS` known words; else null */
  normalized: string | null;
};

const WORD_SET: ReadonlySet<string> = new Set(EFF_LARGE_WORDLIST);

/**
 * Read a typed phrase: split on whitespace (not hyphens, which some words have), lower-case,
 * trim. `normalized` is the words joined by single spaces.
 */
export function parsePhrase(text: string): ParsedPhrase {
  const words = text
    .toLowerCase()
    .split(/\s+/)
    .filter((word) => word !== "");
  const unknown = words.flatMap((word, index) =>
    WORD_SET.has(word) ? [] : [index],
  );
  return {
    words,
    unknown,
    normalized:
      unknown.length === 0 && words.length === PHRASE_WORDS
        ? words.join(" ")
        : null,
  };
}

/** Argon2id of a normalized phrase (UTF-8) with a salt: the 32-byte sealing secret. */
export async function recoverySecret(
  normalizedPhrase: string,
  salt: Uint8Array,
  kdf: RecoveryKdf = RECOVERY_KDF,
): Promise<Uint8Array> {
  if (kdf.name !== "argon2id") throw new Error(`unknown KDF ${kdf.name}`);
  return argon2id({
    password: utf8Bytes(normalizedPhrase),
    salt,
    iterations: kdf.iterations,
    parallelism: kdf.parallelism,
    memorySize: kdf.memoryKiB,
    hashLength: SEAL_SECRET_LENGTH,
    outputType: "binary",
  });
}

/** Seal a private key under a recovery phrase (normalized or as typed). `salt` is for tests only. */
export async function sealWithPhrase(
  phrase: string,
  privateKey: string,
  salt: Uint8Array = randomBytes(RECOVERY_SALT_LENGTH),
  kdf: RecoveryKdf = RECOVERY_KDF,
): Promise<RecoverySealed> {
  const { normalized } = parsePhrase(phrase);
  if (normalized === null) throw new Error("not a recovery phrase");
  const secret = await recoverySecret(normalized, salt, kdf);
  return {
    salt: toBase64(salt),
    sealedPrivateKey: await seal(secret, privateKey),
    kdf,
  };
}

/** Open a recovery seal with a phrase; throws `SealError` for a wrong phrase. */
export async function unsealWithPhrase(
  phrase: string,
  sealed: RecoverySealed,
): Promise<string> {
  const { normalized } = parsePhrase(phrase);
  if (normalized === null) throw new Error("not a recovery phrase");
  const secret = await recoverySecret(
    normalized,
    fromBase64(sealed.salt),
    sealed.kdf,
  );
  return unseal(secret, sealed.sealedPrivateKey);
}
