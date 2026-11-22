// WKD addressing with no OpenPGP dependency, so the Key Worker can use it too.

// Its own encoder rather than compose/encoding's, which the Key Worker's module resolution can't import.
const encoder = new TextEncoder();

/** z-base-32 alphabet (RFC 6189 §5.1.6), as WKD uses it. */
export const ZBASE32_ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769";

/** z-base-32 of bytes, most significant bit first, no padding. */
export function zbase32(bytes: Uint8Array): string {
  let output = "";
  let buffer = 0;
  let bits = 0;
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += ZBASE32_ALPHABET[(buffer >>> (bits - 5)) & 31];
      bits -= 5;
    }
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) output += ZBASE32_ALPHABET[(buffer << (5 - bits)) & 31];
  return output;
}

/** Split an address at its last `@`; throws when it has none. */
export function splitAddress(address: string): {
  local: string;
  domain: string;
} {
  const trimmed = address.trim();
  const at = trimmed.lastIndexOf("@");
  if (at <= 0 || at === trimmed.length - 1) throw new Error("not an address");
  return {
    local: trimmed.slice(0, at),
    domain: trimmed.slice(at + 1).toLowerCase(),
  };
}

/** WKD's hashed local part: z-base-32 of SHA-1 of the local part with ASCII letters lower-cased. */
export async function wkdHash(localPart: string): Promise<string> {
  const lowered = localPart.replace(/[A-Z]/g, (letter) => letter.toLowerCase());
  const digest = await crypto.subtle.digest("SHA-1", encoder.encode(lowered));
  return zbase32(new Uint8Array(digest));
}

/** Where WKD keeps an address's key: the advanced method first, then the direct one. */
export type WkdUrls = {
  /** `https://openpgpkey.<domain>/.well-known/openpgpkey/<domain>/hu/<hash>?l=<local>` */
  advanced: string;
  /** `https://<domain>/.well-known/openpgpkey/hu/<hash>?l=<local>` */
  direct: string;
};

/** The two WKD URLs for an address (draft-koch-openpgp-webkey-service §3.1). */
export async function wkdUrls(address: string): Promise<WkdUrls> {
  const { local, domain } = splitAddress(address);
  const hash = await wkdHash(local);
  const query = `?l=${encodeURIComponent(local)}`;
  return {
    advanced: `https://openpgpkey.${domain}/.well-known/openpgpkey/${domain}/hu/${hash}${query}`,
    direct: `https://${domain}/.well-known/openpgpkey/hu/${hash}${query}`,
  };
}
