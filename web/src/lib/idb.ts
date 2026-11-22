import type { LabelChange } from "@inbox/shared/offline-queue";
import { pruneKeys } from "@inbox/shared/offline-set";
import type { KeptComposer } from "./compose/composer-store";
import type { DraftJob } from "./compose/draft-queue";
import type { Contact } from "./contacts/contacts";
import type { SavedThread } from "./offline-copy";
import type { DeviceKeyRecord } from "./pgp/device-key";
import type { KeyCacheEntry } from "./pgp/key-cache";
import type { ThreadDetail } from "./thread-detail";
import type { ThreadSummary } from "./thread-summary";

/** Object stores of the on-device mail cache and the value each holds. */
export type CacheSchema = {
  // Thread ids of a list view, keyed by the view's key.
  lists: { ids: string[]; nextPageToken: string | null; savedAt: number };
  // Row data, keyed by thread id.
  summaries: ThreadSummary;
  // Threads saved with their bodies, keyed by thread id.
  threads: ThreadDetail;
  // What the saved copy knows of each saved thread (its messages' labels, dates), keyed by thread id.
  offline: SavedThread;
  // Small values: the label list, the account's address.
  meta: unknown;
  // Gmail label changes waiting to be sent, keyed by their id (unique across tabs).
  queue: LabelChange;
  // Google Contacts per People API list ("connections", "other"), with the token to sync from.
  contacts: {
    syncToken: string | null;
    people: [string, Contact[]][];
    syncedAt: number;
  };
  // Draft saves waiting for Gmail, keyed by the composer's local id.
  drafts: DraftJob;
  // Composers' contents, keyed by local id, so a closed reply reopens and a crash loses nothing.
  composers: KeptComposer;
  // Recipients' OpenPGP keys (or misses) by lower-cased address.
  keys: KeyCacheEntry;
  // This device's unlocked OpenPGP key, wrapped by a non-extractable key, under "key".
  device: DeviceKeyRecord;
};

/** A store name of the cache. */
export type StoreName = keyof CacheSchema;

/** Stores whose entries carry a last-use time, so `prune` can drop the least recently used. */
export type TrackedStore = "lists" | "summaries" | "threads";

const TRACKED: ReadonlySet<StoreName> = new Set<TrackedStore>([
  "lists",
  "summaries",
  "threads",
]);
// Last-use times of tracked entries, keyed by [store name, key].
const USAGE = "usage";

const STORES: (StoreName | typeof USAGE)[] = [
  "lists",
  "summaries",
  "threads",
  "offline",
  "meta",
  "queue",
  "contacts",
  "drafts",
  "composers",
  "keys",
  "device",
  USAGE,
];
// Bump when a cached Gmail copy changes shape; an upgrade empties `REFETCHABLE`.
const VERSION = 7;
// Copies of Gmail data, safe to drop and fetch again. Never the queues or drafts.
const REFETCHABLE: ReadonlySet<string> = new Set<StoreName>([
  "lists",
  "summaries",
  "threads",
  "offline",
]);

/** Saved-copy state in `meta` that describes the refetchable stores, so an upgrade drops it with them. */
export const OFFLINE_META = {
  /** the saved copy's history id */
  historyId: "offlineHistoryId",
  /** whether a rebuild filled the saved copy */
  filled: "offlineFilled",
  /** threads whose read Gmail refused, read again at the next sync */
  retry: "offlineRetry",
  /** the tab that last changed the saved copy, so others know to read it again */
  holder: "offlineHolder",
  /** Gmail's drafts as the saved copy last listed them */
  drafts: "offlineDrafts",
  /** drafts the device knows went that the saved copy still holds */
  sentDrafts: "offlineSentDrafts",
} as const;

function promised<Value>(request: IDBRequest<Value>): Promise<Value> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

// Resolves once the transaction's writes are durable, not just queued.
function completed(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () =>
      reject(transaction.error ?? new DOMException("Aborted", "AbortError"));
  });
}

function usageRange(name: StoreName): IDBKeyRange {
  // Arrays sort after every other key type, so `[name, []]` is past every `[name, key]`.
  return IDBKeyRange.bound([name], [name, []]);
}

/** What an `Idb` tells its owner about other tabs. */
export type IdbEvents = {
  /** Another tab's open waits for this one to close the database (an older version of the app). */
  onBlocked?: () => void;
  /** Another tab upgraded or deleted the database, so this one closed it. */
  onClosedElsewhere?: () => void;
};

/**
 * A small typed wrapper over one IndexedDB database. Once closed, here or because another tab
 * upgraded or deleted it, every call is a quiet no-op: reads find nothing, writes resolve.
 */
export class Idb {
  #database: Promise<IDBDatabase>;
  #closed = false;

  constructor(name: string, events: IdbEvents = {}) {
    this.#database = new Promise((resolve, reject) => {
      const request = indexedDB.open(name, VERSION);
      request.onupgradeneeded = () => {
        const database = request.result;
        const upgrade = request.transaction;
        for (const store of STORES) {
          if (!database.objectStoreNames.contains(store)) {
            database.createObjectStore(store);
          } else if (upgrade !== null && REFETCHABLE.has(store)) {
            upgrade.objectStore(store).clear();
          } else if (upgrade !== null && store === "meta") {
            const meta = upgrade.objectStore(store);
            for (const key of Object.values(OFFLINE_META)) meta.delete(key);
          }
        }
      };
      request.onsuccess = () => {
        const database = request.result;
        // Left open, this tab would hold up another's upgrade or sign-out delete for good.
        database.onversionchange = () => {
          database.close();
          if (this.#closed) return;
          this.#closed = true;
          events.onClosedElsewhere?.();
        };
        resolve(database);
      };
      request.onerror = () => reject(request.error);
      request.onblocked = () => events.onBlocked?.();
    });
  }

  /** Whether the database is closed, so calls do nothing. */
  get closed(): boolean {
    return this.#closed;
  }

  // The open database, or null once closed.
  async #open(): Promise<IDBDatabase | null> {
    if (this.#closed) return null;
    const database = await this.#database;
    return this.#closed ? null : database;
  }

  async #store(
    name: StoreName,
    mode: IDBTransactionMode,
  ): Promise<IDBObjectStore | null> {
    const database = await this.#open();
    return database?.transaction(name, mode).objectStore(name) ?? null;
  }

  /** The value under `key`, or undefined. */
  async get<Name extends StoreName>(
    name: Name,
    key: IDBValidKey,
  ): Promise<CacheSchema[Name] | undefined> {
    const store = await this.#store(name, "readonly");
    if (store === null) return undefined;
    return promised(
      store.get(key) as IDBRequest<CacheSchema[Name] | undefined>,
    );
  }

  /** Values under several keys, in the same order; missing keys give undefined. */
  async getMany<Name extends StoreName>(
    name: Name,
    keys: readonly IDBValidKey[],
  ): Promise<(CacheSchema[Name] | undefined)[]> {
    const store = await this.#store(name, "readonly");
    if (store === null) return keys.map(() => undefined);
    return Promise.all(
      keys.map((key) =>
        promised(store.get(key) as IDBRequest<CacheSchema[Name] | undefined>),
      ),
    );
  }

  /** Every key and value of a store, in key order. */
  async entries<Name extends StoreName>(
    name: Name,
  ): Promise<[IDBValidKey, CacheSchema[Name]][]> {
    const store = await this.#store(name, "readonly");
    if (store === null) return [];
    const [keys, values] = await Promise.all([
      promised(store.getAllKeys()),
      promised(store.getAll() as IDBRequest<CacheSchema[Name][]>),
    ]);
    return keys.map((key, index) => [key, values[index]!]);
  }

  /** Store `value` under `key`; resolves once written. */
  async put<Name extends StoreName>(
    name: Name,
    key: IDBValidKey,
    value: CacheSchema[Name],
  ): Promise<void> {
    await this.putMany(name, [[key, value]]);
  }

  /** Store several values in one transaction; resolves once written. */
  async putMany<Name extends StoreName>(
    name: Name,
    entries: readonly [IDBValidKey, CacheSchema[Name]][],
  ): Promise<void> {
    if (entries.length === 0) return;
    const transaction = await this.#writing(name);
    if (transaction === null) return;
    const store = transaction.objectStore(name);
    for (const [key, value] of entries) store.put(value, key);
    if (TRACKED.has(name)) {
      const usage = transaction.objectStore(USAGE);
      const now = Date.now();
      for (const [key] of entries) usage.put(now, [name, key]);
    }
    await completed(transaction);
  }

  /** Mark tracked entries as used now, so `prune` keeps them longer. */
  async touch(name: TrackedStore, keys: readonly IDBValidKey[]): Promise<void> {
    if (keys.length === 0) return;
    const database = await this.#open();
    if (database === null) return;
    const transaction = database.transaction(USAGE, "readwrite");
    const usage = transaction.objectStore(USAGE);
    const now = Date.now();
    for (const key of keys) usage.put(now, [name, key]);
    await completed(transaction);
  }

  /**
   * Drop a tracked store's entries that `expired` says are too old, then its least recently used
   * past `cap`, never those `kept` names; answers how many went. Tracked stores are keyed by strings.
   */
  async prune(
    name: TrackedStore,
    cap: number,
    expired?: (key: string, usedAt: number) => boolean,
    kept?: ReadonlySet<string>,
  ): Promise<number> {
    const database = await this.#open();
    if (database === null) return 0;
    const reading = database.transaction([name, USAGE], "readonly");
    const range = usageRange(name);
    const [keys, usageKeys, usedAts] = await Promise.all([
      promised(reading.objectStore(name).getAllKeys()),
      promised(reading.objectStore(USAGE).getAllKeys(range)),
      promised(
        reading.objectStore(USAGE).getAll(range) as IDBRequest<number[]>,
      ),
    ]);
    const usage = new Map<string, number>();
    usageKeys.forEach((usageKey, index) => {
      const [, key] = usageKey as [string, string];
      usage.set(key, usedAts[index]!);
    });
    const present = keys.map(String);
    const dropped = pruneKeys(present, usage, cap, expired, kept);
    const known = new Set(present);
    const orphans = [...usage.keys()].filter((key) => !known.has(key));
    if (this.#closed || (dropped.length === 0 && orphans.length === 0)) {
      return 0;
    }
    const writing = database.transaction([name, USAGE], "readwrite");
    for (const key of dropped) writing.objectStore(name).delete(key);
    for (const key of [...dropped, ...orphans]) {
      writing.objectStore(USAGE).delete([name, key]);
    }
    await completed(writing);
    return dropped.length;
  }

  /**
   * Drop the values of `name` that no key of `owner` names and that weren't written or used since
   * `before`, in one transaction, so a value saved meanwhile (here or in another tab) is never lost.
   */
  async dropUnowned(
    name: TrackedStore,
    owner: StoreName,
    before: number,
  ): Promise<void> {
    const database = await this.#open();
    if (database === null || this.#closed) return;
    const transaction = database.transaction([name, owner, USAGE], "readwrite");
    const done = completed(transaction);
    const values = transaction.objectStore(name);
    const usage = transaction.objectStore(USAGE);
    const owned = transaction.objectStore(owner).getAllKeys();
    owned.onsuccess = () => {
      const keep = new Set(owned.result.map(String));
      const present = values.getAllKeys();
      present.onsuccess = () => {
        for (const key of present.result) {
          if (keep.has(String(key))) continue;
          const used = usage.get([name, key]);
          used.onsuccess = () => {
            if (typeof used.result === "number" && used.result >= before)
              return;
            values.delete(key);
            usage.delete([name, key]);
          };
        }
      };
    };
    await done;
  }

  /** How many values a store holds. */
  async count(name: StoreName): Promise<number> {
    const store = await this.#store(name, "readonly");
    if (store === null) return 0;
    return promised(store.count());
  }

  /** Remove the values under several keys in one transaction; resolves once removed. */
  async deleteMany(
    name: StoreName,
    keys: readonly IDBValidKey[],
  ): Promise<void> {
    if (keys.length === 0) return;
    const transaction = await this.#writing(name);
    if (transaction === null) return;
    const store = transaction.objectStore(name);
    for (const key of keys) store.delete(key);
    if (TRACKED.has(name)) {
      const usage = transaction.objectStore(USAGE);
      for (const key of keys) usage.delete([name, key]);
    }
    await completed(transaction);
  }

  /** Remove the value under `key`; resolves once removed. */
  async delete(name: StoreName, key: IDBValidKey): Promise<void> {
    await this.deleteMany(name, [key]);
  }

  async #writing(name: StoreName): Promise<IDBTransaction | null> {
    const database = await this.#open();
    return (
      database?.transaction(
        TRACKED.has(name) ? [name, USAGE] : name,
        "readwrite",
      ) ?? null
    );
  }

  /** Close the database; calls after this, and those still waiting for it to open, do nothing. */
  async close(): Promise<void> {
    this.#closed = true;
    const database = await this.#database.catch(() => null);
    database?.close();
  }
}

/** Delete a whole cache database, as on sign-out. */
export function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => resolve();
  });
}
