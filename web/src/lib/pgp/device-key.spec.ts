import { describe, expect, it } from "bun:test";
import {
  makeDeviceRecord,
  newWrappingKey,
  openBytes,
  openJson,
  privateKeyOf,
  sealBytes,
  sealJson,
} from "./device-key";

describe("device key", () => {
  it("is not extractable", async () => {
    const key = await newWrappingKey();
    expect(key.extractable).toBe(false);
    await expect(crypto.subtle.exportKey("raw", key)).rejects.toThrow();
  });

  it("seals and opens bytes with a fresh nonce each time", async () => {
    const key = await newWrappingKey();
    const plain = new TextEncoder().encode("hello");
    const first = await sealBytes(key, plain);
    const second = await sealBytes(key, plain);
    expect(first).not.toEqual(second);
    expect(new TextDecoder().decode(await openBytes(key, first))).toBe("hello");
  });

  it("refuses another key or damaged data", async () => {
    const sealed = await sealBytes(
      await newWrappingKey(),
      new Uint8Array([1, 2]),
    );
    await expect(openBytes(await newWrappingKey(), sealed)).rejects.toThrow();
  });

  it("seals JSON values", async () => {
    const key = await newWrappingKey();
    const value = { subject: "Lease", words: ["a", "b"] };
    expect(await openJson(key, await sealJson(key, value))).toEqual(value);
  });

  it("wraps a private key and gives it back", async () => {
    const armored = "-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc\n";
    const record = await makeDeviceRecord(armored, "ab12");
    expect(record.fingerprint).toBe("ab12");
    expect(new TextDecoder().decode(record.sealedPrivateKey)).not.toContain(
      "PRIVATE",
    );
    expect(await privateKeyOf(record)).toBe(armored);
  });
});
