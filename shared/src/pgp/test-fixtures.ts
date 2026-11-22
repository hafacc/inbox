// Test-only: key pairs made fresh on every run for the people in pgp-fixtures.json, and the
// messages its decrypt cases describe, built with those keys. Android's :core has the same in
// FixtureMessages.kt.

import { createMessage, encrypt, readKey, readPrivateKey, sign } from "openpgp";
import { bytesToBinary, toBase64, utf8Bytes } from "./bytes";
import { generateKeyPair, type KeyPair } from "./keys";
import { decodedText, parseMime, walk } from "./mime";
import {
  decryptMessage,
  type EncryptionKind,
  encryptionKind,
  encryptMime,
  PgpError,
  type SignatureStatus,
} from "./pgp-mime";

/** Creation time of the test keys. */
export const KEY_DATE = new Date("2026-01-01T00:00:00Z");
/** Time of every test signature. */
export const SIGN_DATE = new Date("2026-01-02T00:00:00Z");

const CRLF = "\r\n";

/** A person in the fixture file's `keys`. */
export type TestPerson = { address: string; name: string };

/** A test person with a key pair made for this run. */
export type TestKey = TestPerson & KeyPair;

/** What to encrypt, to and signed by which test people. */
export type PgpRecipe = {
  /** plaintext; `{{signature}}` stands for `signature`'s armored detached signature */
  data: string;
  format: "binary" | "text";
  /** ids of the people it is encrypted to */
  to: string[];
  /** ids of the people who sign it, in order */
  signers: string[];
  /** a detached signature by `by` over `over`, put into `data` */
  signature?: { by: string; over: string };
};

/** How a decrypt case's message is made. */
export type MessageRecipe = {
  /** the message; `{{pgp}}`, `{{pgp:qp}}` and `{{pgp:base64}}` stand for `pgp` encrypted */
  raw?: string;
  pgp?: PgpRecipe;
  /** instead of `raw`: encryptMime(mime, to's keys, signer's key, boundary) */
  encryptMime?: {
    mime: string;
    to: string[];
    signer: string;
    boundary: string;
  };
};

/** Fresh key pairs for the given people, by id. */
export async function testKeys(
  people: Record<string, TestPerson>,
): Promise<Record<string, TestKey>> {
  const entries = await Promise.all(
    Object.entries(people).map(
      async ([id, person]): Promise<[string, TestKey]> => [
        id,
        {
          ...person,
          ...(await generateKeyPair({ ...person, date: KEY_DATE })),
        },
      ],
    ),
  );
  return Object.fromEntries(entries);
}

/** An armored block as a message holds it: CRLF lines, no line break at the end. */
function armoredLines(armored: string): string {
  return armored.replace(/\r?\n/g, CRLF).trimEnd();
}

/**
 * Base64 (76-character lines) of `Hi`, the fewest `!` that make the byte count 2 mod 3, a blank
 * line and the armored block, so the block's last bytes fall in the padded group.
 */
function base64WithOnePad(armored: string): string {
  for (let marks = 0; ; marks++) {
    const bytes = utf8Bytes(`Hi${"!".repeat(marks)}${CRLF}${CRLF}${armored}`);
    if (bytes.length % 3 === 2) {
      return toBase64(bytes).replace(/(.{76})/g, `$1${CRLF}`);
    }
  }
}

async function encryptRecipe(
  recipe: PgpRecipe,
  keys: Record<string, TestKey>,
): Promise<string> {
  let data = recipe.data;
  if (recipe.signature !== undefined) {
    const signature = await sign({
      message: await createMessage({
        binary: utf8Bytes(recipe.signature.over),
      }),
      signingKeys: await readPrivateKey({
        armoredKey: keys[recipe.signature.by]!.privateKey,
      }),
      detached: true,
      date: SIGN_DATE,
    });
    data = data.replace("{{signature}}", () => armoredLines(signature));
  }
  return encrypt({
    message:
      recipe.format === "text"
        ? await createMessage({ text: data })
        : await createMessage({ binary: utf8Bytes(data), format: "binary" }),
    encryptionKeys: await Promise.all(
      recipe.to.map((id) => readKey({ armoredKey: keys[id]!.publicKey })),
    ),
    signingKeys: await Promise.all(
      recipe.signers.map((id) =>
        readPrivateKey({ armoredKey: keys[id]!.privateKey }),
      ),
    ),
    date: SIGN_DATE,
  });
}

/** The bytes of the message a decrypt case describes, made with this run's keys. */
export async function fixtureMessage(
  recipe: MessageRecipe,
  keys: Record<string, TestKey>,
): Promise<Uint8Array> {
  if (recipe.encryptMime !== undefined) {
    const { mime, to, signer, boundary } = recipe.encryptMime;
    return utf8Bytes(
      await encryptMime(mime, {
        encryptionKeys: to.map((id) => keys[id]!.publicKey),
        signingKey: keys[signer]!.privateKey,
        boundary,
        date: SIGN_DATE,
      }),
    );
  }
  let raw = recipe.raw!;
  if (recipe.pgp !== undefined) {
    const armored = armoredLines(await encryptRecipe(recipe.pgp, keys));
    raw = raw
      .replace("{{pgp}}", () => armored)
      .replace("{{pgp:qp}}", () => armored.replace(/=/g, "=3D"))
      .replace("{{pgp:base64}}", () => base64WithOnePad(armored));
  }
  return utf8Bytes(raw);
}

/** What a decrypt case expects: what decryptMessage found, or the PgpError code it threw. */
export type DecryptOutcome =
  | {
      /** the decrypted message's kind; null when encryptionKind of the outer message differs */
      kind: EncryptionKind | null;
      signature: SignatureStatus;
      protectedHeaders: Record<string, string> | null;
      mismatchedHeaders: string[];
      type: string;
      text: string | null;
    }
  | { error: string };

/** Decrypt a message as a decrypt case does, in the shape of its `expect`. */
export async function decryptOutcome(
  message: Uint8Array,
  decryptionKey: string,
  verificationKeys: string[],
): Promise<DecryptOutcome> {
  try {
    const result = await decryptMessage(message, {
      decryptionKeys: [decryptionKey],
      verificationKeys,
    });
    const text = walk(result.entity).find((part) => part.type === "text/plain");
    const outerKind = encryptionKind(parseMime(bytesToBinary(message)));
    return {
      kind: outerKind === result.kind ? result.kind : null,
      signature: result.signature.status,
      protectedHeaders: result.protectedHeaders,
      mismatchedHeaders: result.mismatchedHeaders,
      type: result.entity.type,
      text: text === undefined ? null : decodedText(text),
    };
  } catch (error) {
    if (!(error instanceof PgpError)) throw error;
    return { error: error.code };
  }
}
