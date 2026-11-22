import { describe, expect, it } from "bun:test";
import { randomBytes } from "node:crypto";
import {
  AesGcmSealer,
  DEV_ONLY_SEAL_KEY,
  devSealer,
  type KmsClient,
  KmsSealer,
} from "./sealer.js";

describe("AesGcmSealer", () => {
  const sealer = new AesGcmSealer(randomBytes(32));

  it("round trips", async () => {
    const plain = "1//0refresh-token-ünïcode";
    const sealed = await sealer.seal(plain);
    expect(sealed).not.toContain(plain);
    expect(await sealer.open(sealed)).toBe(plain);
  });

  it("uses a fresh IV per seal", async () => {
    expect(await sealer.seal("same")).not.toBe(await sealer.seal("same"));
  });

  it("rejects tampering and other keys", async () => {
    const sealed = await sealer.seal("secret");
    const packed = Buffer.from(sealed.slice("aes:".length), "base64");
    packed[packed.length - 1] = (packed[packed.length - 1] ?? 0) ^ 1;
    await expect(
      sealer.open(`aes:${packed.toString("base64")}`),
    ).rejects.toThrow();
    await expect(
      new AesGcmSealer(randomBytes(32)).open(sealed),
    ).rejects.toThrow();
    await expect(sealer.open("kms:abc")).rejects.toThrow();
  });

  it("requires a 32-byte key", () => {
    expect(() => new AesGcmSealer(randomBytes(16))).toThrow();
  });
});

describe("devSealer", () => {
  it("defaults to the dev-only key and honors the override", async () => {
    expect(DEV_ONLY_SEAL_KEY.length).toBe(32);
    const sealed = await devSealer({}).seal("secret");
    expect(await new AesGcmSealer(DEV_ONLY_SEAL_KEY).open(sealed)).toBe(
      "secret",
    );
    const overrideKey = randomBytes(32);
    const overridden = await devSealer({
      INBOX_DEV_SEAL_KEY: overrideKey.toString("base64"),
    }).seal("secret");
    expect(await new AesGcmSealer(overrideKey).open(overridden)).toBe("secret");
  });
});

describe("KmsSealer", () => {
  // Stands in for KMS by reversing bytes, so a round trip proves both directions were applied.
  const keyNames: string[] = [];
  const fakeKms: KmsClient = {
    async encrypt({ name, plaintext }) {
      keyNames.push(name);
      return [{ ciphertext: Buffer.from(plaintext).reverse() }];
    },
    async decrypt({ name, ciphertext }) {
      keyNames.push(name);
      return [{ plaintext: Buffer.from(ciphertext).reverse() }];
    },
  };
  const keyName =
    "projects/demo/locations/global/keyRings/inbox/cryptoKeys/refresh-tokens";

  it("round trips through the client with the configured key", async () => {
    const sealer = new KmsSealer(keyName, fakeKms);
    const sealed = await sealer.seal("refresh");
    expect(sealed.startsWith("kms:")).toBe(true);
    expect(await sealer.open(sealed)).toBe("refresh");
    expect(keyNames).toEqual([keyName, keyName]);
  });

  it("rejects values it did not seal", async () => {
    await expect(
      new KmsSealer(keyName, fakeKms).open("aes:abc"),
    ).rejects.toThrow();
  });
});
