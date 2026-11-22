import { describe, expect, it } from "bun:test";
import { readFileSync } from "node:fs";
import { generateKey, readKey } from "openpgp";
import {
  type KeyStore,
  keyHash,
  kvKeyFor,
  publicationOf,
  reconcileKeys,
  syncUserKey,
  wkdKeyBytes,
  workersKv,
} from "./wkd-keys.js";

type TestPerson = { address: string; name: string };

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../../shared/testdata/pgp-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  keys: Record<string, TestPerson>;
  wkd: { address: string; hash: string }[];
};

/** A key pair made for this run, as shared/src/pgp/keys.ts makes them. */
async function testKey(person: TestPerson) {
  const pair = await generateKey({
    type: "ecc",
    curve: "curve25519Legacy",
    userIDs: [{ name: person.name, email: person.address }],
    format: "armored",
  });
  return { address: person.address, ...pair };
}
const alice = await testKey(fixtures.keys.alice!);
const bob = await testKey(fixtures.keys.bob!);

class MemoryStore implements KeyStore {
  entries = new Map<string, Uint8Array>();
  async put(name: string, value: Uint8Array) {
    this.entries.set(name, value);
  }
  async delete(name: string) {
    this.entries.delete(name);
  }
  async list(prefix: string) {
    return [...this.entries]
      .filter(([name]) => name.startsWith(prefix))
      .map(([name, value]) => ({ name, hash: keyHash(value) }));
  }
}

function user(
  address: string | null,
  status: string,
  publicKey?: string,
): Record<string, unknown> {
  return {
    address,
    addressSetup: { status },
    ...(publicKey === undefined
      ? {}
      : { pgp: { publicKey, sealed: [], recoverySealed: {} } }),
    settings: { signature: "x" },
  };
}

describe("publicationOf", () => {
  it("publishes only an active @hafa.cc address with a key", () => {
    expect(publicationOf(user("Test@hafa.cc", "active", "k"))).toEqual({
      address: "test@hafa.cc",
      publicKey: "k",
    });
    expect(publicationOf(user("test@hafa.cc", "pending", "k"))).toBeNull();
    expect(publicationOf(user("test@hafa.cc", "releasing", "k"))).toBeNull();
    expect(publicationOf(user("test@hafa.cc", "active"))).toBeNull();
    expect(publicationOf(user(null, "active", "k"))).toBeNull();
    expect(
      publicationOf(user("alice@gmail.example.com", "active", "k")),
    ).toBeNull();
    expect(publicationOf(undefined)).toBeNull();
  });
});

describe("wkdKeyBytes", () => {
  it("serves the public key, binary, with only the address's user id", async () => {
    const bytes = await wkdKeyBytes({
      address: "test@hafa.cc",
      publicKey: alice.publicKey,
    });
    const key = await readKey({ binaryKey: bytes! });
    expect(key.isPrivate()).toBe(false);
    expect(key.getUserIDs()).toEqual(["Alice Test <test@hafa.cc>"]);
  });

  it("refuses a private key, another address's key, or garbage", async () => {
    for (const publicKey of [alice.privateKey, bob.publicKey, "nope"]) {
      expect(
        await wkdKeyBytes({ address: "test@hafa.cc", publicKey }),
      ).toBeNull();
    }
  });
});

/** `syncUserKey` for a user who holds every address. */
function sync(
  store: KeyStore,
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
) {
  return syncUserKey(store, before, after, async () => "self");
}

const aliceKey = await kvKeyFor("test@hafa.cc");

describe("syncUserKey", () => {
  it("publishes once the address is active, and removes it on release", async () => {
    const store = new MemoryStore();
    const pending = user("test@hafa.cc", "pending", alice.publicKey);
    const active = user("test@hafa.cc", "active", alice.publicKey);
    expect(await sync(store, undefined, pending)).toEqual({
      published: [],
      removed: [],
    });
    expect(await sync(store, pending, active)).toEqual({
      published: [aliceKey],
      removed: [],
    });
    expect(store.entries.has(aliceKey)).toBe(true);
    expect(
      await sync(store, active, { ...active, settings: { a: 1 } }),
    ).toEqual({ published: [], removed: [] });
    expect(
      await sync(
        store,
        active,
        user("test@hafa.cc", "releasing", alice.publicKey),
      ),
    ).toEqual({ published: [], removed: [aliceKey] });
    expect(store.entries.size).toBe(0);
  });

  it("removes the key when it is deleted or can't be published", async () => {
    const store = new MemoryStore();
    const active = user("test@hafa.cc", "active", alice.publicKey);
    await sync(store, undefined, active);
    await sync(store, active, user("test@hafa.cc", "active", bob.publicKey));
    expect(store.entries.size).toBe(0);
    await sync(store, undefined, active);
    await sync(store, active, user("test@hafa.cc", "active"));
    expect(store.entries.size).toBe(0);
  });

  it("publishes only while the user holds the address, and never removes another holder's key", async () => {
    const store = new MemoryStore();
    const active = user("test@hafa.cc", "active", alice.publicKey);
    const asked: string[] = [];
    expect(
      await syncUserKey(store, undefined, active, async (address) => {
        asked.push(address);
        return "none";
      }),
    ).toEqual({ published: [], removed: [aliceKey] });
    expect(asked).toEqual(["test@hafa.cc"]);

    await store.put(aliceKey, new Uint8Array([7]));
    expect(
      await syncUserKey(
        store,
        active,
        user("test@hafa.cc", "releasing", alice.publicKey),
        async () => "other",
      ),
    ).toEqual({ published: [], removed: [] });
    expect(store.entries.get(aliceKey)).toEqual(new Uint8Array([7]));
  });
});

it("reconciles: puts every wanted key and drops the rest", async () => {
  const store = new MemoryStore();
  await store.put("hu/stale", new Uint8Array([1]));
  await store.put("other/thing", new Uint8Array([1]));
  const result = await reconcileKeys(
    store,
    [
      { uid: "a", data: user("test@hafa.cc", "active", alice.publicKey) },
      { uid: "b", data: user("staff@hafa.cc", "active", bob.publicKey) },
    ],
    async () => "self",
  );
  expect(result).toEqual({
    published: [await kvKeyFor("test@hafa.cc")],
    removed: ["hu/stale"],
    unchanged: 0,
  });
  expect([...store.entries.keys()].sort()).toEqual(
    [await kvKeyFor("test@hafa.cc"), "other/thing"].sort(),
  );
});

it("reconciles without rewriting a key that is already stored", async () => {
  const store = new MemoryStore();
  const users = [
    { uid: "a", data: user("test@hafa.cc", "active", alice.publicKey) },
  ];
  const self = async () => "self" as const;
  await reconcileKeys(store, users, self);
  let puts = 0;
  const put = store.put.bind(store);
  store.put = async (name, value) => {
    puts++;
    await put(name, value);
  };
  expect(await reconcileKeys(store, users, self)).toEqual({
    published: [],
    removed: [],
    unchanged: 1,
  });
  expect(puts).toBe(0);
});

it("reconciles by the same holder rule as a write: no key for an address the user doesn't hold", async () => {
  const store = new MemoryStore();
  const name = await kvKeyFor("test@hafa.cc");
  await store.put(name, new Uint8Array([7]));
  const asked: string[] = [];
  const result = await reconcileKeys(
    store,
    [{ uid: "a", data: user("test@hafa.cc", "active", alice.publicKey) }],
    async (uid, address) => {
      asked.push(`${uid} ${address}`);
      return "other";
    },
  );
  expect(asked).toEqual(["a test@hafa.cc"]);
  expect(result).toEqual({ published: [], removed: [name], unchanged: 0 });
});

it("talks to Workers KV's API", async () => {
  const calls: { url: string; method: string; body?: unknown }[] = [];
  const kv = workersKv(
    { apiToken: "t", accountId: "acc", namespaceId: "ns" },
    (async (url: string, init: RequestInit) => {
      calls.push({ url, method: init.method ?? "GET", body: init.body });
      if (url.includes("/keys?")) {
        const cursor = new URL(url).searchParams.get("cursor");
        return Response.json({
          result: [
            cursor
              ? { name: "hu/b" }
              : { name: "hu/a", metadata: { hash: "h" } },
          ],
          result_info: { cursor: cursor ? "" : "next" },
        });
      }
      if (init.method === "DELETE") return new Response("", { status: 404 });
      return Response.json({ success: true });
    }) as typeof fetch,
  );
  await kv.put("hu/a", new Uint8Array([1, 2]));
  await kv.delete("hu/a");
  expect(await kv.list("hu/")).toEqual([
    { name: "hu/a", hash: "h" },
    { name: "hu/b" },
  ]);
  expect(calls[0]).toMatchObject({
    url: "https://api.cloudflare.com/client/v4/accounts/acc/storage/kv/namespaces/ns/values/hu%2Fa",
    method: "PUT",
  });
  const form = calls[0]?.body as FormData;
  expect(JSON.parse(String(form.get("metadata")))).toEqual({
    hash: keyHash(new Uint8Array([1, 2])),
  });
  expect(
    new Uint8Array(await (form.get("value") as Blob).arrayBuffer()),
  ).toEqual(new Uint8Array([1, 2]));
  expect(calls[1]?.method).toBe("DELETE");
});
