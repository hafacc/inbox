import { expect, test } from "bun:test";
import type { AccountChange } from "@inbox/shared/account-queue";
import { ACCOUNT_QUEUE_KEY } from "./account-queue";
import {
  type AccountStores,
  PARKED_WRAPPING_KEY,
  type ParkedAccount,
  type ParkingSlot,
  park,
  RETAGS_KEY,
  unpark,
} from "./parked";
import { DEVICE_RECORD_KEY, newWrappingKey } from "./pgp/device-key";

function memoryStores(): AccountStores & {
  data: Map<string, Map<IDBValidKey, unknown>>;
} {
  const data = new Map<string, Map<IDBValidKey, unknown>>();
  const store = (name: string) => {
    let found = data.get(name);
    if (found === undefined) {
      found = new Map();
      data.set(name, found);
    }
    return found;
  };
  return {
    data,
    entries: async (name) => [...store(name)],
    get: async (name, key) => store(name).get(key),
    put: async (name, key, value) => {
      store(name).set(key, value);
    },
  };
}

function memorySlot(): ParkingSlot & { value: ParkedAccount | undefined } {
  const slot = {
    value: undefined as ParkedAccount | undefined,
    get: async () => slot.value,
    put: async (value: ParkedAccount) => {
      slot.value = value;
    },
    delete: async () => {
      slot.value = undefined;
    },
  };
  return slot;
}

const change = {
  id: "c3",
  seq: 3,
  threadId: "t1",
  add: [],
  remove: ["INBOX"],
};
const accountChanges: AccountChange[] = [
  { seq: 1, kind: "createLabel", labelId: "local:l1", name: "Trips" },
  { seq: 2, kind: "patchLabel", labelId: "local:l1", name: "Travel" },
];

test("a forced sign-out keeps queued work and the device key that sealed it, for the same account", async () => {
  const wrappingKey = await newWrappingKey();
  const before = memoryStores();
  const sealedJob = { sealed: new Uint8Array([1, 2, 3]) };
  await before.put("drafts", "c1", sealedJob);
  await before.put("composers", "c1", { sealed: new Uint8Array([4]) });
  // A composer with nothing queued is only a draft in Gmail by now.
  await before.put("composers", "c2", { sealed: new Uint8Array([5]) });
  await before.put("queue", change.id, change);
  await before.put("device", DEVICE_RECORD_KEY, { wrappingKey });
  const slot = memorySlot();
  await park("uid-1", before, slot);
  expect(slot.value?.composers.map(([key]) => key)).toEqual(["c1"]);
  expect(slot.value?.wrappingKey).toBe(wrappingKey);

  const after = memoryStores();
  expect(await unpark("uid-1", after, slot)).toBe(true);
  expect(await after.get("drafts", "c1")).toEqual(sealedJob);
  expect(await after.get("queue", change.id)).toEqual(change);
  expect(await after.get("composers", "c2")).toBeUndefined();
  expect(await after.get("meta", PARKED_WRAPPING_KEY)).toBe(wrappingKey);
  expect(slot.value).toBeUndefined();
});

test("a forced sign-out keeps queued label and filter edits on their own", async () => {
  const before = memoryStores();
  await before.put("meta", ACCOUNT_QUEUE_KEY, accountChanges);
  const slot = memorySlot();
  await park("uid-1", before, slot);
  expect(slot.value?.accountQueue).toEqual(accountChanges);

  const after = memoryStores();
  expect(await unpark("uid-1", after, slot)).toBe(true);
  expect(await after.get("meta", ACCOUNT_QUEUE_KEY)).toEqual(accountChanges);
});

test("a forced sign-out keeps label renames the todos' tags haven't followed yet", async () => {
  const retags = [
    { from: "Trips", to: "Travel" },
    { from: "Travel", to: "Away" },
  ];
  const before = memoryStores();
  await before.put("meta", RETAGS_KEY, retags);
  const slot = memorySlot();
  await park("uid-1", before, slot);
  expect(slot.value?.retags).toEqual(retags);

  const after = memoryStores();
  expect(await unpark("uid-1", after, slot)).toBe(true);
  expect(await after.get("meta", RETAGS_KEY)).toEqual(retags);
});

test("another account signing in drops the parked work", async () => {
  const before = memoryStores();
  await before.put("queue", change.id, change);
  const slot = memorySlot();
  await park("uid-1", before, slot);
  expect(slot.value?.wrappingKey).toBeNull();
  const other = memoryStores();
  expect(await unpark("uid-2", other, slot)).toBe(false);
  expect(other.data.size).toBe(0);
  expect(slot.value).toBeUndefined();
});

test("with nothing unsent, nothing is parked", async () => {
  const slot = memorySlot();
  slot.value = {
    uid: "old",
    wrappingKey: null,
    drafts: [],
    composers: [],
    queue: [],
    accountQueue: [],
    retags: [],
  };
  const stores = memoryStores();
  await stores.put("composers", "c1", { markdown: "hi" });
  await park("uid-1", stores, slot);
  expect(slot.value).toBeUndefined();
});

test("a forced sign-out keeps drafts whose Gmail delete hasn't gone through", async () => {
  const pending = {
    localId: "c3",
    draftId: "d3",
    messageId: "m3",
    threadId: "t3",
    deleted: true,
  };
  const before = memoryStores();
  await before.put("composers", "c3", pending);
  await before.put("composers", "c4", { markdown: "hi" });
  const slot = memorySlot();
  await park("uid-1", before, slot);
  expect(slot.value?.composers).toEqual([["c3", pending]]);

  const after = memoryStores();
  expect(await unpark("uid-1", after, slot)).toBe(true);
  expect(await after.get("composers", "c3")).toEqual(pending);
  expect(await after.get("composers", "c4")).toBeUndefined();
});
