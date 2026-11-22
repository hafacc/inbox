import { createHmac, randomBytes } from "node:crypto";
import type { TokenSealer } from "./sealer.js";

/** Bytes in the HMAC key. */
const KEY_BYTES = 32;

/** Where the sealed HMAC key lives, as the hasher reads and creates it. */
export interface HolderKeyStore {
  /** The sealed key, or undefined before the first one is made. */
  read(): Promise<string | undefined>;
  /** Store `sealed` unless a key is already there; returns whichever key is stored. */
  createIfAbsent(sealed: string): Promise<string>;
}

/**
 * Hash an account id with HMAC-SHA256 under `key`, as base64url. It lets a retired address
 * recognize the account that held it without keeping the account id.
 */
export function holderHash(key: Buffer, uid: string): string {
  return createHmac("sha256", key)
    .update(`address-holder:${uid}`, "utf8")
    .digest("base64url");
}

/**
 * Make a hasher for {@link holderHash} whose key is random, made on first use and stored sealed by
 * `sealer` (Cloud KMS in production), so nothing readable at rest can recompute a hash.
 */
export function holderHasher(
  store: HolderKeyStore,
  sealer: TokenSealer,
): (uid: string) => Promise<string> {
  let key: Promise<Buffer> | undefined;
  const load = async () => {
    let sealed = await store.read();
    if (sealed === undefined) {
      const fresh = randomBytes(KEY_BYTES).toString("base64");
      sealed = await store.createIfAbsent(await sealer.seal(fresh));
    }
    const opened = Buffer.from(await sealer.open(sealed), "base64");
    if (opened.length !== KEY_BYTES) {
      throw new Error(`address holder key must be ${KEY_BYTES} bytes`);
    }
    return opened;
  };
  return async (uid) => {
    key ??= load().catch((error: unknown) => {
      key = undefined;
      throw error;
    });
    return holderHash(await key, uid);
  };
}
