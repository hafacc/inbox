import { addressKey } from "../compose/address";
import { pickKeyFor, readAnyKeys } from "./keys";
import { splitAddress } from "./wkd";

/** The domain whose mail may be encrypted (mail from other addresses always goes as is). */
export const ENCRYPTING_DOMAIN = "hafa.cc";

/** keys.openpgp.org's lookup by (verified) address; answers armored, 404 when unknown. */
export function vksUrl(address: string): string {
  return `https://keys.openpgp.org/vks/v1/by-email/${encodeURIComponent(address.trim())}`;
}

/**
 * The key to use from a lookup answer (WKD binary, keys.openpgp.org armored, or an Autocrypt
 * header's key data) for an address: armored, or null when the answer has no public key with that
 * address that can be encrypted to. Never throws on bad data.
 */
export async function keyFromAnswer(
  data: Uint8Array | string,
  address: string,
  date = new Date(),
): Promise<string | null> {
  try {
    return await pickKeyFor(await readAnyKeys(data), address, date);
  } catch {
    return null;
  }
}

/** Why a message will or won't be encrypted. */
export type EncryptionReason =
  | "encrypt"
  | "not-encrypting-sender"
  | "no-recipients"
  | "missing-keys";

/** Whether to encrypt a message, and which recipients lack a key. */
export type EncryptionDecision = {
  /** encrypt iff true */
  encrypt: boolean;
  /** why */
  reason: EncryptionReason;
  /** recipients (lower-cased) with no key, in the order given; empty unless `missing-keys` */
  missing: string[];
};

/**
 * Decide whether to encrypt: only mail from an `@hafa.cc` address, and only when every recipient
 * (To, Cc and Bcc) has a key. `keys` maps lower-cased addresses to an armored key, or null / absent
 * when none was found.
 */
export function encryptionDecision(
  from: string,
  recipients: readonly string[],
  keys: Readonly<Record<string, string | null | undefined>>,
): EncryptionDecision {
  let domain = "";
  try {
    domain = splitAddress(from).domain;
  } catch {
    // An unusable From never encrypts.
  }
  if (domain !== ENCRYPTING_DOMAIN) {
    return { encrypt: false, reason: "not-encrypting-sender", missing: [] };
  }
  const unique = [...new Set(recipients.map(addressKey))];
  if (unique.length === 0) {
    return { encrypt: false, reason: "no-recipients", missing: [] };
  }
  const missing = unique.filter((address) => !keys[address]);
  return missing.length === 0
    ? { encrypt: true, reason: "encrypt", missing }
    : { encrypt: false, reason: "missing-keys", missing };
}
