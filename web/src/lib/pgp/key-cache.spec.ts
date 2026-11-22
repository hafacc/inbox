import { describe, expect, it } from "bun:test";
import {
  afterAutocrypt,
  afterLookup,
  FOUND_TTL_MS,
  isFresh,
  type KeyCacheEntry,
  KeyDirectory,
  type KeyFetchers,
  MISS_TTL_MS,
} from "./key-cache";

const found: KeyCacheEntry = {
  key: "K",
  source: "wkd",
  fetchedAt: 0,
  seenAt: 0,
};
const miss: KeyCacheEntry = {
  key: null,
  source: null,
  fetchedAt: 0,
  seenAt: 0,
};

describe("key cache rules", () => {
  it("reuses a key for 7 days and a miss for 1", () => {
    expect(isFresh(undefined, 0)).toBe(false);
    expect(isFresh(found, FOUND_TTL_MS - 1)).toBe(true);
    expect(isFresh(found, FOUND_TTL_MS)).toBe(false);
    expect(isFresh(miss, MISS_TTL_MS - 1)).toBe(true);
    expect(isFresh(miss, MISS_TTL_MS)).toBe(false);
  });

  it("keeps an Autocrypt key when a lookup finds nothing", () => {
    const autocrypt: KeyCacheEntry = {
      key: "A",
      source: "autocrypt",
      fetchedAt: 0,
      seenAt: 5,
    };
    expect(afterLookup(autocrypt, null, 10)).toEqual({
      ...autocrypt,
      fetchedAt: 10,
    });
    expect(afterLookup(found, null, 10)).toEqual({ ...miss, fetchedAt: 10 });
    expect(afterLookup(autocrypt, { key: "W", source: "wkd" }, 10)).toEqual({
      key: "W",
      source: "wkd",
      fetchedAt: 10,
      seenAt: 5,
    });
  });

  it("takes an Autocrypt key only from newer mail", () => {
    const entry = {
      key: "A",
      source: "autocrypt" as const,
      fetchedAt: 0,
      seenAt: 100,
    };
    expect(afterAutocrypt(entry, "B", 100, 1)).toBeNull();
    expect(afterAutocrypt(entry, "B", 101, 1)).toEqual({
      key: "B",
      source: "autocrypt",
      fetchedAt: 1,
      seenAt: 101,
    });
    expect(afterAutocrypt(undefined, "B", 1, 2)?.key).toBe("B");
  });
});

function setup(fetchers: Partial<KeyFetchers>, now = { value: 1_000 }) {
  const store = new Map<string, KeyCacheEntry>();
  const calls: string[] = [];
  const directory = new KeyDirectory(
    {
      get: async (address) => store.get(address),
      put: async (address, entry) => {
        store.set(address, entry);
      },
    },
    {
      wkd: async (address) => {
        calls.push(`wkd:${address}`);
        return (await fetchers.wkd?.(address)) ?? null;
      },
      vks: async (address) => {
        calls.push(`vks:${address}`);
        return (await fetchers.vks?.(address)) ?? null;
      },
    },
    async (data, address) =>
      typeof data === "string" ? `${data}|${address}` : `bin|${address}`,
    () => now.value,
  );
  return { directory, store, calls, now };
}

describe("KeyDirectory", () => {
  it("tries WKD, then keys.openpgp.org, and caches what it found", async () => {
    const { directory, store, calls } = setup({ vks: async () => "armored" });
    expect(await directory.keyFor("Ana@Example.com")).toBe(
      "armored|ana@example.com",
    );
    expect(calls).toEqual(["wkd:ana@example.com", "vks:ana@example.com"]);
    expect(store.get("ana@example.com")?.source).toBe("vks");
    expect(await directory.keyFor("ana@example.com")).toBe(
      "armored|ana@example.com",
    );
    expect(calls).toHaveLength(2);
  });

  it("stops at WKD", async () => {
    const { directory, calls } = setup({
      wkd: async () => new Uint8Array([1]),
    });
    expect(await directory.keyFor("bo@proton.example.net")).toBe(
      "bin|bo@proton.example.net",
    );
    expect(calls).toEqual(["wkd:bo@proton.example.net"]);
  });

  it("remembers a miss for a day", async () => {
    const { directory, calls, now } = setup({});
    expect(await directory.keyFor("x@example.com")).toBeNull();
    expect(await directory.keyFor("x@example.com")).toBeNull();
    expect(calls).toHaveLength(2);
    now.value += MISS_TTL_MS;
    await directory.keyFor("x@example.com");
    expect(calls).toHaveLength(4);
  });

  it("doesn't remember a miss it couldn't vouch for", async () => {
    const { directory, store } = setup({
      wkd: async () => {
        throw new TypeError("Failed to fetch");
      },
    });
    expect(await directory.keyFor("x@example.com")).toBeNull();
    expect(store.has("x@example.com")).toBe(false);
  });

  it("shares one lookup between callers", async () => {
    const { directory, calls } = setup({ vks: async () => "k" });
    await Promise.all([directory.keyFor("a@b.c"), directory.keyFor("A@b.c")]);
    expect(calls).toEqual(["wkd:a@b.c", "vks:a@b.c"]);
  });

  it("takes Autocrypt keys from newer mail", async () => {
    const { directory, store } = setup({});
    await directory.noteAutocrypt("Ana@x.example.org", new Uint8Array([1]), 50);
    expect(store.get("ana@x.example.org")).toMatchObject({
      key: "bin|ana@x.example.org",
      seenAt: 50,
    });
    store.set("ana@x.example.org", {
      ...store.get("ana@x.example.org")!,
      key: "kept",
    });
    await directory.noteAutocrypt("ana@x.example.org", new Uint8Array([2]), 40);
    expect(store.get("ana@x.example.org")?.key).toBe("kept");
    expect(await directory.cached("ana@x.example.org")).toBe("kept");
  });
});
