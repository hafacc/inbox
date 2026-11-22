import { describe, expect, it } from "bun:test";
import {
  type HolderKeyStore,
  holderHash,
  holderHasher,
} from "./holder-hash.js";
import { AesGcmSealer } from "./sealer.js";

const UID = "google:110248495921238986420";

class MemoryKeys implements HolderKeyStore {
  sealed: string | undefined;
  async read() {
    return this.sealed;
  }
  async createIfAbsent(sealed: string) {
    this.sealed ??= sealed;
    return this.sealed;
  }
}

describe("holderHash", () => {
  it("is stable per key and account, and hides the account id", () => {
    const key = Buffer.alloc(32, 1);
    const hash = holderHash(key, UID);
    expect(holderHash(key, UID)).toBe(hash);
    expect(holderHash(Buffer.alloc(32, 2), UID)).not.toBe(hash);
    expect(holderHash(key, "google:1")).not.toBe(hash);
    expect(hash).not.toContain(UID.slice("google:".length));
    expect(hash).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});

describe("holderHasher", () => {
  it("makes one key, stores it only sealed, and reuses it", async () => {
    const keys = new MemoryKeys();
    const sealer = new AesGcmSealer(Buffer.alloc(32, 3));
    const first = await holderHasher(keys, sealer)(UID);
    expect(keys.sealed?.startsWith("aes:")).toBe(true);
    const key = Buffer.from(await sealer.open(keys.sealed!), "base64");
    expect(first).toBe(holderHash(key, UID));
    expect(await holderHasher(keys, sealer)(UID)).toBe(first);
  });

  it("uses the key another instance stored first", async () => {
    const keys = new MemoryKeys();
    const sealer = new AesGcmSealer(Buffer.alloc(32, 3));
    const key = Buffer.alloc(32, 9);
    keys.read = async () => undefined;
    keys.sealed = await sealer.seal(key.toString("base64"));
    expect(await holderHasher(keys, sealer)(UID)).toBe(holderHash(key, UID));
  });
});
