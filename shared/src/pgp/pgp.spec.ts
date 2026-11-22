import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { readKey } from "openpgp";
import { buildMime, type ComposeInput } from "../compose";
import {
  assemblePgpMime,
  autocryptFor,
  autocryptHeaderValue,
  decodeHeaderValue,
  decryptMessage,
  EFF_LARGE_WORDLIST,
  encryptionDecision,
  encryptionKind,
  encryptMime,
  fromHex,
  generateKeyPair,
  generatePhrase,
  keyFromAnswer,
  parseAutocrypt,
  parseMime,
  parsePhrase,
  protectedParts,
  type RecoveryKdf,
  type RecoverySealed,
  recoverySecret,
  SealError,
  seal,
  sealWithPhrase,
  toBase64,
  toHex,
  unseal,
  unsealWithPhrase,
  wkdHash,
  wkdUrls,
  wordsFromBytes,
  zbase32,
} from "./index";
import {
  type DecryptOutcome,
  decryptOutcome,
  fixtureMessage,
  type MessageRecipe,
  type TestPerson,
  testKeys,
} from "./test-fixtures";

type Fixtures = {
  keys: Record<string, TestPerson>;
  seal: {
    secretHex: string;
    ivHex: string;
    plaintext: string;
    sealed: string;
  }[];
  unsealFailures: { name: string; secretHex: string; sealed: string }[];
  phraseWords: { bytesHex: string; words: string[] }[];
  parsePhrase: {
    input: string;
    words: string[];
    unknown: number[];
    normalized: string | null;
  }[];
  recovery: {
    phrase: string;
    phraseTyped: string;
    saltHex: string;
    kdf: RecoveryKdf;
    secretHex: string;
    plaintext: string;
    sealed: RecoverySealed;
  }[];
  wkd: { address: string; hash: string; advanced: string; direct: string }[];
  zbase32: { hex: string; encoded: string }[];
  autocrypt: {
    build: { address: string; publicKey: string; value: string }[];
    parse: {
      value: string;
      expect: {
        addr: string;
        preferEncrypt: "mutual" | "nopreference";
        keydata: string;
      } | null;
    }[];
    for: { values: string[]; from: string; expect: string | null }[];
  };
  decision: {
    from: string;
    recipients: string[];
    withKeys: string[];
    expect: unknown;
  }[];
  protect: {
    composeInput: ComposeInput;
    mime: string;
    outerHeaders: string[];
    inner: string;
    boundary: string;
    placeholder: string;
    assembled: string;
  }[];
  decrypt: (MessageRecipe & {
    name: string;
    decryptionKey: string;
    verificationKeys: string[];
    expect: DecryptOutcome;
  })[];
  headerDecode: { value: string; expect: string }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/pgp-fixtures.json", import.meta.url),
    "utf8",
  ),
);
const keys = await testKeys(fixtures.keys);
const alice = keys.alice!;
const bob = keys.bob!;

test("the wordlist matches the bundled file", () => {
  const lines = readFileSync(
    new URL("../../wordlists/eff_large_wordlist.txt", import.meta.url),
    "utf8",
  )
    .trim()
    .split("\n");
  expect(EFF_LARGE_WORDLIST.length).toBe(7776);
  expect(lines.map((line) => line.split("\t")[1])).toEqual([
    ...EFF_LARGE_WORDLIST,
  ]);
});

describe("seal", () => {
  for (const entry of fixtures.seal) {
    test(`seals and unseals ${entry.plaintext.slice(0, 20)}`, async () => {
      const secret = fromHex(entry.secretHex);
      expect(await seal(secret, entry.plaintext, fromHex(entry.ivHex))).toBe(
        entry.sealed,
      );
      expect(await unseal(secret, entry.sealed)).toBe(entry.plaintext);
    });
  }
  for (const entry of fixtures.unsealFailures) {
    test(`refuses: ${entry.name}`, async () => {
      await expect(
        unseal(fromHex(entry.secretHex), entry.sealed),
      ).rejects.toThrow(SealError);
    });
  }
  test("uses a fresh nonce each time", async () => {
    const secret = new Uint8Array(32);
    expect(await seal(secret, "x")).not.toBe(await seal(secret, "x"));
  });
  test("refuses a secret of the wrong length", async () => {
    await expect(seal(new Uint8Array(16), "x")).rejects.toThrow(SealError);
  });
});

describe("recovery phrase", () => {
  for (const entry of fixtures.phraseWords) {
    test(`words from ${entry.bytesHex}`, () => {
      expect(
        wordsFromBytes(fromHex(entry.bytesHex), entry.words.length),
      ).toEqual(entry.words);
    });
  }
  for (const entry of fixtures.parsePhrase) {
    test(`parses ${JSON.stringify(entry.input)}`, () => {
      expect(parsePhrase(entry.input)).toEqual({
        words: entry.words,
        unknown: entry.unknown,
        normalized: entry.normalized,
      });
    });
  }
  test("generates 8 known words", () => {
    const phrase = generatePhrase();
    expect(parsePhrase(phrase).normalized).toBe(phrase);
  });
  for (const entry of fixtures.recovery) {
    test("derives the secret and opens the sealed key", async () => {
      expect(
        toHex(
          await recoverySecret(entry.phrase, fromHex(entry.saltHex), entry.kdf),
        ),
      ).toBe(entry.secretHex);
      expect(await unsealWithPhrase(entry.phraseTyped, entry.sealed)).toBe(
        entry.plaintext,
      );
    });
  }
  test("round-trips with cheap settings and refuses a wrong phrase", async () => {
    const kdf: RecoveryKdf = {
      name: "argon2id",
      memoryKiB: 1024,
      iterations: 1,
      parallelism: 1,
    };
    const phrase = generatePhrase();
    const sealed = await sealWithPhrase(phrase, "secret key", undefined, kdf);
    expect(sealed.kdf).toEqual(kdf);
    expect(await unsealWithPhrase(phrase, sealed)).toBe("secret key");
    await expect(unsealWithPhrase(generatePhrase(), sealed)).rejects.toThrow(
      SealError,
    );
    await expect(sealWithPhrase("not a phrase", "x")).rejects.toThrow();
  });
});

describe("lookup", () => {
  for (const entry of fixtures.wkd) {
    test(`WKD for ${entry.address}`, async () => {
      expect(await wkdHash(entry.address.split("@")[0]!)).toBe(entry.hash);
      expect(await wkdUrls(entry.address)).toEqual({
        advanced: entry.advanced,
        direct: entry.direct,
      });
    });
  }
  for (const entry of fixtures.zbase32) {
    test(`z-base-32 of ${entry.hex || "nothing"}`, () => {
      expect(zbase32(fromHex(entry.hex))).toBe(entry.encoded);
    });
  }
  for (const entry of fixtures.decision) {
    test(`decides for ${entry.from} → ${entry.recipients.join(", ")}`, () => {
      const known = Object.fromEntries(
        entry.recipients.map((address) => {
          const lower = address.toLowerCase();
          return [lower, entry.withKeys.includes(lower) ? "key" : null];
        }),
      );
      expect(encryptionDecision(entry.from, entry.recipients, known)).toEqual(
        entry.expect as never,
      );
    });
  }
  test("picks a usable public key from binary or armored answers", async () => {
    const binary = (await readKey({ armoredKey: bob.publicKey })).write();
    expect(await keyFromAnswer(binary, "BOB@example.com")).toBe(
      (await readKey({ armoredKey: bob.publicKey })).armor(),
    );
    expect(
      await keyFromAnswer(bob.publicKey, "bob@example.com"),
    ).not.toBeNull();
    expect(await keyFromAnswer(bob.publicKey, "eve@example.com")).toBeNull();
    expect(await keyFromAnswer(bob.privateKey, "bob@example.com")).toBeNull();
    expect(
      await keyFromAnswer(new Uint8Array([1, 2, 3]), "bob@example.com"),
    ).toBeNull();
  });
});

describe("Autocrypt", () => {
  for (const entry of fixtures.autocrypt.build) {
    test(`builds for ${entry.address}`, async () => {
      expect(await autocryptHeaderValue(entry.address, entry.publicKey)).toBe(
        entry.value,
      );
    });
  }
  for (const entry of fixtures.autocrypt.parse) {
    test(`parses ${entry.value.slice(0, 40)}`, () => {
      const parsed = parseAutocrypt(entry.value);
      expect(
        parsed === null
          ? null
          : {
              addr: parsed.addr,
              preferEncrypt: parsed.preferEncrypt,
              keydata: toBase64(parsed.keydata),
            },
      ).toEqual(entry.expect);
    });
  }
  for (const [index, entry] of fixtures.autocrypt.for.entries()) {
    test(`picks the header for From (${index})`, () => {
      expect(autocryptFor(entry.values, entry.from)?.addr ?? null).toBe(
        entry.expect,
      );
    });
  }
  test("its key data is a key for the address", async () => {
    const parsed = parseAutocrypt(
      await autocryptHeaderValue(alice.address, alice.publicKey),
    );
    expect(await keyFromAnswer(parsed!.keydata, alice.address)).not.toBeNull();
  });
});

describe("PGP/MIME", () => {
  for (const entry of fixtures.protect) {
    test("splits protected headers and assembles", () => {
      expect(buildMime(entry.composeInput)).toBe(entry.mime);
      expect(protectedParts(entry.mime)).toEqual({
        outerHeaders: entry.outerHeaders,
        inner: entry.inner,
      });
      expect(
        assemblePgpMime(entry.outerHeaders, entry.placeholder, entry.boundary),
      ).toBe(entry.assembled);
    });
    test("encrypts so the recipient gets the inner part back", async () => {
      const encrypted = await encryptMime(entry.mime, {
        encryptionKeys: [bob.publicKey, alice.publicKey],
        signingKey: alice.privateKey,
        boundary: entry.boundary,
      });
      const parsed = parseMime(encrypted);
      expect(parsed.type).toBe("multipart/encrypted");
      expect(parsed.params.protocol).toBe("application/pgp-encrypted");
      expect(encryptionKind(parsed)).toBe("pgp-mime");
      expect(encrypted).not.toContain("noon");
      expect(encrypted).toContain("Subject: ...\r\n");
      for (const reader of [bob, alice]) {
        const opened = await decryptMessage(
          new TextEncoder().encode(encrypted),
          {
            decryptionKeys: [reader.privateKey],
            verificationKeys: [alice.publicKey],
          },
        );
        expect(opened.entity.raw).toBe(entry.inner);
        expect(opened.signature.status).toBe("valid");
      }
    });
  }

  for (const entry of fixtures.decrypt) {
    test(`decrypts: ${entry.name}`, async () => {
      const outcome = await decryptOutcome(
        await fixtureMessage(entry, keys),
        keys[entry.decryptionKey]!.privateKey,
        entry.verificationKeys.map((id) => keys[id]!.publicKey),
      );
      expect(outcome).toEqual(entry.expect);
    });
  }
});

for (const entry of fixtures.headerDecode) {
  test(`decodeHeaderValue: ${entry.value}`, () => {
    expect(decodeHeaderValue(entry.value)).toBe(entry.expect);
  });
}

test("generated keys carry the address and can be used at once", async () => {
  const pair = await generateKeyPair({ address: "dev@hafa.cc", name: "" });
  expect(pair.publicKey).toContain("BEGIN PGP PUBLIC KEY BLOCK");
  expect(pair.privateKey).toContain("BEGIN PGP PRIVATE KEY BLOCK");
  const key = await readKey({ armoredKey: pair.publicKey });
  expect(key.getUserIDs()).toEqual(["<dev@hafa.cc>"]);
  expect(key.getFingerprint()).toBe(pair.fingerprint);
  expect(key.getAlgorithmInfo().algorithm).toBe("eddsaLegacy");
  expect(await keyFromAnswer(pair.publicKey, "dev@hafa.cc")).not.toBeNull();
});
