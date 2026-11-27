import { describe, expect, it } from "bun:test";
import { newWrappingKey } from "../pgp/device-key";
import type { ComposeSnapshot } from "./compose-model";
import {
  ComposerStore,
  type ComposeStores,
  type PendingDelete,
  type Protection,
} from "./composer-store";
import type { DraftJob } from "./draft-queue";

function memoryStores(): ComposeStores & { raw: Map<string, unknown> } {
  const raw = new Map<string, unknown>();
  return {
    raw,
    get: async (name, key) => raw.get(`${name}/${key}`) as never,
    entries: async (name) =>
      [...raw]
        .filter(([key]) => key.startsWith(`${name}/`))
        .map(([key, value]) => [key.slice(name.length + 1), value]) as never,
    put: async (name, key, value) => {
      raw.set(`${name}/${key}`, value);
    },
    delete: async (name, key) => {
      raw.delete(`${name}/${key}`);
    },
  };
}

const snapshot: ComposeSnapshot = {
  localId: "c1",
  mode: "reply",
  threadId: "t1",
  draftId: null,
  messageId: null,
  from: "user@hafa.cc",
  to: [{ name: "Ana", email: "ana@example.com" }],
  cc: [],
  bcc: [],
  subject: "Re: secret",
  markdown: "Quoting the decrypted text",
  inReplyTo: null,
  references: [],
  attachments: [
    {
      id: "a1",
      filename: "a.bin",
      mimeType: "application/octet-stream",
      data: new Uint8Array([0, 1, 254, 255]),
    },
  ],
  inlineImages: [],
  quote: null,
};

const job: DraftJob = {
  localId: "c1",
  draftId: null,
  threadId: "t1",
  from: "user@hafa.cc",
  raw: "secret raw",
  version: 1,
  send: null,
};

describe("ComposerStore", () => {
  it("keeps contents as they are when the account has no key", async () => {
    const stores = memoryStores();
    const store = new ComposerStore(stores, async () => ({ kind: "plain" }));
    await store.putComposer(snapshot);
    expect(stores.raw.get("composers/c1")).toEqual(snapshot);
    expect(await store.composer("c1")).toEqual(snapshot);
  });

  it("seals contents and queued saves under the device key", async () => {
    const stores = memoryStores();
    const key = await newWrappingKey();
    const store = new ComposerStore(stores, async () => ({
      kind: "sealed",
      key,
    }));
    await store.putComposer(snapshot);
    await store.putJob(job);
    const stored = JSON.stringify([...stores.raw.values()], (_name, value) =>
      value instanceof Uint8Array ? new TextDecoder().decode(value) : value,
    );
    expect(stored).not.toContain("decrypted");
    expect(stored).not.toContain("secret");
    expect(await store.composer("c1")).toEqual(snapshot);
    expect(await store.composers()).toEqual([["c1", snapshot]]);
    expect(await store.jobs()).toEqual([["c1", job]]);
  });

  it("writes nothing while the key is locked, and drops what it can't open", async () => {
    const stores = memoryStores();
    let protection: Protection = {
      kind: "sealed",
      key: await newWrappingKey(),
    };
    const store = new ComposerStore(stores, async () => protection);
    await store.putComposer(snapshot);
    protection = { kind: "locked" };
    await store.putJob(job);
    expect(stores.raw.has("drafts/c1")).toBe(false);
    expect(await store.composers()).toEqual([]);
    expect(stores.raw.has("composers/c1")).toBe(true);
    protection = { kind: "sealed", key: await newWrappingKey() };
    expect(await store.composers()).toEqual([]);
    expect(stores.raw.has("composers/c1")).toBe(false);
  });

  it("seals what was kept before the account had a key", async () => {
    const stores = memoryStores();
    await stores.put("composers", "c1", snapshot);
    const key = await newWrappingKey();
    const store = new ComposerStore(stores, async () => ({
      kind: "sealed",
      key,
    }));
    expect(await store.composers()).toEqual([["c1", snapshot]]);
    expect(stores.raw.get("composers/c1")).toHaveProperty("sealed");
  });

  it("keeps a pending delete in place of its composer, unsealed and readable while locked", async () => {
    const stores = memoryStores();
    let protection: Protection = {
      kind: "sealed",
      key: await newWrappingKey(),
    };
    const store = new ComposerStore(stores, async () => protection);
    await store.putComposer(snapshot);
    await store.putComposer({ ...snapshot, localId: "c2" });
    const pending: PendingDelete = {
      localId: "c1",
      draftId: "d1",
      messageId: "m1",
      threadId: "t1",
      deleted: true,
    };
    await store.putDelete(pending);
    expect(stores.raw.get("composers/c1")).toEqual(pending);
    expect(await store.composer("c1")).toBeUndefined();
    expect((await store.composers()).map(([key]) => key)).toEqual(["c2"]);
    protection = { kind: "locked" };
    expect(await store.pendingDeletes()).toEqual([pending]);
    expect(stores.raw.get("composers/c1")).toEqual(pending);
    await store.deleteComposer("c1");
    expect(await store.pendingDeletes()).toEqual([]);
  });

  it("keeps composers in memory while nothing may be written, and writes them once it may", async () => {
    const stores = memoryStores();
    let protection: Protection = { kind: "locked" };
    const changes: [string, unknown][] = [];
    const store = new ComposerStore(
      stores,
      async () => protection,
      (localId, kept) => changes.push([localId, kept]),
    );
    await store.putComposer(snapshot);
    await store.putJob(job);
    expect(stores.raw.has("composers/c1")).toBe(false);
    expect(stores.raw.has("drafts/c1")).toBe(false);
    expect(await store.composer("c1")).toEqual(snapshot);
    expect(await store.holds("c1")).toBe(true);
    expect(await store.composers()).toEqual([["c1", snapshot]]);
    expect(changes).toEqual([["c1", snapshot]]);
    protection = { kind: "plain" };
    expect(await store.composers()).toEqual([["c1", snapshot]]);
    expect(await store.jobs()).toEqual([["c1", job]]);
    expect(stores.raw.get("composers/c1")).toEqual(snapshot);
    expect(stores.raw.get("drafts/c1")).toEqual(job);
  });

  it("says each change once it lands, in the order asked", async () => {
    const stores = memoryStores();
    const changes: [string, unknown][] = [];
    let release = () => {};
    const slow = new Promise<void>((resolve) => {
      release = resolve;
    });
    let first = true;
    const store = new ComposerStore(
      stores,
      async () => {
        if (first) {
          first = false;
          await slow;
        }
        return { kind: "plain" };
      },
      (localId, kept) => changes.push([localId, kept]),
    );
    // The write waits on the key; the delete asked after it still lands after it.
    const written = store.putComposer(snapshot);
    const deleted = store.deleteComposer("c1");
    release();
    await Promise.all([written, deleted]);
    expect(stores.raw.has("composers/c1")).toBe(false);
    expect(await store.composers()).toEqual([]);
    expect(changes).toEqual([
      ["c1", snapshot],
      ["c1", null],
    ]);
  });

  it("changes a kept composer in place, and writes nothing when none is kept", async () => {
    const stores = memoryStores();
    const store = new ComposerStore(stores, async () => ({ kind: "plain" }));
    const withIds = (kept: ComposeSnapshot) => ({
      ...kept,
      draftId: "d1",
      messageId: "m1",
    });
    expect(await store.updateComposer("c1", withIds)).toBeUndefined();
    expect(stores.raw.has("composers/c1")).toBe(false);
    await store.putComposer(snapshot);
    expect(await store.updateComposer("c1", withIds)).toEqual(
      withIds(snapshot),
    );
    expect(await store.composer("c1")).toEqual(withIds(snapshot));
  });

  it("holds a record it can't open, and not a pending delete", async () => {
    const stores = memoryStores();
    await stores.put("composers", "c1", {
      sealed: new Uint8Array([1]),
    } as never);
    await stores.put("composers", "c2", {
      localId: "c2",
      draftId: "d2",
      messageId: null,
      threadId: null,
      deleted: true,
    });
    const store = new ComposerStore(stores, async () => ({ kind: "locked" }));
    expect(await store.composer("c1")).toBeUndefined();
    expect(await store.holds("c1")).toBe(true);
    expect(await store.holds("c2")).toBe(false);
    expect(await store.holds("c3")).toBe(false);
  });
});
