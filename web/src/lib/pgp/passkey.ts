// Passkeys whose PRF output seals the OpenPGP private key (docs/pgp-contract.md "Passkeys").

/** The relying party in production; Android uses the same, so one passkey serves both. */
export const RP_ID = "inbox.hafa.cc";

/** One passkey's sealed copy, as `users/{uid}.pgp.sealed` holds it (`addedAt` in epoch ms here). */
export type SealedCopy = {
  /** the passkey's credential id, base64url */
  credentialId: string;
  /** its PRF salt, base64url */
  salt: string;
  /** the private key sealed with its PRF output */
  sealedPrivateKey: string;
  /** label shown in Settings */
  name: string;
  /** when it was added, epoch milliseconds */
  addedAt: number;
};

/** The longest passkey label the rules take. */
export const MAX_PASSKEY_NAME = 100;

/** The most passkeys the rules take. */
export const MAX_PASSKEYS = 8;

/** The passkey provider can't give a PRF secret, so it can't unlock mail. */
export class PrfUnsupported extends Error {
  constructor() {
    super(
      "This password manager can't unlock mail. Use Google Password Manager or iCloud Keychain.",
    );
    this.name = "PrfUnsupported";
  }
}

/** The relying party id for a host: the real one, or `localhost` while developing. */
export function rpIdFor(hostname: string): string {
  return hostname === "localhost" || hostname === "127.0.0.1"
    ? "localhost"
    : RP_ID;
}

/** Bytes as base64url without padding. */
export function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/** Base64 or base64url text as bytes. */
export function fromBase64Url(text: string): Uint8Array<ArrayBuffer> {
  const standard = text
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/=+$/, "");
  const binary = atob(standard + "=".repeat((4 - (standard.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function random(length: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(length));
}

/** Who the passkey is for. */
export type PasskeyUser = {
  /** Firebase uid */
  uid: string;
  /** the Gmail address */
  gmail: string;
  /** the @hafa.cc address */
  address: string;
};

/** `navigator.credentials.create` options for a new passkey with a PRF salt. */
export function creationOptions(
  user: PasskeyUser,
  salt: Uint8Array<ArrayBuffer>,
  rpId: string,
  challenge: Uint8Array<ArrayBuffer> = random(32),
): PublicKeyCredentialCreationOptions {
  return {
    rp: { id: rpId, name: "inbox" },
    user: {
      id: new TextEncoder().encode(user.uid),
      name: user.gmail,
      displayName: user.address,
    },
    challenge,
    pubKeyCredParams: [
      { type: "public-key", alg: -7 },
      { type: "public-key", alg: -257 },
    ],
    authenticatorSelection: {
      residentKey: "required",
      userVerification: "required",
    },
    extensions: { prf: { eval: { first: salt } } } as never,
  };
}

/** `navigator.credentials.get` options that ask every sealed passkey for its PRF secret. */
export function requestOptions(
  sealed: readonly SealedCopy[],
  rpId: string,
  challenge: Uint8Array<ArrayBuffer> = random(32),
): PublicKeyCredentialRequestOptions {
  return {
    rpId,
    challenge,
    userVerification: "required",
    allowCredentials: sealed.map((entry) => ({
      type: "public-key" as const,
      id: fromBase64Url(entry.credentialId),
    })),
    extensions: {
      prf: {
        evalByCredential: Object.fromEntries(
          sealed.map((entry) => [
            entry.credentialId,
            { first: fromBase64Url(entry.salt) },
          ]),
        ),
      },
    } as never,
  };
}

type PrfOutputs = {
  enabled?: boolean;
  results?: { first?: BufferSource };
};

function prfOf(credential: PublicKeyCredential): PrfOutputs | undefined {
  return (
    credential.getClientExtensionResults() as { prf?: PrfOutputs } | undefined
  )?.prf;
}

/** The 32-byte PRF secret in extension results, or null. */
export function prfSecret(
  outputs: PrfOutputs | undefined,
): Uint8Array<ArrayBuffer> | null {
  const first = outputs?.results?.first;
  if (first === undefined) return null;
  const bytes =
    first instanceof ArrayBuffer
      ? new Uint8Array(first)
      : new Uint8Array(first.buffer, first.byteOffset, first.byteLength);
  return bytes.length === 32 ? new Uint8Array(bytes) : null;
}

/** A passkey just made, with its PRF secret. */
export type CreatedPasskey = {
  credentialId: string;
  salt: string;
  secret: Uint8Array<ArrayBuffer>;
};

/**
 * Make a passkey and get its PRF secret: from creation when the provider evaluates it then, else
 * from one assertion straight after. Throws `PrfUnsupported` when the provider has no PRF.
 */
export async function createPasskey(
  user: PasskeyUser,
  rpId: string,
): Promise<CreatedPasskey> {
  const salt = random(32);
  const credential = (await navigator.credentials.create({
    publicKey: creationOptions(user, salt, rpId),
  })) as PublicKeyCredential | null;
  if (credential === null) throw new Error("no passkey was made");
  const prf = prfOf(credential);
  if (prf?.enabled !== true && prf?.results === undefined) {
    throw new PrfUnsupported();
  }
  const credentialId = toBase64Url(new Uint8Array(credential.rawId));
  const saltText = toBase64Url(salt);
  let secret = prfSecret(prf);
  if (secret === null) {
    const unlocked = await unlockWithPasskey(
      [
        {
          credentialId,
          salt: saltText,
          sealedPrivateKey: "",
          name: "",
          addedAt: 0,
        },
      ],
      rpId,
    );
    secret = unlocked.secret;
  }
  return { credentialId, salt: saltText, secret };
}

/** Which sealed copy a passkey assertion opened, and its secret. */
export type PasskeyUnlock = {
  entry: SealedCopy;
  secret: Uint8Array<ArrayBuffer>;
};

/** Ask for one of the sealed passkeys; throws `PrfUnsupported` when it gives no PRF secret. */
export async function unlockWithPasskey(
  sealed: readonly SealedCopy[],
  rpId: string,
): Promise<PasskeyUnlock> {
  const assertion = (await navigator.credentials.get({
    publicKey: requestOptions(sealed, rpId),
  })) as PublicKeyCredential | null;
  if (assertion === null) throw new Error("no passkey was chosen");
  const id = toBase64Url(new Uint8Array(assertion.rawId));
  const entry = sealed.find((candidate) => candidate.credentialId === id);
  const secret = prfSecret(prfOf(assertion));
  if (entry === undefined || secret === null) throw new PrfUnsupported();
  return { entry, secret };
}

/** Whether this browser can make passkeys at all. */
export function passkeysAvailable(): boolean {
  return (
    typeof PublicKeyCredential !== "undefined" &&
    typeof navigator.credentials?.create === "function"
  );
}

/** Whether the user closed the passkey prompt rather than something failing. */
export function isPasskeyCancel(error: unknown): boolean {
  return (
    error instanceof DOMException &&
    (error.name === "NotAllowedError" || error.name === "AbortError")
  );
}

/** A label for this device's passkey, such as "Chrome on Mac". */
export function deviceLabel(userAgent: string): string {
  const browser = /Edg\//.test(userAgent)
    ? "Edge"
    : /SamsungBrowser\//.test(userAgent)
      ? "Samsung Internet"
      : /Firefox\//.test(userAgent)
        ? "Firefox"
        : /Chrome\//.test(userAgent)
          ? "Chrome"
          : /Safari\//.test(userAgent)
            ? "Safari"
            : "Browser";
  const system = /Android/.test(userAgent)
    ? "Android"
    : /iPhone|iPad/.test(userAgent)
      ? "iPhone"
      : /Mac OS X|Macintosh/.test(userAgent)
        ? "Mac"
        : /Windows/.test(userAgent)
          ? "Windows"
          : /CrOS/.test(userAgent)
            ? "ChromeOS"
            : /Linux/.test(userAgent)
              ? "Linux"
              : "";
  return system === "" ? browser : `${browser} on ${system}`;
}
