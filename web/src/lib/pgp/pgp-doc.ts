// users/{uid}.pgp as the app reads it (docs/pgp-contract.md "Firestore").

import type { RecoverySealed } from "@inbox/shared/pgp";
import { MAX_PASSKEY_NAME, type SealedCopy } from "./passkey";

/** The account's key: public key, a sealed copy per passkey, and the recovery seal. */
export type PgpDoc = {
  publicKey: string;
  sealed: SealedCopy[];
  recoverySealed: RecoverySealed;
};

function millis(value: unknown): number {
  if (typeof value === "number") return value;
  if (
    typeof value === "object" &&
    value !== null &&
    "toMillis" in value &&
    typeof value.toMillis === "function"
  ) {
    return Number(value.toMillis());
  }
  return 0;
}

function sealedCopy(raw: unknown): SealedCopy | null {
  if (typeof raw !== "object" || raw === null) return null;
  const entry = raw as Record<string, unknown>;
  if (
    typeof entry.credentialId !== "string" ||
    typeof entry.salt !== "string" ||
    typeof entry.sealedPrivateKey !== "string"
  ) {
    return null;
  }
  return {
    credentialId: entry.credentialId,
    salt: entry.salt,
    sealedPrivateKey: entry.sealedPrivateKey,
    name: typeof entry.name === "string" ? entry.name : "",
    addedAt: millis(entry.addedAt),
  };
}

/** Read `users/{uid}.pgp`; null when absent or not usable. */
export function pgpDocFrom(raw: unknown): PgpDoc | null {
  if (typeof raw !== "object" || raw === null) return null;
  const data = raw as Record<string, unknown>;
  if (typeof data.publicKey !== "string") return null;
  const recovery = data.recoverySealed as Record<string, unknown> | undefined;
  const kdf = recovery?.kdf as Record<string, unknown> | undefined;
  if (
    typeof recovery?.salt !== "string" ||
    typeof recovery.sealedPrivateKey !== "string" ||
    kdf?.name !== "argon2id"
  ) {
    return null;
  }
  return {
    publicKey: data.publicKey,
    sealed: (Array.isArray(data.sealed) ? data.sealed : [])
      .map(sealedCopy)
      .filter((entry): entry is SealedCopy => entry !== null),
    recoverySealed: {
      salt: recovery.salt,
      sealedPrivateKey: recovery.sealedPrivateKey,
      kdf: {
        name: "argon2id",
        memoryKiB: Number(kdf.memoryKiB),
        iterations: Number(kdf.iterations),
        parallelism: Number(kdf.parallelism),
      },
    },
  };
}

/** A passkey label the rules take: trimmed, at most `MAX_PASSKEY_NAME` characters. */
export function passkeyName(name: string): string {
  return name.trim().slice(0, MAX_PASSKEY_NAME) || "Passkey";
}

/** The sealed list without one passkey. */
export function withoutPasskey(
  sealed: readonly SealedCopy[],
  credentialId: string,
): SealedCopy[] {
  return sealed.filter((entry) => entry.credentialId !== credentialId);
}

/** The sealed list with a passkey added (replacing one with the same id). */
export function withPasskey(
  sealed: readonly SealedCopy[],
  added: SealedCopy,
): SealedCopy[] {
  return [...withoutPasskey(sealed, added.credentialId), added];
}

/** Two distinct word positions (0-based, ascending) to ask back when confirming a phrase. */
export function confirmPositions(
  wordCount: number,
  random: () => number = Math.random,
): [number, number] {
  const first = Math.floor(random() * wordCount);
  let second = Math.floor(random() * (wordCount - 1));
  if (second >= first) second++;
  return first < second ? [first, second] : [second, first];
}

/** Whether a typed word matches the phrase's word, ignoring case and spaces. */
export function wordMatches(typed: string, word: string): boolean {
  return typed.trim().toLowerCase() === word.toLowerCase();
}
