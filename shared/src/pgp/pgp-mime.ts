import {
  createMessage,
  decrypt,
  encrypt,
  type Key,
  readMessage,
  readSignature,
  verify,
} from "openpgp";
import { addressKey } from "../compose/address";
import { CRLF, foldHeader } from "../compose/encoding";
import { binaryToBytes, bytesToBinary, utf8Bytes, utf8Text } from "./bytes";
import { keyAddresses, readPublicKey, readUnlockedPrivateKey } from "./keys";
import {
  decodedText,
  decodeHeaderValue,
  header,
  type MimeEntity,
  parseHeaders,
  parseMime,
  walk,
} from "./mime";

/** Outer `Subject` of an encrypted message; the real one travels inside. */
export const OBSCURED_SUBJECT = "...";

/** Headers copied into the encrypted part (and, but for Subject, kept outside too). Never Bcc. */
export const PROTECTED_HEADERS = [
  "From",
  "To",
  "Cc",
  "Reply-To",
  "Subject",
  "Date",
  "Message-ID",
  "In-Reply-To",
  "References",
] as const;

/** Preamble of the multipart/encrypted body, for readers that can't decrypt. */
export const PGP_MIME_PREAMBLE =
  "This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156)";

const CONTENT_HEADER = /^content-/i;

/** A failure to open an encrypted message. */
export type PgpErrorCode =
  /** not PGP/MIME and no inline PGP block */
  | "not-encrypted"
  /** encrypted, but to none of the keys given ("Can't open: encrypted to another key") */
  | "wrong-key"
  /** damaged or not OpenPGP */
  | "malformed";

/** An encrypted message couldn't be opened. */
export class PgpError extends Error {
  /** why */
  readonly code: PgpErrorCode;

  /** Create an error with a code and a message. */
  constructor(code: PgpErrorCode, message: string) {
    super(message);
    this.name = "PgpError";
    this.code = code;
  }
}

/** A message split for PGP/MIME: the outer header lines and the part to encrypt. */
export type ProtectedParts = {
  /** outer header fields, folded, without the multipart/encrypted Content-Type */
  outerHeaders: string[];
  /** the cryptographic payload: content headers, protected headers, `HP-Outer`s, then the body */
  inner: string;
};

/** How to split a message for encryption. */
export type ProtectOptions = {
  /**
   * The message is a Bcc copy (`planEncryptedSends`): `To` and `Cc` travel only inside, and `Bcc`
   * (naming just its recipient) goes inside as well as outside
   */
  bccCopy?: boolean;
};

const BCC_COPY_HIDDEN = new Set(["to", "cc"]);

// `PROTECTED_HEADERS` lower-case, and the same with Bcc (what a Bcc copy carries inside).
const PROTECTED_NAMES: ReadonlySet<string> = new Set(
  PROTECTED_HEADERS.map((name) => name.toLowerCase()),
);
const PROTECTED_NAMES_WITH_BCC: ReadonlySet<string> = new Set([
  ...PROTECTED_NAMES,
  "bcc",
]);

/**
 * Split a built message (`buildMime`'s output) for encryption, with protected headers in the
 * style of RFC 9788 and the older "memoryhole" `protected-headers="v1"` that Thunderbird and Proton
 * read:
 * - outer: every header but the `Content-*` ones, in order, with `Subject` replaced by
 *   `OBSCURED_SUBJECT`.
 * - inner: the `Content-*` headers (the Content-Type gaining `protected-headers="v1"` and
 *   `hp="cipher"`), then the `PROTECTED_HEADERS` present, as written (Bcc never goes in), then one
 *   `HP-Outer: <Name>: <outer value>` per protected header also outside, then a blank line and the
 *   original body.
 *
 * With `bccCopy`, `To` and `Cc` are left out of the outer headers (so delivery goes to the `Bcc`
 * recipient alone, and they get no `HP-Outer`), and `Bcc` goes inside too, in its place among the
 * protected headers (RFC 5322 §3.6.3: a Bcc recipient's copy names only that recipient).
 */
export function protectedParts(
  mime: string,
  options: ProtectOptions = {},
): ProtectedParts {
  const match = /\r?\n\r?\n/.exec(mime);
  if (match === null) throw new Error("message has no body");
  const fields = parseHeaders(mime.slice(0, match.index));
  const body = mime.slice(match.index + match[0].length);
  const bccCopy = options.bccCopy === true;
  const content = fields.filter((field) => CONTENT_HEADER.test(field.name));
  const headers = fields.filter((field) => !CONTENT_HEADER.test(field.name));
  const outer = headers.filter(
    (field) => !(bccCopy && BCC_COPY_HIDDEN.has(field.name.toLowerCase())),
  );
  const outerHeaders = outer.map((field) =>
    field.name.toLowerCase() === "subject"
      ? `Subject: ${OBSCURED_SUBJECT}`
      : field.raw,
  );
  const insideNames = bccCopy ? PROTECTED_NAMES_WITH_BCC : PROTECTED_NAMES;
  const contentType = content.find(
    (field) => field.name.toLowerCase() === "content-type",
  );
  const innerContent = [
    foldHeader(
      "Content-Type",
      `${contentType?.value ?? "text/plain; charset=UTF-8"}; protected-headers="v1"; hp="cipher"`,
    ),
    ...content
      .filter((field) => field !== contentType)
      .map((field) => field.raw),
  ];
  const kept = headers.filter((field) =>
    insideNames.has(field.name.toLowerCase()),
  );
  const hpOuter = kept
    .filter(
      (field) =>
        PROTECTED_NAMES.has(field.name.toLowerCase()) && outer.includes(field),
    )
    .map((field) =>
      foldHeader(
        "HP-Outer",
        `${field.name}: ${field.name.toLowerCase() === "subject" ? OBSCURED_SUBJECT : field.value}`,
      ),
    );
  return {
    outerHeaders,
    inner: [
      ...innerContent,
      ...kept.map((field) => field.raw),
      ...hpOuter,
      "",
      body,
    ].join(CRLF),
  };
}

/** The whole PGP/MIME message (RFC 3156 §4) around an armored OpenPGP message. */
export function assemblePgpMime(
  outerHeaders: readonly string[],
  armored: string,
  boundary: string,
): string {
  const delimiter = `=_${boundary}_enc`;
  const armoredLines = armored.replace(/\r?\n/g, CRLF).replace(/(\r\n)+$/, "");
  return [
    ...outerHeaders,
    foldHeader(
      "Content-Type",
      `multipart/encrypted; protocol="application/pgp-encrypted"; boundary="${delimiter}"`,
    ),
    "",
    PGP_MIME_PREAMBLE,
    `--${delimiter}`,
    "Content-Type: application/pgp-encrypted",
    "Content-Description: PGP/MIME version identification",
    "",
    "Version: 1",
    "",
    `--${delimiter}`,
    'Content-Type: application/octet-stream; name="encrypted.asc"',
    "Content-Description: OpenPGP encrypted message",
    'Content-Disposition: inline; filename="encrypted.asc"',
    "",
    armoredLines,
    `--${delimiter}--`,
    "",
  ].join(CRLF);
}

/**
 * Encrypt and sign a built message as PGP/MIME (RFC 3156, signed and encrypted in one OpenPGP
 * message, §6.2) with protected headers (`protectedParts`).
 *
 * `encryptionKeys` are armored public keys of the message's recipients **and the sender's own**,
 * so the Sent copy and the draft stay readable: with Bcc recipients, encrypt each message
 * `planEncryptedSends` plans separately, to its own `encryptTo`, so no recipient learns of a Bcc
 * recipient's key. `signingKey` is the sender's unlocked private key, armored. `boundary` is a
 * fresh random token (`randomBoundary()`). `bccCopy` is passed to `protectedParts`.
 */
export async function encryptMime(
  mime: string,
  options: {
    /** armored public keys: every recipient's and the sender's */
    encryptionKeys: readonly string[];
    /** the sender's unlocked private key, armored */
    signingKey: string;
    /** random token for the boundary */
    boundary: string;
    /** signature time; defaults to now */
    date?: Date;
    /** the message is a Bcc copy (`ProtectOptions`) */
    bccCopy?: boolean;
  },
): Promise<string> {
  const { outerHeaders, inner } = protectedParts(mime, {
    bccCopy: options.bccCopy,
  });
  const encryptionKeys = await Promise.all(
    options.encryptionKeys.map(readPublicKey),
  );
  const signingKey = await readUnlockedPrivateKey(options.signingKey);
  const armored = await encrypt({
    message: await createMessage({
      binary: utf8Bytes(inner),
      format: "binary",
    }),
    encryptionKeys,
    signingKeys: signingKey,
    date: options.date,
  });
  return assemblePgpMime(outerHeaders, armored, options.boundary);
}

/** How a message is encrypted. */
export type EncryptionKind = "pgp-mime" | "inline";

const INLINE_BEGIN = "-----BEGIN PGP MESSAGE-----";
const INLINE_BLOCK =
  /-----BEGIN PGP MESSAGE-----[\s\S]*?-----END PGP MESSAGE-----/;

function isPgpMime(entity: MimeEntity): boolean {
  return (
    entity.type === "multipart/encrypted" &&
    (entity.params.protocol ?? "").toLowerCase() === "application/pgp-encrypted"
  );
}

/**
 * The PGP/MIME entity of a message: the root, or one of its direct children when a mailing list
 * wrapped it in a multipart/mixed with a footer; undefined when neither is.
 */
function pgpMimeEntity(entity: MimeEntity): MimeEntity | undefined {
  if (isPgpMime(entity)) return entity;
  return entity.type.startsWith("multipart/")
    ? entity.parts.find(isPgpMime)
    : undefined;
}

function inlinePart(entity: MimeEntity): MimeEntity | undefined {
  return walk(entity).find(
    (part) =>
      part.type === "text/plain" &&
      header(part, "Content-Disposition")
        ?.toLowerCase()
        .startsWith("attachment") !== true &&
      decodedText(part).includes(INLINE_BEGIN),
  );
}

/** Whether a parsed message is encrypted, and how; null when it isn't. */
export function encryptionKind(entity: MimeEntity): EncryptionKind | null {
  if (pgpMimeEntity(entity) !== undefined) return "pgp-mime";
  return inlinePart(entity) === undefined ? null : "inline";
}

/** Signature check result. */
export type SignatureStatus =
  /** signed by one of the verification keys, and it checks out */
  | "valid"
  /** signed by one of the verification keys, and it doesn't check out (show a warning) */
  | "invalid"
  /** signed by a key we don't have for the sender */
  | "unknown-key"
  /** not signed */
  | "unsigned";

/** A decrypted message. */
export type DecryptedMessage = {
  /** how it was encrypted */
  kind: EncryptionKind;
  /** the decrypted content: the inner MIME entity, or for inline PGP a text/plain entity */
  entity: MimeEntity;
  /**
   * protected headers from inside, names lower-case, values decoded (`subject`, `from`, `to`,
   * `cc`, `reply-to`, `date`, `message-id`, `in-reply-to`, `references`, and in a Bcc copy `bcc`);
   * null when there are none. Show these instead of the outer ones.
   *
   * The address headers (`ADDRESS_HEADERS`: from, reply-to, to, cc) come from inside only when
   * the signature is `valid` and the signing key has a user id for the inner From address;
   * otherwise each holds the outer header's decoded value (left out when there is none outside).
   */
  protectedHeaders: Record<string, string> | null;
  /**
   * address headers (lower-case names) whose inner value differed from the outer one and was not
   * used because the signature doesn't vouch for it; empty when none (show a warning when not)
   */
  mismatchedHeaders: string[];
  /** the sender's signature */
  signature: {
    /** result */
    status: SignatureStatus;
    /** signing key id, hex, or null when unsigned */
    keyId: string | null;
  };
};

type SignatureResult = DecryptedMessage["signature"];

function hasKeyId(key: Key, keyId: string): boolean {
  return key.getKeyIDs().some((id) => id.toHex() === keyId);
}

function keyMatches(keys: readonly Key[], keyId: string): boolean {
  return keys.some((key) => hasKeyId(key, keyId));
}

/**
 * The result for the first signature by one of `verificationKeys`; `unknown-key` (with the first
 * signature's key id) when none is by one of them.
 */
async function signatureOf(
  signatures: readonly {
    keyID: { toHex(): string };
    verified: Promise<true>;
  }[],
  verificationKeys: readonly Key[],
): Promise<SignatureResult> {
  for (const signature of signatures) signature.verified.catch(() => {});
  const [first] = signatures;
  if (first === undefined) return { status: "unsigned", keyId: null };
  const known = signatures.find((signature) =>
    keyMatches(verificationKeys, signature.keyID.toHex()),
  );
  if (known === undefined) {
    return { status: "unknown-key", keyId: first.keyID.toHex() };
  }
  const keyId = known.keyID.toHex();
  try {
    await known.verified;
    return { status: "valid", keyId };
  } catch {
    return { status: "invalid", keyId };
  }
}

function syntheticText(text: string): MimeEntity {
  const head = "Content-Type: text/plain; charset=utf-8";
  const body = bytesToBinary(utf8Bytes(text));
  return parseMime(`${head}${CRLF}${CRLF}${body}`);
}

function protectedHeadersOf(entity: MimeEntity): Record<string, string> | null {
  const found: Record<string, string> = {};
  for (const field of entity.headers) {
    const name = field.name.toLowerCase();
    if (PROTECTED_NAMES_WITH_BCC.has(name) && !(name in found)) {
      found[name] = decodeHeaderValue(field.value);
    }
  }
  return Object.keys(found).length === 0 ? null : found;
}

/** Protected headers that name people: taken from inside only under a matching valid signature. */
export const ADDRESS_HEADERS = ["from", "reply-to", "to", "cc"] as const;

/** The one address in a From value (`Name <a@b>` or `a@b`), lower-case, or null. */
function soleAddress(value: string): string | null {
  const bracketed = [...value.matchAll(/<([^<>]*)>/g)];
  if (bracketed.length > 1) return null;
  const address = addressKey(bracketed[0]?.[1] ?? value);
  return /^[^\s@,;<>]+@[^\s@,;<>]+$/.test(address) ? address : null;
}

/** Whether the signature is valid and made by a key with a user id for the inner From address. */
function vouchesForFrom(
  signature: SignatureResult,
  inner: Record<string, string>,
  verificationKeys: readonly Key[],
): boolean {
  if (signature.status !== "valid" || signature.keyId === null) return false;
  const from = soleAddress(inner.from ?? "");
  if (from === null) return false;
  const keyId = signature.keyId;
  return verificationKeys.some(
    (key) => hasKeyId(key, keyId) && keyAddresses(key).includes(from),
  );
}

function sameValue(
  first: string | undefined,
  second: string | undefined,
): boolean {
  const normalize = (value: string | undefined) =>
    value?.replace(/\s+/g, " ").trim();
  return normalize(first) === normalize(second);
}

/**
 * The protected headers to show: the inner ones, but the address headers from outside unless the
 * signature vouches for the inner From (`vouchesForFrom`).
 */
function trustedHeaders(
  inner: Record<string, string> | null,
  outer: MimeEntity,
  signature: SignatureResult,
  verificationKeys: readonly Key[],
): { headers: Record<string, string> | null; mismatched: string[] } {
  if (inner === null) return { headers: null, mismatched: [] };
  if (vouchesForFrom(signature, inner, verificationKeys)) {
    return { headers: inner, mismatched: [] };
  }
  const headers: Record<string, string> = { ...inner };
  const mismatched: string[] = [];
  for (const name of ADDRESS_HEADERS) {
    const outside = header(outer, name);
    const outerValue =
      outside === undefined ? undefined : decodeHeaderValue(outside);
    if (name in inner && !sameValue(inner[name], outerValue)) {
      mismatched.push(name);
    }
    if (outerValue === undefined) delete headers[name];
    else if (name in inner) headers[name] = outerValue;
  }
  return { headers, mismatched };
}

async function openArmored(
  armored: string,
  decryptionKeys: readonly Key[],
  verificationKeys: readonly Key[],
): Promise<{ data: Uint8Array; signature: SignatureResult }> {
  let message: Awaited<ReturnType<typeof readMessage>>;
  try {
    message = await readMessage({ armoredMessage: armored });
  } catch (error) {
    throw new PgpError("malformed", (error as Error).message);
  }
  const wanted = message.getEncryptionKeyIDs().map((id) => id.toHex());
  const wildcard = wanted.includes("0000000000000000");
  if (!wildcard && !wanted.some((id) => keyMatches(decryptionKeys, id))) {
    throw new PgpError("wrong-key", "encrypted to another key");
  }
  let result: Awaited<ReturnType<typeof decrypt<Uint8Array>>>;
  try {
    result = await decrypt({
      message,
      decryptionKeys: decryptionKeys as never,
      verificationKeys: [...verificationKeys],
      format: "binary",
    });
  } catch (error) {
    const text = (error as Error).message;
    throw new PgpError(
      /session key|decryption key/i.test(text) ? "wrong-key" : "malformed",
      text,
    );
  }
  return {
    data: result.data as Uint8Array,
    signature: await signatureOf(result.signatures, verificationKeys),
  };
}

function canonical(text: string): string {
  return text.replace(/\r?\n/g, CRLF);
}

/** Check a multipart/signed (RFC 3156 §5) entity; returns its content and the result. */
export async function verifySigned(
  entity: MimeEntity,
  verificationKeys: readonly string[],
): Promise<{ entity: MimeEntity; signature: SignatureResult }> {
  const keys = await Promise.all(verificationKeys.map(readPublicKey));
  return verifySignedWith(entity, keys);
}

async function verifySignedWith(
  entity: MimeEntity,
  keys: readonly Key[],
): Promise<{ entity: MimeEntity; signature: SignatureResult }> {
  const [content, signaturePart] = entity.parts;
  if (content === undefined || signaturePart === undefined) {
    throw new PgpError("malformed", "multipart/signed without two parts");
  }
  try {
    const result = await verify({
      message: await createMessage({
        binary: binaryToBytes(canonical(content.raw)),
      }),
      signature: await readSignature({
        armoredSignature: decodedText(signaturePart),
      }),
      verificationKeys: [...keys],
      format: "binary",
    });
    return {
      entity: content,
      signature: await signatureOf(result.signatures, keys),
    };
  } catch (error) {
    throw new PgpError("malformed", (error as Error).message);
  }
}

function isPgpSigned(entity: MimeEntity): boolean {
  return (
    entity.type === "multipart/signed" &&
    (entity.params.protocol ?? "").toLowerCase() === "application/pgp-signature"
  );
}

/**
 * Decrypt a message as Gmail's `raw` gives it (bytes): PGP/MIME, or inline PGP in a text/plain
 * part from older clients. `decryptionKeys`: the account's unlocked private key(s), armored.
 * `verificationKeys`: the keys we have for the sender (from Autocrypt, WKD or keys.openpgp.org);
 * empty gives `unknown-key` for a signed message. A PGP/MIME message signed inside as
 * multipart/signed (RFC 3156 §6.1) is unwrapped and that signature checked. The multipart/encrypted
 * may be the root or a direct child of it (a mailing list's footer wrapper). Address headers in
 * `protectedHeaders` are the inner ones only under a signature that vouches for the inner From
 * (see `DecryptedMessage`). Throws `PgpError`.
 */
export async function decryptMessage(
  raw: Uint8Array,
  options: {
    /** unlocked private keys, armored */
    decryptionKeys: readonly string[];
    /** the sender's public keys, armored */
    verificationKeys: readonly string[];
  },
): Promise<DecryptedMessage> {
  const outer = parseMime(bytesToBinary(raw));
  const decryptionKeys = await Promise.all(
    options.decryptionKeys.map(readUnlockedPrivateKey),
  );
  const verificationKeys = await Promise.all(
    options.verificationKeys.map(readPublicKey),
  );
  const encrypted = pgpMimeEntity(outer);
  if (encrypted !== undefined) {
    const payload = encrypted.parts[1];
    if (payload === undefined) {
      throw new PgpError("malformed", "multipart/encrypted without a payload");
    }
    const opened = await openArmored(
      decodedText(payload),
      decryptionKeys,
      verificationKeys,
    );
    let entity = parseMime(bytesToBinary(opened.data));
    let signature = opened.signature;
    if (isPgpSigned(entity) && signature.status === "unsigned") {
      const checked = await verifySignedWith(entity, verificationKeys);
      entity = checked.entity;
      signature = checked.signature;
    }
    const { headers, mismatched } = trustedHeaders(
      protectedHeadersOf(entity),
      outer,
      signature,
      verificationKeys,
    );
    return {
      kind: "pgp-mime",
      entity,
      protectedHeaders: headers,
      mismatchedHeaders: mismatched,
      signature,
    };
  }
  const part = inlinePart(outer);
  const block =
    part === undefined ? null : INLINE_BLOCK.exec(decodedText(part));
  if (block === null) throw new PgpError("not-encrypted", "not encrypted");
  const opened = await openArmored(block[0], decryptionKeys, verificationKeys);
  let text: string;
  try {
    text = utf8Text(opened.data);
  } catch {
    text = bytesToBinary(opened.data);
  }
  return {
    kind: "inline",
    entity: syntheticText(text),
    protectedHeaders: null,
    mismatchedHeaders: [],
    signature: opened.signature,
  };
}
