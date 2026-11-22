import {
  generateKey,
  type Key,
  type PublicKey,
  readKey,
  readKeys,
  readPrivateKey,
} from "openpgp";
import { addressKey } from "../compose/address";
import { bytesToBinary } from "./bytes";

/** A new key pair, both halves armored; the private key is not passphrase-protected (it is sealed). */
export type KeyPair = {
  /** armored public key, what `users/{uid}.pgp.publicKey` holds */
  publicKey: string;
  /** armored private key, unprotected: seal it before it leaves memory */
  privateKey: string;
  /** primary key fingerprint, lower-case hex */
  fingerprint: string;
};

/**
 * Make an OpenPGP key pair for an address: an EdDSA (Ed25519) primary key for signing and
 * certifying, an ECDH Curve25519 subkey for encryption, in the v4 format with the "legacy" Curve25519
 * algorithm ids (RFC 4880bis), which GnuPG, Thunderbird, Proton, Mailvelope and FlowCrypt all read.
 * One user id, `name <address>` (just `<address>` when `name` is empty), no expiry.
 */
export async function generateKeyPair(options: {
  /** the @hafa.cc address */
  address: string;
  /** display name, or empty */
  name: string;
  /** creation time; defaults to now (tests pin it) */
  date?: Date;
}): Promise<KeyPair> {
  const userId =
    options.name.trim() === ""
      ? { email: options.address }
      : { name: options.name.trim(), email: options.address };
  const pair = await generateKey({
    type: "ecc",
    curve: "curve25519Legacy",
    userIDs: [userId],
    date: options.date,
    format: "object",
  });
  return {
    publicKey: pair.publicKey.armor(),
    privateKey: pair.privateKey.armor(),
    fingerprint: pair.publicKey.getFingerprint(),
  };
}

/** Read keys, armored or binary (a WKD answer is binary, keys.openpgp.org's armored). */
export async function readAnyKeys(data: Uint8Array | string): Promise<Key[]> {
  if (typeof data === "string") return readKeys({ armoredKeys: data });
  const start = bytesToBinary(data.subarray(0, 64)).trimStart();
  return start.startsWith("-----BEGIN PGP")
    ? readKeys({ armoredKeys: new TextDecoder().decode(data) })
    : readKeys({ binaryKeys: data });
}

/** The lower-cased addresses in a key's user ids. */
export function keyAddresses(key: Key): string[] {
  return key.users.flatMap((user) => {
    const email = user.userID?.email;
    return email ? [addressKey(email)] : [];
  });
}

/** Whether a key can be encrypted to now: not revoked or expired, with a valid encryption (sub)key. */
export async function canEncryptTo(
  key: Key,
  date = new Date(),
): Promise<boolean> {
  try {
    await key.getEncryptionKey(undefined, date);
    return true;
  } catch {
    return false;
  }
}

/**
 * From keys found for an address, the one to encrypt to: a public key (private keys are refused)
 * that has a user id with that address and can be encrypted to. Returns it armored, or null.
 */
export async function pickKeyFor(
  keys: readonly Key[],
  address: string,
  date = new Date(),
): Promise<string | null> {
  const wanted = addressKey(address);
  for (const key of keys) {
    if (key.isPrivate()) continue;
    if (!keyAddresses(key).includes(wanted)) continue;
    if (await canEncryptTo(key, date)) return key.armor();
  }
  return null;
}

/** Read an armored public key; refuses private keys. */
export async function readPublicKey(armored: string): Promise<PublicKey> {
  const key = await readKey({ armoredKey: armored });
  if (key.isPrivate()) throw new Error("expected a public key");
  return key as PublicKey;
}

/** Read an unprotected armored private key (as unsealed). */
export async function readUnlockedPrivateKey(armored: string) {
  const key = await readPrivateKey({ armoredKey: armored });
  if (!key.isDecrypted()) throw new Error("the private key is locked");
  return key;
}

/** The fingerprint (lower-case hex) of an armored key. */
export async function fingerprintOf(armored: string): Promise<string> {
  return (await readKey({ armoredKey: armored })).getFingerprint();
}
