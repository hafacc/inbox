import { readKey } from "openpgp";
import { addressKey } from "../compose/address";
import { fromBase64, toBase64 } from "./bytes";

/** A parsed `Autocrypt` header (Autocrypt Level 1). */
export type AutocryptHeader = {
  /** the sender address it is for, lower-cased */
  addr: string;
  /** `mutual` when the sender asks for encryption by default */
  preferEncrypt: "mutual" | "nopreference";
  /** the public key, binary OpenPGP */
  keydata: Uint8Array;
};

/** Characters per space-separated piece of `keydata`, so the header can be folded at the spaces. */
export const KEYDATA_CHUNK = 64;

/**
 * The value of the `Autocrypt` header for outgoing mail:
 * `addr=<address>; prefer-encrypt=mutual; keydata=<base64>`, the key data split into
 * `KEYDATA_CHUNK`-character pieces joined by spaces (readers drop the whitespace). Pass it to
 * `buildMime` in `extraHeaders` as `["Autocrypt", value]`; `foldHeader` folds it at the spaces.
 */
export async function autocryptHeaderValue(
  address: string,
  publicKey: string,
): Promise<string> {
  const key = await readKey({ armoredKey: publicKey });
  if (key.isPrivate()) throw new Error("expected a public key");
  const base64 = toBase64(key.write());
  const chunks: string[] = [];
  for (let start = 0; start < base64.length; start += KEYDATA_CHUNK) {
    chunks.push(base64.slice(start, start + KEYDATA_CHUNK));
  }
  return `addr=${addressKey(address)}; prefer-encrypt=mutual; keydata=${chunks.join(" ")}`;
}

/**
 * Read an `Autocrypt` header value (unfolded). Null when it isn't valid: `addr` or `keydata`
 * missing, keydata not base64, or an attribute this version doesn't know that doesn't start with
 * `_` (the spec says to ignore such headers). Whether `addr` matches the message's `From` is the
 * caller's check (`autocryptFor`).
 */
export function parseAutocrypt(value: string): AutocryptHeader | null {
  const attributes = new Map<string, string>();
  for (const piece of value.split(";")) {
    if (piece.trim() === "") continue;
    const equals = piece.indexOf("=");
    if (equals <= 0) return null;
    const name = piece.slice(0, equals).trim();
    const attributeValue = piece.slice(equals + 1).trim();
    if (attributes.has(name)) return null;
    attributes.set(name, attributeValue);
  }
  for (const name of attributes.keys()) {
    if (
      !["addr", "prefer-encrypt", "keydata"].includes(name) &&
      !name.startsWith("_")
    ) {
      return null;
    }
  }
  const addrValue = attributes.get("addr");
  const addr = addrValue === undefined ? "" : addressKey(addrValue);
  const keydataText = attributes.get("keydata");
  if (!addr || !keydataText) return null;
  let keydata: Uint8Array;
  try {
    keydata = fromBase64(keydataText);
  } catch {
    return null;
  }
  if (keydata.length === 0) return null;
  return {
    addr,
    preferEncrypt:
      attributes.get("prefer-encrypt") === "mutual" ? "mutual" : "nopreference",
    keydata,
  };
}

/**
 * The Autocrypt header that applies to a message: of the `Autocrypt` values given, the one whose
 * `addr` is the `From` address; null when none or more than one (the spec ignores both).
 */
export function autocryptFor(
  values: readonly string[],
  fromAddress: string,
): AutocryptHeader | null {
  const from = addressKey(fromAddress);
  const matching = values
    .map(parseAutocrypt)
    .filter((parsed): parsed is AutocryptHeader => parsed?.addr === from);
  return matching.length === 1 ? (matching[0] ?? null) : null;
}
