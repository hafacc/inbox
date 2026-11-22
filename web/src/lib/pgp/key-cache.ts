// Recipients' public keys: the device's cache, then WKD (through the Key Worker), then
// keys.openpgp.org (docs/pgp-contract.md "Looking up recipients' keys").

import { addressKey } from "@inbox/shared/compose";

/** Where a cached key came from. */
export type KeySource = "autocrypt" | "wkd" | "vks";

/** What the device remembers about one address's key. */
export type KeyCacheEntry = {
  /** armored public key, or null when none was found */
  key: string | null;
  /** where it came from; null for a miss */
  source: KeySource | null;
  /** when it was last looked up (or taken from Autocrypt), epoch ms */
  fetchedAt: number;
  /** date of the newest message whose Autocrypt header set it, epoch ms; 0 for none */
  seenAt: number;
};

const DAY_MS = 24 * 60 * 60 * 1000;
/** How long a found key is used before looking again. */
export const FOUND_TTL_MS = 7 * DAY_MS;
/** How long a miss stands before looking again. */
export const MISS_TTL_MS = DAY_MS;
/** Time allowed for one lookup request. */
export const LOOKUP_TIMEOUT_MS = 5_000;
/** The Key Worker's WKD lookup for browsers. */
export const WKD_LOOKUP_URL = "https://openpgpkey.hafa.cc/lookup";

/** Whether a cached entry can be used without looking again. */
export function isFresh(
  entry: KeyCacheEntry | undefined,
  now: number,
): boolean {
  if (entry === undefined) return false;
  const ttl = entry.key === null ? MISS_TTL_MS : FOUND_TTL_MS;
  return now - entry.fetchedAt < ttl;
}

/**
 * The entry after a lookup. A miss keeps a key an Autocrypt header gave (people who don't publish
 * keys still send them that way).
 */
export function afterLookup(
  previous: KeyCacheEntry | undefined,
  found: { key: string; source: KeySource } | null,
  now: number,
): KeyCacheEntry {
  if (found !== null) {
    return {
      key: found.key,
      source: found.source,
      fetchedAt: now,
      seenAt: previous?.seenAt ?? 0,
    };
  } else if (previous?.source === "autocrypt" && previous.key !== null) {
    return { ...previous, fetchedAt: now };
  } else {
    return {
      key: null,
      source: null,
      fetchedAt: now,
      seenAt: previous?.seenAt ?? 0,
    };
  }
}

/** The entry after an Autocrypt header on a message dated `seenAt`; null when it is older news. */
export function afterAutocrypt(
  previous: KeyCacheEntry | undefined,
  key: string,
  seenAt: number,
  now: number,
): KeyCacheEntry | null {
  if (previous !== undefined && seenAt <= previous.seenAt) return null;
  return { key, source: "autocrypt", fetchedAt: now, seenAt };
}

/** Where the cache lives. */
export type KeyStore = {
  get(address: string): Promise<KeyCacheEntry | undefined>;
  put(address: string, entry: KeyCacheEntry): Promise<void>;
};

/** The two lookups; each answers null for "no key" and throws when it couldn't tell. */
export type KeyFetchers = {
  /** WKD through the Key Worker: binary key data */
  wkd(address: string): Promise<Uint8Array | null>;
  /** keys.openpgp.org: armored */
  vks(address: string): Promise<string | null>;
};

/** Pick the usable key for an address from an answer (`keyFromAnswer`). */
export type KeyPicker = (
  data: Uint8Array | string,
  address: string,
) => Promise<string | null>;

/** Keys for addresses, cached on the device, looked up at most once at a time per address. */
export class KeyDirectory {
  #store: KeyStore;
  #fetchers: KeyFetchers;
  #pick: KeyPicker;
  #clock: () => number;
  #pending = new Map<string, Promise<string | null>>();

  constructor(
    store: KeyStore,
    fetchers: KeyFetchers,
    pick: KeyPicker,
    clock: () => number = Date.now,
  ) {
    this.#store = store;
    this.#fetchers = fetchers;
    this.#pick = pick;
    this.#clock = clock;
  }

  /** The key to use for an address, or null; cached answers first. */
  keyFor(address: string): Promise<string | null> {
    const key = addressKey(address);
    let pending = this.#pending.get(key);
    if (pending === undefined) {
      pending = this.#look(key).finally(() => this.#pending.delete(key));
      this.#pending.set(key, pending);
    }
    return pending;
  }

  /** The cached key alone, without looking it up; undefined when nothing is cached. */
  async cached(address: string): Promise<string | null | undefined> {
    const entry = await this.#store
      .get(addressKey(address))
      .catch(() => undefined);
    return entry?.key;
  }

  async #look(address: string): Promise<string | null> {
    const previous = await this.#store.get(address).catch(() => undefined);
    if (isFresh(previous, this.#clock())) return previous?.key ?? null;
    let found: { key: string; source: KeySource } | null = null;
    let unsure = false;
    try {
      const data = await this.#fetchers.wkd(address);
      const key = data === null ? null : await this.#pick(data, address);
      if (key !== null) found = { key, source: "wkd" };
    } catch {
      unsure = true;
    }
    if (found === null) {
      try {
        const data = await this.#fetchers.vks(address);
        const key = data === null ? null : await this.#pick(data, address);
        if (key !== null) found = { key, source: "vks" };
      } catch {
        unsure = true;
      }
    }
    // Offline or a server down: use what we had, and don't remember a miss we can't vouch for.
    if (found === null && unsure) return previous?.key ?? null;
    const entry = afterLookup(previous, found, this.#clock());
    await this.#store.put(address, entry).catch(() => {});
    return entry.key;
  }

  /** Take the key from an Autocrypt header of a message from `address` dated `date`. */
  async noteAutocrypt(
    address: string,
    keydata: Uint8Array,
    date: number,
  ): Promise<void> {
    const key = addressKey(address);
    const previous = await this.#store.get(key).catch(() => undefined);
    if (previous !== undefined && date <= previous.seenAt) return;
    const armored = await this.#pick(keydata, key);
    if (armored === null) return;
    const entry = afterAutocrypt(previous, armored, date, this.#clock());
    if (entry !== null) await this.#store.put(key, entry).catch(() => {});
  }
}

async function fetchAnswer(url: string): Promise<Response | null> {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS),
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response;
}

/** The lookups over the network: the Key Worker for WKD, and keys.openpgp.org. */
export function networkFetchers(
  vksUrl: (address: string) => string,
): KeyFetchers {
  return {
    async wkd(address) {
      const response = await fetchAnswer(
        `${WKD_LOOKUP_URL}?${new URLSearchParams({ email: address })}`,
      );
      return response === null
        ? null
        : new Uint8Array(await response.arrayBuffer());
    },
    async vks(address) {
      const response = await fetchAnswer(vksUrl(address));
      return response === null ? null : response.text();
    },
  };
}
