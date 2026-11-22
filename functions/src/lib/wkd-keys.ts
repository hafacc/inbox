import { createHash } from "node:crypto";
import type { Key } from "openpgp";
import { nameOf } from "../../../shared/src/address-name.js";
import { wkdHash } from "../../../shared/src/pgp/wkd.js";
import { fetchTimeout, mapLimit } from "./util.js";

const BASE = "https://api.cloudflare.com/client/v4";

/** Domain whose keys the Key Worker publishes. */
export const KEY_DOMAIN = "hafa.cc";

/** Prefix of the Workers KV keys holding published keys: `hu/<WKD hash>`. */
const KV_PREFIX = "hu/";

/** The KV key a hafa.cc address's public key is stored under. */
export async function kvKeyFor(address: string): Promise<string> {
  return `${KV_PREFIX}${await wkdHash(nameOf(address))}`;
}

/** What should be published for a user: their active @hafa.cc address and its public key. */
export interface Publication {
  /** `name@hafa.cc`, lower-case */
  address: string;
  /** armored public key as the client stored it */
  publicKey: string;
}

/**
 * What `users/{uid}` says should be published: only while the address is `active` (a claim being
 * set up or released publishes nothing) and a public key is stored. `address` is written only by
 * the server, so it is trusted as the user's.
 */
export function publicationOf(
  data: Record<string, unknown> | undefined,
): Publication | null {
  if (data === undefined) return null;
  const address = data.address;
  const setup = data.addressSetup as { status?: unknown } | null | undefined;
  const pgp = data.pgp as { publicKey?: unknown } | null | undefined;
  if (
    typeof address !== "string" ||
    !address.toLowerCase().endsWith(`@${KEY_DOMAIN}`) ||
    setup?.status !== "active" ||
    typeof pgp?.publicKey !== "string"
  ) {
    return null;
  }
  return { address: address.toLowerCase(), publicKey: pgp.publicKey };
}

/**
 * The key as WKD should serve it (binary), or null when it mustn't be published: not a public key,
 * no user id with the address, or no usable encryption key. User ids for other addresses are
 * dropped, as WKD asks.
 */
export async function wkdKeyBytes(
  publication: Publication,
  date = new Date(),
): Promise<Uint8Array | null> {
  const { readKey } = await import("openpgp");
  let key: Key;
  try {
    key = await readKey({ armoredKey: publication.publicKey });
  } catch {
    return null;
  }
  if (key.isPrivate()) return null;
  const users = key.users.filter(
    (user) => user.userID?.email?.toLowerCase() === publication.address,
  );
  if (users.length === 0) return null;
  key.users = users;
  try {
    await key.getEncryptionKey(undefined, date);
  } catch {
    return null;
  }
  return key.write();
}

/** Most keys a full sync reads or writes at once. */
const SYNC_CONCURRENCY = 10;

/** SHA-256 of a stored key's bytes, kept with it so a sync can skip rewriting an unchanged key. */
export function keyHash(value: Uint8Array): string {
  return createHash("sha256").update(value).digest("hex");
}

/** A stored key's name and, when stored with one, its {@link keyHash}. */
export interface StoredKey {
  /** KV key */
  name: string;
  /** hash of the value */
  hash?: string;
}

/** Where the published keys live. */
export interface KeyStore {
  /** store or replace a key, with its {@link keyHash} */
  put(name: string, value: Uint8Array): Promise<void>;
  /** remove a key; a missing one is fine */
  delete(name: string): Promise<void>;
  /** every key with the prefix */
  list(prefix: string): Promise<StoredKey[]>;
}

/** Workers KV through Cloudflare's API; the token needs Account › Workers KV Storage › Edit. */
export function workersKv(
  config: { apiToken: string; accountId: string; namespaceId: string },
  fetchImpl: typeof fetch = fetch,
): KeyStore {
  const base = `${BASE}/accounts/${encodeURIComponent(config.accountId)}/storage/kv/namespaces/${encodeURIComponent(config.namespaceId)}`;
  const auth = { Authorization: `Bearer ${config.apiToken}` };
  async function check(response: Response, allowMissing = false) {
    if (response.ok || (allowMissing && response.status === 404)) return;
    throw new Error(
      `Workers KV answered ${response.status}: ${(await response.text()).slice(0, 300)}`,
    );
  }
  return {
    async put(name, value) {
      const form = new FormData();
      form.set("value", new Blob([new Uint8Array(value)]));
      form.set("metadata", JSON.stringify({ hash: keyHash(value) }));
      await check(
        await fetchImpl(`${base}/values/${encodeURIComponent(name)}`, {
          method: "PUT",
          headers: auth,
          body: form,
          signal: fetchTimeout(),
        }),
      );
    },
    async delete(name) {
      await check(
        await fetchImpl(`${base}/values/${encodeURIComponent(name)}`, {
          method: "DELETE",
          headers: auth,
          signal: fetchTimeout(),
        }),
        true,
      );
    },
    async list(prefix) {
      const keys: StoredKey[] = [];
      let cursor = "";
      for (let page = 0; page < 100; page++) {
        const params = new URLSearchParams({ prefix, limit: "1000" });
        if (cursor !== "") params.set("cursor", cursor);
        const response = await fetchImpl(`${base}/keys?${params}`, {
          method: "GET",
          headers: auth,
          signal: fetchTimeout(),
        });
        await check(response);
        const body = (await response.json()) as {
          result?: { name: string; metadata?: { hash?: unknown } }[];
          result_info?: { cursor?: string };
        };
        for (const { name, metadata } of body.result ?? []) {
          const hash = metadata?.hash;
          keys.push(typeof hash === "string" ? { name, hash } : { name });
        }
        cursor = body.result_info?.cursor ?? "";
        if (cursor === "") break;
      }
      return keys;
    },
  };
}

/** What a sync did. */
export type SyncResult = { published: string[]; removed: string[] };

/** What a full sync did: also how many keys were already as wanted. */
export type ReconcileResult = SyncResult & { unchanged: number };

/** Who holds an address in `addresses/{name}` now: the user being synced, no one, or someone else. */
export type AddressHolder = "self" | "none" | "other";

/**
 * The bytes to publish for a user's publication, or null when none may be: the one rule both the
 * per-write sync and the daily one follow. A key is published only while `addresses/{name}` is the
 * user's (`holder` is `self`) and {@link wkdKeyBytes} accepts it.
 */
export async function publishedKey(
  publication: Publication,
  holder: AddressHolder,
): Promise<Uint8Array | null> {
  return holder === "self" ? wkdKeyBytes(publication) : null;
}

/**
 * Bring the published key in step with a change to `users/{uid}`: publish the new key (or
 * re-publish it when it changed), and remove the old entry when the address went away, changed,
 * stopped being active, or the key was removed or can't be published. Does nothing when neither
 * the address nor the key changed.
 *
 * `after` should be the doc as it is now, not as the change left it, so a late retry doesn't put
 * back a key the user has since replaced. `holderOf` is asked before every put and delete: a key is
 * published only while `addresses/{name}` is this user's, and an entry is never removed while
 * someone else holds the address (it is theirs now).
 */
export async function syncUserKey(
  store: KeyStore,
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
  holderOf: (address: string) => Promise<AddressHolder>,
): Promise<SyncResult> {
  const old = publicationOf(before);
  const next = publicationOf(after);
  if (old?.address === next?.address && old?.publicKey === next?.publicKey) {
    return { published: [], removed: [] };
  }
  const holders = new Map<string, AddressHolder>();
  const holder = async (address: string) => {
    const known = holders.get(address);
    if (known !== undefined) return known;
    const found = await holderOf(address);
    holders.set(address, found);
    return found;
  };
  const result: SyncResult = { published: [], removed: [] };
  const bytes =
    next === null ? null : await publishedKey(next, await holder(next.address));
  const keep =
    next !== null && bytes !== null ? await kvKeyFor(next.address) : null;
  if (keep !== null && bytes !== null) {
    await store.put(keep, bytes);
    result.published.push(keep);
  }
  for (const gone of [old, next]) {
    if (gone === null) continue;
    const name = await kvKeyFor(gone.address);
    if (name === keep || result.removed.includes(name)) continue;
    if ((await holder(gone.address)) === "other") continue;
    await store.delete(name);
    result.removed.push(name);
  }
  return result;
}

/** A user's doc, as the daily sync reads it. */
export interface UserRecord {
  /** Firebase uid */
  uid: string;
  /** `users/{uid}` */
  data: Record<string, unknown>;
}

/**
 * Make the store hold exactly the keys these users should publish ({@link publishedKey}, asking
 * `holderOf` who holds each address): put each whose stored hash differs (so a missed write or a
 * store set up late catches up) and delete every other `hu/` entry.
 */
export async function reconcileKeys(
  store: KeyStore,
  users: readonly UserRecord[],
  holderOf: (uid: string, address: string) => Promise<AddressHolder>,
): Promise<ReconcileResult> {
  const publications = users.flatMap(({ uid, data }) => {
    const publication = publicationOf(data);
    return publication === null ? [] : [{ uid, publication }];
  });
  const [built, stored] = await Promise.all([
    mapLimit(publications, SYNC_CONCURRENCY, async ({ uid, publication }) => ({
      name: await kvKeyFor(publication.address),
      bytes: await publishedKey(
        publication,
        await holderOf(uid, publication.address),
      ),
    })),
    store.list(KV_PREFIX),
  ]);
  const wanted = new Map<string, Uint8Array>();
  for (const { name, bytes } of built) {
    if (bytes !== null) wanted.set(name, bytes);
  }
  const storedHashes = new Map(stored.map(({ name, hash }) => [name, hash]));
  const changed = [...wanted].filter(
    ([name, bytes]) => storedHashes.get(name) !== keyHash(bytes),
  );
  const stale = stored
    .map(({ name }) => name)
    .filter((name) => !wanted.has(name));
  await mapLimit(changed, SYNC_CONCURRENCY, ([name, bytes]) =>
    store.put(name, bytes),
  );
  await mapLimit(stale, SYNC_CONCURRENCY, (name) => store.delete(name));
  return {
    published: changed.map(([name]) => name),
    removed: stale,
    unchanged: wanted.size - changed.length,
  };
}
