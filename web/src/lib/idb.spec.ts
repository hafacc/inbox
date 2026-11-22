import { afterEach, expect, test } from "bun:test";
import { Idb } from "./idb";

type FakeTransaction = {
  stores: string[];
  writes: [string, string, unknown][];
  oncomplete: (() => void) | null;
  onerror: (() => void) | null;
  onabort: (() => void) | null;
  error: DOMException | null;
  objectStore: (name: string) => unknown;
};

type FakeDatabase = {
  closed: boolean;
  onversionchange: (() => void) | null;
};

type FakeOpenRequest = {
  onblocked: (() => void) | null;
};

const opened: FakeTransaction[] = [];
const original = globalThis.indexedDB;

// Just enough of IndexedDB to see when a write's promise settles.
function installFake(): { database: FakeDatabase; request: FakeOpenRequest } {
  const database = {
    closed: false,
    onversionchange: null as (() => void) | null,
    objectStoreNames: { contains: () => true },
    transaction(names: string | string[]): FakeTransaction {
      const transaction: FakeTransaction = {
        stores: [names].flat(),
        writes: [],
        oncomplete: null,
        onerror: null,
        onabort: null,
        error: null,
        objectStore: (name) => ({
          put(value: unknown, key: unknown) {
            transaction.writes.push(["put", `${name}:${String(key)}`, value]);
            return {};
          },
          delete(key: unknown) {
            transaction.writes.push(["delete", `${name}:${String(key)}`, null]);
            return {};
          },
        }),
      };
      opened.push(transaction);
      return transaction;
    },
    close() {
      database.closed = true;
    },
  };
  const request = {
    result: database,
    onsuccess: null as (() => void) | null,
    onerror: null,
    onupgradeneeded: null,
    onblocked: null as (() => void) | null,
  };
  globalThis.indexedDB = {
    open() {
      queueMicrotask(() => request.onsuccess?.());
      return request;
    },
  } as unknown as IDBFactory;
  return { database, request };
}

async function transactionOpened(): Promise<void> {
  for (let tries = 0; opened.length === 0 && tries < 100; tries++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  expect(opened.length).toBeGreaterThan(0);
}

afterEach(() => {
  globalThis.indexedDB = original;
  opened.length = 0;
});

test("a write resolves only once its transaction completes", async () => {
  installFake();
  const cache = new Idb("test");
  let done = false;
  const writing = cache.put("meta", "labels", []).then(() => {
    done = true;
  });
  await transactionOpened();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(done).toBe(false);
  opened[0]!.oncomplete?.();
  await writing;
  expect(done).toBe(true);
});

test("a write fails when its transaction aborts", async () => {
  installFake();
  const cache = new Idb("test");
  const writing = cache.deleteMany("meta", ["a", "b"]);
  await transactionOpened();
  opened[0]!.error = new DOMException("full", "QuotaExceededError");
  opened[0]!.onabort?.();
  await expect(writing).rejects.toThrow("full");
});

test("writes to a tracked store note when each entry was used", async () => {
  installFake();
  const cache = new Idb("test");
  const writing = cache.putMany("threads", [["t1", {} as never]]);
  await transactionOpened();
  const [transaction] = opened;
  expect(transaction!.stores).toEqual(["threads", "usage"]);
  expect(transaction!.writes.map(([kind, key]) => [kind, key])).toEqual([
    ["put", "threads:t1"],
    ["put", "usage:threads,t1"],
  ]);
  transaction!.oncomplete?.();
  await writing;
});

test("calls after close do nothing", async () => {
  const { database } = installFake();
  const cache = new Idb("test");
  const reading = cache.get("meta", "labels");
  await cache.close();
  expect(database.closed).toBe(true);
  expect(await reading).toBeUndefined();
  expect(await cache.entries("queue")).toEqual([]);
  expect(await cache.count("queue")).toBe(0);
  await cache.put("meta", "labels", []);
  await cache.deleteMany("queue", [1]);
  expect(opened).toEqual([]);
});

test("another tab's upgrade or delete closes the database and says so", async () => {
  const { database } = installFake();
  let closedElsewhere = 0;
  const cache = new Idb("test", {
    onClosedElsewhere: () => closedElsewhere++,
  });
  await cache.count("meta").catch(() => {});
  expect(database.onversionchange).not.toBeNull();
  database.onversionchange?.();
  expect(database.closed).toBe(true);
  expect(cache.closed).toBe(true);
  expect(closedElsewhere).toBe(1);
  opened.length = 0;
  await cache.put("meta", "labels", []);
  expect(opened).toEqual([]);
});

test("closing here doesn't count as closed elsewhere", async () => {
  const { database } = installFake();
  let closedElsewhere = 0;
  const cache = new Idb("test", {
    onClosedElsewhere: () => closedElsewhere++,
  });
  const closing = cache.close();
  await Promise.resolve();
  expect(database.onversionchange).not.toBeNull();
  database.onversionchange?.();
  await closing;
  expect(closedElsewhere).toBe(0);
});

test("an open another tab holds up is reported", () => {
  const { request } = installFake();
  let blocked = 0;
  new Idb("test", { onBlocked: () => blocked++ });
  request.onblocked?.();
  expect(blocked).toBe(1);
});
