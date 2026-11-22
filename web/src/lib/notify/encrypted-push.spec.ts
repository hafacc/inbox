import { describe, expect, it } from "bun:test";
import { buildMime } from "@inbox/shared/compose";
import * as pgp from "@inbox/shared/pgp";
import { makeDeviceRecord, privateKeyOf } from "../pgp/device-key";
import { TEST_KEYS } from "../pgp/test-keys";
import { decryptPush, previewOf } from "./encrypted-push";
import type { PushPayload } from "./push";

const { alice, bob } = TEST_KEYS;

const payload: PushPayload = {
  kind: "message",
  threadId: "t1",
  messageId: "m1",
  sender: "Alice",
  senderAddress: alice.address,
  subject: "",
  snippet: "",
  account: bob.address,
  actionToken: "",
  sentAt: "1",
  encrypted: true,
};

async function message(): Promise<Uint8Array> {
  const mime = buildMime({
    from: { name: "Alice", email: alice.address },
    to: [{ name: "", email: bob.address }],
    cc: [],
    bcc: [],
    subject: "Dinner",
    markdown: "Are you free\non Friday?\n\n> old quote",
    signature: null,
    inReplyTo: null,
    references: [],
    inlineImages: [],
    attachments: [],
    extraHeaders: [],
    boundary: "b",
  });
  return new TextEncoder().encode(
    await pgp.encryptMime(mime, {
      encryptionKeys: [bob.publicKey, alice.publicKey],
      signingKey: alice.privateKey,
      boundary: "c",
    }),
  );
}

describe("encrypted notifications", () => {
  it("previews the protected subject and the text without quotes", async () => {
    const decrypted = await pgp.decryptMessage(await message(), {
      decryptionKeys: [bob.privateKey],
      verificationKeys: [],
    });
    expect(previewOf(decrypted, pgp)).toEqual({
      subject: "Dinner",
      snippet: "Are you free on Friday?",
    });
  });

  it("decrypts with the device's key", async () => {
    const raw = await message();
    const record = await makeDeviceRecord(bob.privateKey, bob.fingerprint);
    const shown = await decryptPush(payload, {
      deviceRecord: async () => record,
      privateKeyOf,
      rawMessage: async () => raw,
      decrypt: (bytes, key) =>
        pgp.decryptMessage(bytes, {
          decryptionKeys: [key],
          verificationKeys: [],
        }),
      tools: pgp,
    });
    expect(shown.subject).toBe("Dinner");
    expect(shown.snippet).toBe("Are you free on Friday?");
  });

  it("leaves the push as it came without a key, or when anything fails", async () => {
    const deps = {
      deviceRecord: async () => null,
      privateKeyOf,
      rawMessage: async () => new Uint8Array(),
      decrypt: async () => {
        throw new Error("no");
      },
      tools: pgp,
    };
    expect(await decryptPush(payload, deps)).toBe(payload);
    const record = await makeDeviceRecord(bob.privateKey, bob.fingerprint);
    expect(
      await decryptPush(payload, { ...deps, deviceRecord: async () => record }),
    ).toBe(payload);
    const plain = { ...payload, encrypted: false };
    expect(await decryptPush(plain, deps)).toBe(plain);
  });
});
