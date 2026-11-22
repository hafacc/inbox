import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  decryptMessage,
  type EncryptedSend,
  type EncryptedSendInput,
  encryptMime,
  parseMime,
  planEncryptedSends,
  protectedParts,
} from "./index";
import { testKeys } from "./test-fixtures";

type Fixtures = {
  plan: { name: string; input: EncryptedSendInput; expect: EncryptedSend[] }[];
  protect: {
    kind: EncryptedSend["kind"];
    mime: string;
    bccCopy: boolean;
    outerHeaders: string[];
    inner: string;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/encrypted-send-fixtures.json", import.meta.url),
    "utf8",
  ),
);
const keys = await testKeys(
  JSON.parse(
    readFileSync(
      new URL("../../testdata/pgp-fixtures.json", import.meta.url),
      "utf8",
    ),
  ).keys,
);
const alice = keys.alice!;
const bob = keys.bob!;
const carol = keys.carol!;

describe("planEncryptedSends", () => {
  for (const entry of fixtures.plan) {
    test(entry.name, () => {
      expect(planEncryptedSends(entry.input)).toEqual(entry.expect);
    });
  }
});

describe("protectedParts for planned sends", () => {
  for (const [index, entry] of fixtures.protect.entries()) {
    test(`${entry.kind} ${index}`, () => {
      expect(protectedParts(entry.mime, { bccCopy: entry.bccCopy })).toEqual({
        outerHeaders: entry.outerHeaders,
        inner: entry.inner,
      });
    });
  }
});

function addresses(value: string | undefined): string[] {
  return value === undefined
    ? []
    : [...value.matchAll(/[^\s<>,]+@[^\s<>,]+/g)].map((match) =>
        match[0].toLowerCase(),
      );
}

test("a Bcc copy reaches only its recipient and shows them inside", async () => {
  const [, copy] = fixtures.protect;
  const encrypted = await encryptMime(copy!.mime, {
    encryptionKeys: [carol.publicKey, alice.publicKey],
    signingKey: alice.privateKey,
    boundary: "enc",
    bccCopy: true,
  });
  const outer = parseMime(encrypted);
  const outerNames = outer.headers.map((field) => field.name.toLowerCase());
  expect(outerNames).not.toContain("to");
  expect(outerNames).not.toContain("cc");
  expect(
    addresses(outer.headers.find((field) => field.name === "Bcc")?.value),
  ).toEqual(["hidden@example.net"]);
  const opened = await decryptMessage(new TextEncoder().encode(encrypted), {
    decryptionKeys: [carol.privateKey],
    verificationKeys: [alice.publicKey],
  });
  expect(opened.entity.raw).toBe(copy!.inner);
  expect(opened.protectedHeaders).toMatchObject({
    to: "Bob Test <bob@example.com>",
    cc: "carol@example.org",
    bcc: "Hidden Person <hidden@example.net>",
  });
  const refused = await decryptMessage(new TextEncoder().encode(encrypted), {
    decryptionKeys: [bob.privateKey],
    verificationKeys: [alice.publicKey],
  }).catch((error: unknown) => error);
  expect((refused as { code?: string }).code).toBe("wrong-key");
});

test("the main message names no Bcc recipient", () => {
  const [main] = fixtures.protect;
  expect(main!.mime).not.toContain("Bcc:");
  expect(main!.mime).not.toContain("hidden@example.net");
  expect(main!.mime.toLowerCase()).not.toContain("dana@example.com");
});
