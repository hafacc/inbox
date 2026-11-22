// Unsent work kept through a sign-out forced by lost Gmail access (docs/compose-contract.md,
// `blockedOn: "sign-in"`): queued label changes, queued label and filter edits, label renames the
// todos' tags haven't followed yet, queued draft saves with their composers, and thrown-away drafts
// whose Gmail delete hasn't gone through. The rest of the account's data is cleared as on any
// sign-out.
// Signing back in to the same account puts the work back; another account, or a deliberate
// sign-out, drops it.

import type { AccountChange } from "@inbox/shared/account-queue";
import { ACCOUNT_QUEUE_KEY } from "./account-queue";
import { isPendingDelete } from "./compose/composer-store";
import { type CacheSchema, Idb } from "./idb";
import { DEVICE_RECORD_KEY } from "./pgp/device-key";

/** The database the parked work waits in, which a forced sign-out leaves in place. */
export const PARKED_DATABASE = "inbox:parked";
const PARKED_RECORD_KEY = "account";

/** Key in the account's `meta` store of the device key kept from before a forced sign-out. */
export const PARKED_WRAPPING_KEY = "parkedWrappingKey";

/** Key in the account's `meta` store of label renames whose todo tags haven't followed yet, oldest first. */
export const RETAGS_KEY = "todoRetags";

/** A label rename the todos' tags follow. */
export type TagRename = {
  /** the label's name before */
  from: string;
  /** its new name */
  to: string;
};

/** What a forced sign-out keeps for one account; records stay as stored (sealed or not). */
export type ParkedAccount = {
  uid: string;
  /** the device key that sealed the records, kept so they open after the next unlock */
  wrappingKey: CryptoKey | null;
  /** queued draft saves, by composer id */
  drafts: [string, unknown][];
  /** the composers of those saves, and drafts whose Gmail delete is still to go */
  composers: [string, unknown][];
  /** queued label changes, by id */
  queue: [string, CacheSchema["queue"]][];
  /** queued label and filter edits, in the order made */
  accountQueue: AccountChange[];
  /** label renames the todos' tags haven't followed yet, oldest first */
  retags: TagRename[];
};

type ParkedStore = "drafts" | "composers" | "queue" | "device" | "meta";

/** The parts of the account's cache that parking reads and restoring writes. */
export type AccountStores = {
  /** every key and value of a store */
  entries(name: ParkedStore): Promise<[IDBValidKey, unknown][]>;
  /** one value */
  get(name: ParkedStore, key: IDBValidKey): Promise<unknown>;
  /** store a value */
  put(name: ParkedStore, key: IDBValidKey, value: unknown): Promise<void>;
};

/** Where the parked work waits between accounts: one value. */
export type ParkingSlot = {
  /** the parked work, or undefined */
  get(): Promise<ParkedAccount | undefined>;
  /** keep parked work */
  put(value: ParkedAccount): Promise<void>;
  /** drop it */
  delete(): Promise<void>;
};

function isSealed(value: unknown): boolean {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as { sealed?: unknown }).sealed instanceof Uint8Array
  );
}

/** Keep an account's unsent work in `slot`; with none, the slot is emptied. */
export async function park(
  uid: string,
  stores: AccountStores,
  slot: ParkingSlot,
): Promise<void> {
  const drafts = (await stores.entries("drafts")).map(
    ([key, value]) => [String(key), value] as [string, unknown],
  );
  const queued = new Set(drafts.map(([key]) => key));
  // Composers of saves that went already are only drafts in Gmail; they aren't kept.
  const composers = (await stores.entries("composers"))
    .map(([key, value]) => [String(key), value] as [string, unknown])
    .filter(([key, value]) => queued.has(key) || isPendingDelete(value));
  const queue = (await stores.entries("queue")).map(
    ([key, value]) =>
      [String(key), value as CacheSchema["queue"]] as [
        string,
        CacheSchema["queue"],
      ],
  );
  const storedAccountQueue = await stores.get("meta", ACCOUNT_QUEUE_KEY);
  const accountQueue = Array.isArray(storedAccountQueue)
    ? (storedAccountQueue as AccountChange[])
    : [];
  const storedRetags = await stores.get("meta", RETAGS_KEY);
  const retags = Array.isArray(storedRetags)
    ? (storedRetags as TagRename[])
    : [];
  if (
    drafts.length === 0 &&
    composers.length === 0 &&
    queue.length === 0 &&
    accountQueue.length === 0 &&
    retags.length === 0
  ) {
    await slot.delete();
    return;
  }
  const sealed = [...drafts, ...composers].some(([, value]) => isSealed(value));
  const device = sealed
    ? ((await stores.get("device", DEVICE_RECORD_KEY)) as
        | { wrappingKey?: CryptoKey }
        | undefined)
    : undefined;
  const wrappingKey =
    device?.wrappingKey ??
    (sealed
      ? (((await stores.get("meta", PARKED_WRAPPING_KEY)) as
          | CryptoKey
          | undefined) ?? null)
      : null);
  await slot.put({
    uid,
    wrappingKey,
    drafts,
    composers,
    queue,
    accountQueue,
    retags,
  });
}

/**
 * Put back the work parked for `uid` and empty the slot; work parked for another account is
 * dropped. Resolves whether anything came back.
 */
export async function unpark(
  uid: string,
  stores: AccountStores,
  slot: ParkingSlot,
): Promise<boolean> {
  const parked = await slot.get();
  if (parked === undefined) return false;
  if (parked.uid !== uid) {
    await slot.delete();
    return false;
  }
  for (const [key, value] of parked.drafts) {
    await stores.put("drafts", key, value);
  }
  for (const [key, value] of parked.composers) {
    await stores.put("composers", key, value);
  }
  for (const [key, value] of parked.queue) {
    await stores.put("queue", key, value);
  }
  if (parked.accountQueue.length > 0) {
    await stores.put("meta", ACCOUNT_QUEUE_KEY, parked.accountQueue);
  }
  if (parked.retags.length > 0) {
    await stores.put("meta", RETAGS_KEY, parked.retags);
  }
  if (parked.wrappingKey !== null) {
    await stores.put("meta", PARKED_WRAPPING_KEY, parked.wrappingKey);
  }
  await slot.delete();
  return true;
}

/** The parking slot in this browser. */
export function parkingSlot(): ParkingSlot {
  const open = () => new Idb(PARKED_DATABASE);
  const withDatabase = async <Result>(
    run: (database: Idb) => Promise<Result>,
  ): Promise<Result> => {
    const database = open();
    try {
      return await run(database);
    } finally {
      await database.close().catch(() => {});
    }
  };
  return {
    get: () =>
      withDatabase(
        async (database) =>
          (await database.get("meta", PARKED_RECORD_KEY)) as
            | ParkedAccount
            | undefined,
      ),
    put: (value) =>
      withDatabase((database) =>
        database.put("meta", PARKED_RECORD_KEY, value),
      ),
    delete: () =>
      withDatabase((database) => database.delete("meta", PARKED_RECORD_KEY)),
  };
}
