// Writes shared/testdata/pgp-fixtures.json (`bun src/pgp/make-fixtures.ts` in shared/). The file
// holds no private key: decrypt cases describe their messages, which the tests build with keys
// made on each run (test-fixtures.ts), and the expectations come from building them here the
// same way. It keeps the file's Autocrypt public key and recovery seal so they don't churn;
// `--new-keys` makes new ones.

import { armor, enums, unarmor } from "openpgp";
import { buildMime, type ComposeInput } from "../compose";
import {
  autocryptFor,
  autocryptHeaderValue,
  parseAutocrypt,
} from "./autocrypt";
import { fromHex, toBase64, toHex, utf8Bytes } from "./bytes";
import { generateKeyPair } from "./keys";
import { encryptionDecision } from "./lookup";
import { decodedText, decodeHeaderValue, walk } from "./mime";
import {
  assemblePgpMime,
  decryptMessage,
  PgpError,
  protectedParts,
} from "./pgp-mime";
import {
  parsePhrase,
  RECOVERY_KDF,
  recoverySecret,
  wordsFromBytes,
} from "./recovery";
import { seal } from "./seal";
import {
  fixtureMessage,
  KEY_DATE,
  type MessageRecipe,
  type PgpRecipe,
  type TestPerson,
  testKeys,
} from "./test-fixtures";
import { wkdHash, wkdUrls, zbase32 } from "./wkd";

const CRLF = "\r\n";

const FIXTURE_FILE = new URL(
  "../../testdata/pgp-fixtures.json",
  import.meta.url,
);
const previous: {
  autocrypt: { build: { publicKey?: string }[] };
  recovery: { plaintext?: string; sealed: { sealedPrivateKey: string } }[];
} | null =
  (await Bun.file(FIXTURE_FILE).exists()) &&
  !process.argv.includes("--new-keys")
    ? await Bun.file(FIXTURE_FILE).json()
    : null;

const people: Record<string, TestPerson> = {
  alice: { address: "test@hafa.cc", name: "Alice Test" },
  bob: { address: "bob@example.com", name: "Bob Test" },
  carol: { address: "carol@example.org", name: "" },
};
const keys = await testKeys(people);

// Autocrypt pins its key data, so it uses one public key whose private half is thrown away.
const autocryptKey =
  previous?.autocrypt.build[0]?.publicKey ??
  (
    await generateKeyPair({
      address: people.alice!.address,
      name: people.alice!.name,
      date: KEY_DATE,
    })
  ).publicKey;

/** An armored public key with a trust packet (tag 12, as a keyring holds) after its primary key. */
async function withTrustPacket(publicKey: string): Promise<string> {
  const bytes = (await unarmor(publicKey)).data as Uint8Array;
  const primaryEnd = 2 + bytes[1]!;
  const trust = new Uint8Array([0xcc, 0x02, 0x00, 0x00]);
  return armor(
    enums.armor.publicKey,
    new Uint8Array([
      ...bytes.slice(0, primaryEnd),
      ...trust,
      ...bytes.slice(primaryEnd),
    ]),
  );
}

const composeInput: ComposeInput = {
  from: { name: "Alice Test", email: "test@hafa.cc" },
  to: [{ name: "Bob Test", email: "bob@example.com" }],
  cc: [],
  bcc: [{ name: "", email: "hidden@example.net" }],
  subject: "Plans for Thursday — café?",
  markdown: "Lunch at *noon*?",
  signature: null,
  inReplyTo: "<prev@example.com>",
  references: ["<prev@example.com>"],
  inlineImages: [],
  attachments: [],
  extraHeaders: [
    ["Autocrypt", await autocryptHeaderValue("test@hafa.cc", autocryptKey)],
  ],
  boundary: "fixture1",
};
const mime = buildMime(composeInput);
const parts = protectedParts(mime);
const placeholder =
  "-----BEGIN PGP MESSAGE-----\n\nAAAA\n=AAAA\n-----END PGP MESSAGE-----\n";

const replyBody = [
  'Content-Type: multipart/alternative; boundary="alt"; protected-headers="v1"',
  "Subject: =?UTF-8?Q?Re:_Plans_for_Thursday_=E2=80=94_caf=C3=A9=3F?=",
  "From: Bob Test <bob@example.com>",
  "To: Alice Test <test@hafa.cc>",
  "",
  "--alt",
  "Content-Type: text/plain; charset=UTF-8",
  "Content-Transfer-Encoding: quoted-printable",
  "",
  "Noon works. =E2=98=95",
  "--alt",
  "Content-Type: text/html; charset=UTF-8",
  "",
  "<p>Noon works. &#9749;</p>",
  "--alt--",
  "",
].join(CRLF);

function binary(
  data: string,
  to: string[],
  signers: string[],
  signature?: PgpRecipe["signature"],
): PgpRecipe {
  return {
    data,
    format: "binary",
    to,
    signers,
    ...(signature === undefined ? {} : { signature }),
  };
}

// A mailing list's footer: the multipart/encrypted wrapped in a multipart/mixed after the fact.
function withFooter(pgpMime: string): string {
  const split = pgpMime.indexOf(`${CRLF}${CRLF}`);
  const headers = pgpMime
    .slice(0, split)
    .split(CRLF)
    .filter((line) => !/^(content-type|\s)/i.test(line));
  const contentType = pgpMime
    .slice(0, split)
    .split(CRLF)
    .filter(
      (line, index, lines) =>
        /^content-type/i.test(line) ||
        (/^\s/.test(line) && /^content-type/i.test(lines[index - 1] ?? "")),
    );
  return [
    ...headers,
    'Content-Type: multipart/mixed; boundary="list"',
    "",
    "--list",
    ...contentType,
    "",
    pgpMime.slice(split + 4).replace(/\r\n$/, ""),
    "--list",
    "Content-Type: text/plain; charset=UTF-8",
    "",
    "_______________________________________________",
    "Team mailing list",
    "--list--",
    "",
  ].join(CRLF);
}

/** A PGP/MIME message to Alice whose encrypted part is `{{pgp}}`. */
function outer(subject: string, from = "Bob Test <bob@example.com>"): string {
  return assemblePgpMime(
    [
      `From: ${from}`,
      "To: Alice Test <test@hafa.cc>",
      `Subject: ${subject}`,
      "MIME-Version: 1.0",
    ],
    "{{pgp}}",
    "fx",
  );
}

const signedContent = [
  "Content-Type: text/plain; charset=UTF-8",
  "",
  "Signed then encrypted.",
  "",
].join(CRLF);
// RFC 3156 signs the part without the line break that ends it (it belongs to the delimiter).
const signedByBob = { by: "bob", over: signedContent.replace(/\r\n$/, "") };
function signedEntity(content: string): string {
  return [
    'Content-Type: multipart/signed; micalg=pgp-sha512; protocol="application/pgp-signature"; boundary="sig"',
    "",
    "--sig",
    content.replace(/\r\n$/, ""),
    "--sig",
    'Content-Type: application/pgp-signature; name="signature.asc"',
    "",
    "{{signature}}",
    "--sig--",
    "",
  ].join(CRLF);
}

const inlinePgp: PgpRecipe = {
  data: "Inline secret, ünïcode.",
  format: "text",
  to: ["alice"],
  signers: ["bob"],
};
const inlineRaw = [
  "From: bob@example.com",
  "To: test@hafa.cc",
  "Subject: Old school",
  "Content-Type: text/plain; charset=us-ascii",
  "",
  "Hi,",
  "",
  "{{pgp}}",
  "",
].join(CRLF);

function base64Entity(encoded: string): string {
  return [
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: base64",
    "",
    encoded,
    "",
  ].join(CRLF);
}
function base64Of(text: string): string {
  return toBase64(utf8Bytes(text)).replace(/(.{76})/g, `$1${CRLF}`);
}
// Text parts in base64 whose encoding ends in "==", "=" and no padding (byte counts 1, 2, 0 mod 3).
const base64Texts = [
  "Two padding: ünïcode",
  "One padding: café!!",
  "No padding: café!",
];

type DecryptCase = MessageRecipe & {
  name: string;
  decryptionKey: string;
  verificationKeys: string[];
};

const decryptCases: DecryptCase[] = [
  {
    name: "PGP/MIME signed and encrypted, sender key known",
    raw: outer("..."),
    pgp: binary(replyBody, ["alice", "bob"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "same message, sender key unknown",
    raw: outer("..."),
    pgp: binary(replyBody, ["alice", "bob"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: [],
  },
  {
    name: "same message, another key for the sender",
    raw: outer("..."),
    pgp: binary(replyBody, ["alice", "bob"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["carol"],
  },
  {
    name: "PGP/MIME our own sent copy, unsigned, no protected headers",
    raw: outer("Note", "Alice Test <test@hafa.cc>"),
    pgp: binary(
      ["Content-Type: text/plain; charset=UTF-8", "", "Just text.", ""].join(
        CRLF,
      ),
      ["alice"],
      [],
    ),
    decryptionKey: "alice",
    verificationKeys: ["alice"],
  },
  {
    name: "built by encryptMime",
    encryptMime: {
      mime,
      to: ["bob", "alice"],
      signer: "alice",
      boundary: "fx2",
    },
    decryptionKey: "bob",
    verificationKeys: ["alice"],
  },
  {
    name: "signed inside as multipart/signed (RFC 3156 6.1)",
    raw: outer("Signed inside"),
    pgp: binary(signedEntity(signedContent), ["alice"], [], signedByBob),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "multipart/signed inside, content changed after signing",
    raw: outer("Tampered"),
    pgp: binary(
      signedEntity(signedContent.replace("Signed", "Changed")),
      ["alice"],
      [],
      signedByBob,
    ),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "inline PGP",
    raw: inlineRaw,
    pgp: inlinePgp,
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "inline PGP inside quoted-printable multipart/alternative",
    raw: [
      "From: bob@example.com",
      'Content-Type: multipart/alternative; boundary="b1"',
      "",
      "--b1",
      "Content-Type: text/plain; charset=utf-8",
      "Content-Transfer-Encoding: quoted-printable",
      "",
      "{{pgp:qp}}",
      "--b1",
      "Content-Type: text/html; charset=utf-8",
      "",
      "<pre>encrypted</pre>",
      "--b1--",
      "",
    ].join(CRLF),
    pgp: inlinePgp,
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  ...base64Texts.map((text) => ({
    name: `PGP/MIME, base64 text ${["without padding", "ending in ==", "ending in ="][utf8Bytes(text).length % 3]}`,
    raw: outer("..."),
    pgp: binary(base64Entity(base64Of(text)), ["alice", "bob"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  })),
  {
    name: "inline PGP inside a base64 text part",
    raw: [
      "From: bob@example.com",
      "To: test@hafa.cc",
      "Subject: Old school",
      "MIME-Version: 1.0",
      base64Entity("{{pgp:base64}}"),
    ].join(CRLF),
    pgp: inlinePgp,
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "unsigned: inner From is not trusted over the outer one",
    raw: outer("...", "Mallory <mallory@example.net>"),
    pgp: binary(
      replyBody.replace(
        "Subject: =?UTF-8?Q?Re:_Plans_for_Thursday_=E2=80=94_caf=C3=A9=3F?=",
        "Subject: =?UTF-8?B?Q?= Re: plans",
      ),
      ["alice"],
      [],
    ),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "valid signature by a key for another address than the inner From",
    raw: outer("...", "carol@example.org"),
    pgp: binary(replyBody, ["alice"], ["carol"]),
    decryptionKey: "alice",
    verificationKeys: ["carol"],
  },
  {
    name: "signed by the inner From's key: inner From wins over the outer one",
    raw: outer("...", "Team list <team@lists.example.com>"),
    pgp: binary(replyBody, ["alice"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "two signatures: the one by a known key decides",
    raw: outer("..."),
    pgp: binary(replyBody, ["alice"], ["carol", "bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "PGP/MIME wrapped by a mailing list footer",
    raw: withFooter(outer("...")),
    pgp: binary(replyBody, ["alice"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "encrypted to another key",
    raw: outer("Not yours"),
    pgp: binary(replyBody, ["carol"], ["bob"]),
    decryptionKey: "alice",
    verificationKeys: ["bob"],
  },
  {
    name: "not encrypted",
    raw: ["From: bob@example.com", "Subject: hi", "", "Hello", ""].join(CRLF),
    decryptionKey: "alice",
    verificationKeys: [],
  },
];

const wkdAddresses = [
  "Joe.Doe@Example.ORG",
  "test@hafa.cc",
  "me@proton.example.net",
  "test+b@hafa.cc",
];

const sealSecret = fromHex(
  "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
);
const sealIv = fromHex("0f0e0d0c0b0a090807060504");
const longPlaintext = [
  "A longer plaintext over several lines,",
  "with ünïcode, sealed under a zero IV.",
  "0123456789".repeat(20),
  "",
].join("\n");
const phrase = "abacus zoom t-shirt yo-yo crumpet drop-down eel oxygen";
const recoverySalt = fromHex("00112233445566778899aabbccddeeff");
const secret = await recoverySecret(phrase, recoverySalt, RECOVERY_KDF);
const recoveryPlaintext = "stands in for an armored private key";
const keptRecovery = previous?.recovery[0];

const fixtures = {
  _doc: {
    about:
      "OpenPGP behavior every client must match: web (shared/src/pgp, TypeScript, openpgp.js) and Android :core (Kotlin). Contract: docs/pgp-contract.md. Strings use CRLF where messages do. Made by shared/src/pgp/make-fixtures.ts.",
    keys: "Test people only: each test run makes its own key pairs for them (Curve25519, EdDSA legacy primary + ECDH Curve25519 subkey, v4, created 2026-01-01, unprotected), so this file holds no private key. Web: shared/src/pgp/test-fixtures.ts; Android: FixtureMessages.kt.",
    seal: "seal(secret, plaintext, iv) == sealed and unseal(secret, sealed) == plaintext. Key = HKDF-SHA256(ikm=secret, salt=empty, info=UTF-8 'inbox.hafa.cc/pgp-seal/v1', 32 bytes); AES-256-GCM, no AAD; sealed = base64(0x01 || iv(12) || ciphertext || tag(16)). unsealFailures must throw.",
    phraseWords:
      "wordsFromBytes(bytes, 8): big-endian 16-bit numbers, skip ≥ 62208, word = list[n % 7776] (shared/wordlists/eff_large_wordlist.txt, line n+1).",
    parsePhrase:
      "parsePhrase(input): lower-case, split on whitespace (not hyphens), unknown = indexes not in the list, normalized = words joined by one space when 8 known words, else null.",
    recovery:
      "recoverySecret(phrase, salt, kdf) == secretHex: Argon2id v0x13, password = UTF-8 of the normalized phrase, 32-byte output. unsealWithPhrase(phrase, sealed) == plaintext; also with phraseTyped.",
    wkd: "wkdHash(local) and wkdUrls(address): z-base-32 of SHA-1 of the local part with A-Z lower-cased; the l= parameter is the local part as given, percent-encoded.",
    zbase32: "zbase32(bytes).",
    autocrypt:
      "build: autocryptHeaderValue(address, publicKey) == value (key data in 64-character pieces joined by spaces); publicKey is a test public key whose private key was thrown away. The key data is the key re-serialized, so packets a keyring adds (a trust packet) are left out. parse: parseAutocrypt(value) → null, or addr, preferEncrypt and keydata (base64 of the bytes). for: autocryptFor(values, from) → addr or null.",
    decision:
      "encryptionDecision(from, recipients, keys) where keys maps each address in withKeys to a key and the rest to null.",
    protect:
      "protectedParts(mime) == { outerHeaders, inner }; assemblePgpMime(outerHeaders, placeholder, boundary) == assembled. mime is buildMime(composeInput) from compose-fixtures' rules (boundary token fixture1). Also: decrypting encryptMime(mime, …) gives back inner byte for byte.",
    decrypt:
      "Build the message with this run's keys, signing at 2026-01-02: encrypt pgp.data (binary, or text when format is text) to pgp.to's keys, signed by each of pgp.signers in order; for {{signature}} in data, first put the armored detached binary signature by pgp.signature.by over the UTF-8 of pgp.signature.over. Armored blocks go in with CRLF line breaks and none at the end. In raw, {{pgp}} is that armored message, {{pgp:qp}} the same with each = written =3D, and {{pgp:base64}} the base64 (76-character lines, CRLF) of 'Hi', the fewest '!' that make the UTF-8 byte count 2 mod 3, CRLF CRLF and the armored message. A case with encryptMime instead of raw is encryptMime(mime, to's public keys, signer's private key, boundary). The message is the UTF-8 of the result. Then decryptMessage(message, { decryptionKeys: [keys[k].privateKey], verificationKeys: [keys[k].publicKey…] }). expect: kind, signature status, protectedHeaders (null or the map), mismatchedHeaders, type of the decrypted entity, and text = the decoded first text/plain part. Or error = PgpError code; otherwise also encryptionKind(parseMime(message)) == kind. PGP/MIME is a multipart/encrypted (protocol application/pgp-encrypted) at the root or as a direct child of the root. Signature: the first signature whose key id is one of the verification keys' (primary or subkey) decides valid/invalid; unknown-key (with the first signature's key id) when none is. protectedHeaders: from, reply-to, to and cc come from inside only when the signature is valid and its key has a user id whose address equals the inner From's single address (case-insensitive); otherwise each present inside is replaced by the outer header's decoded value (removed when the outer message has none), and listed in mismatchedHeaders (in the order from, reply-to, to, cc) when the two differ after collapsing whitespace runs to one space and trimming.",
    headerDecode:
      "decodeHeaderValue(value) == expect: RFC 2047 encoded words decoded (whitespace between two adjacent encoded words dropped); a word that fails to decode (bad base64) stays exactly as written.",
  },
  keys: people,
  seal: [
    {
      secretHex: toHex(sealSecret),
      ivHex: toHex(sealIv),
      plaintext: "hello, sealed world",
      sealed: await seal(sealSecret, "hello, sealed world", sealIv),
    },
    {
      secretHex: toHex(sealSecret),
      ivHex: "000000000000000000000000",
      plaintext: longPlaintext,
      sealed: await seal(sealSecret, longPlaintext, new Uint8Array(12)),
    },
  ],
  unsealFailures: [
    {
      name: "wrong secret",
      secretHex: "ff".repeat(32),
      sealed: await seal(sealSecret, "x", sealIv),
    },
    {
      name: "unknown version",
      secretHex: toHex(sealSecret),
      sealed: toBase64(new Uint8Array([2, ...new Uint8Array(40)])),
    },
    { name: "too short", secretHex: toHex(sealSecret), sealed: "AQID" },
  ],
  phraseWords: [
    {
      bytesHex: "0000000100021e5f1e601e61f2ffffff",
      words: wordsFromBytes(fromHex("0000000100021e5f1e601e61f2ffffff"), 6),
    },
    {
      bytesHex: "f300f2ffaaaa5555123498760001f2fe00ff",
      words: wordsFromBytes(fromHex("f300f2ffaaaa5555123498760001f2fe00ff"), 8),
    },
  ],
  parsePhrase: [
    { input: phrase },
    { input: "  ABACUS\tzoom\n t-shirt yo-yo crumpet drop-down eel oxygen " },
    { input: "abacus zoom tshirt yo-yo crumpet drop-down eel oxygen" },
    { input: "abacus zoom t-shirt yo-yo crumpet drop-down eel" },
    { input: "" },
  ],
  recovery: [
    {
      phrase,
      phraseTyped: "Abacus  Zoom T-Shirt yo-yo crumpet drop-down eel OXYGEN",
      saltHex: toHex(recoverySalt),
      kdf: RECOVERY_KDF,
      secretHex: toHex(secret),
      plaintext: recoveryPlaintext,
      sealed: {
        salt: toBase64(recoverySalt),
        sealedPrivateKey:
          keptRecovery?.plaintext === recoveryPlaintext
            ? keptRecovery.sealed.sealedPrivateKey
            : await seal(secret, recoveryPlaintext),
        kdf: RECOVERY_KDF,
      },
    },
  ],
  wkd: await Promise.all(
    wkdAddresses.map(async (address) => ({
      address,
      hash: await wkdHash(address.slice(0, address.lastIndexOf("@"))),
      ...(await wkdUrls(address)),
    })),
  ),
  zbase32: [
    { hex: "", encoded: zbase32(new Uint8Array(0)) },
    { hex: "00", encoded: zbase32(fromHex("00")) },
    { hex: "ff", encoded: zbase32(fromHex("ff")) },
    { hex: "f0bfc7", encoded: zbase32(fromHex("f0bfc7")) },
    { hex: "d4a9a8d51d", encoded: zbase32(fromHex("d4a9a8d51d")) },
  ],
  autocrypt: {
    build: [
      {
        address: "Test@Hafa.cc",
        publicKey: autocryptKey,
        value: await autocryptHeaderValue("Test@Hafa.cc", autocryptKey),
      },
      {
        address: "test@hafa.cc",
        publicKey: await withTrustPacket(autocryptKey),
        value: await autocryptHeaderValue("test@hafa.cc", autocryptKey),
      },
    ],
    parse: [] as unknown[],
    for: [] as unknown[],
  },
  decision: [
    {
      from: "test@hafa.cc",
      recipients: ["Bob@Example.com"],
      withKeys: ["bob@example.com"],
    },
    {
      from: "Alice <x>",
      recipients: ["bob@example.com"],
      withKeys: ["bob@example.com"],
    },
    {
      from: "alice@example.com",
      recipients: ["bob@example.com"],
      withKeys: ["bob@example.com"],
    },
    {
      from: "test@HAFA.CC",
      recipients: ["bob@example.com", "carol@example.org", "bob@example.com"],
      withKeys: ["bob@example.com"],
    },
    { from: "test@hafa.cc", recipients: [], withKeys: [] },
    {
      from: "test@sub.hafa.cc",
      recipients: ["bob@example.com"],
      withKeys: ["bob@example.com"],
    },
  ],
  protect: [
    {
      composeInput,
      mime,
      ...parts,
      boundary: "fx",
      placeholder,
      assembled: assemblePgpMime(parts.outerHeaders, placeholder, "fx"),
    },
  ],
};

const keydata = (
  await autocryptHeaderValue("test@hafa.cc", autocryptKey)
).split("keydata=")[1]!;
const autocryptValues = [
  `addr=test@hafa.cc; prefer-encrypt=mutual; keydata=${keydata}`,
  `addr=Bob@Example.com; keydata=${keydata}`,
  `addr=bob@example.com; _extra=1; keydata=${keydata}`,
  `addr=bob@example.com; unknown=1; keydata=${keydata}`,
  "addr=bob@example.com",
  "keydata=AAAA",
  "addr=bob@example.com; keydata=***",
  "addr=bob@example.com; addr=eve@example.com; keydata=AAAA",
];
fixtures.autocrypt.parse = autocryptValues.map((value) => {
  const parsed = parseAutocrypt(value);
  return {
    value,
    expect:
      parsed === null
        ? null
        : {
            addr: parsed.addr,
            preferEncrypt: parsed.preferEncrypt,
            keydata: toBase64(parsed.keydata),
          },
  };
});
fixtures.autocrypt.for = [
  { values: [autocryptValues[0]!], from: "Test@hafa.cc" },
  { values: [autocryptValues[0]!], from: "bob@example.com" },
  {
    values: [autocryptValues[1]!, autocryptValues[2]!],
    from: "bob@example.com",
  },
  {
    values: [autocryptValues[1]!, autocryptValues[3]!],
    from: "bob@example.com",
  },
].map((entry) => ({
  ...entry,
  expect: autocryptFor(entry.values, entry.from)?.addr ?? null,
}));

const output = {
  ...fixtures,
  parsePhrase: fixtures.parsePhrase.map((entry) => ({
    ...entry,
    ...parsePhrase(entry.input),
  })),
  decision: fixtures.decision.map((entry) => ({
    ...entry,
    expect: encryptionDecision(
      entry.from,
      entry.recipients,
      Object.fromEntries(
        entry.recipients.map((address) => {
          const lower = address.toLowerCase();
          return [lower, entry.withKeys.includes(lower) ? "key" : null];
        }),
      ),
    ),
  })),
  decrypt: await Promise.all(
    decryptCases.map(async (entry) => {
      try {
        const result = await decryptMessage(await fixtureMessage(entry, keys), {
          decryptionKeys: [keys[entry.decryptionKey]!.privateKey],
          verificationKeys: entry.verificationKeys.map(
            (id) => keys[id]!.publicKey,
          ),
        });
        const text = walk(result.entity).find(
          (part) => part.type === "text/plain",
        );
        return {
          ...entry,
          expect: {
            kind: result.kind,
            signature: result.signature.status,
            protectedHeaders: result.protectedHeaders,
            mismatchedHeaders: result.mismatchedHeaders,
            type: result.entity.type,
            text: text === undefined ? null : decodedText(text),
          },
        };
      } catch (error) {
        if (!(error instanceof PgpError)) throw error;
        return { ...entry, expect: { error: error.code } };
      }
    }),
  ),
};

const headerDecode = [
  "=?UTF-8?B?Q2Fmw6k=?= au lait",
  "=?ISO-8859-1?Q?caf=E9_cr=E8me?=",
  "=?UTF-8?B?Q?=",
  "Hi =?UTF-8?B?Q?= =?UTF-8?B?w6k=?= there",
  "=?UTF-8?B?***?= plain",
].map((value) => ({ value, expect: decodeHeaderValue(value) }));

await Bun.write(
  FIXTURE_FILE,
  `${JSON.stringify({ ...output, headerDecode }, null, 2)}\n`,
);
