import { beforeEach, describe, expect, it } from "bun:test";
import {
  type AccountRecords,
  DELETED_MARK_MS,
  type DeletionDeps,
  deleteAccount,
  type GoogleGrant,
  type StoredSend,
} from "./delete-account.js";
import { outboxFromDoc, sendTaskId } from "./outbox-task.js";
import { type KeyStore, kvKeyFor } from "./wkd-keys.js";

const UID = "google:1";
const NOW = 1_800_000_000_000;
const HOUR = 60 * 60 * 1000;
const ADDRESS = "test@hafa.cc";

class MemoryRecords implements AccountRecords {
  addresses = new Map<string, string>();
  outbox = new Map<string, StoredSend[]>();
  deleted: string[] = [];
  marked = new Map<string, number>();

  async markDeleted(uid: string, until: number) {
    this.marked.set(uid, until);
  }
  async address(uid: string) {
    return this.addresses.get(uid);
  }
  async sends(uid: string) {
    return this.outbox.get(uid) ?? [];
  }
  async deleteAll(uid: string) {
    this.addresses.delete(uid);
    this.outbox.delete(uid);
    this.deleted.push(uid);
  }
}

class FakeGoogle implements GoogleGrant {
  tokens = new Map<string, string>();
  revoked = new Set<string>();
  calls: string[] = [];

  async refreshToken(uid: string) {
    return this.tokens.get(uid);
  }
  async accessToken(refreshToken: string) {
    this.calls.push(`refresh ${refreshToken}`);
    return this.revoked.has(refreshToken)
      ? undefined
      : `access-${refreshToken}`;
  }
  async stopWatch(accessToken: string) {
    this.calls.push(`stop ${accessToken}`);
  }
  async revoke(refreshToken: string) {
    this.calls.push(`revoke ${refreshToken}`);
    if (this.revoked.has(refreshToken)) return "already-revoked" as const;
    this.revoked.add(refreshToken);
    return "revoked" as const;
  }
}

class MemoryKeys implements KeyStore {
  entries = new Map<string, Uint8Array>();
  deletes: string[] = [];

  async put(name: string, value: Uint8Array) {
    this.entries.set(name, value);
  }
  async delete(name: string) {
    this.deletes.push(name);
    this.entries.delete(name);
  }
  async list(prefix: string) {
    return [...this.entries.keys()]
      .filter((name) => name.startsWith(prefix))
      .map((name) => ({ name }));
  }
}

class RecordingQueue {
  removed: string[] = [];

  async remove(id: string) {
    this.removed.push(id);
  }
}

let records: MemoryRecords;
let google: FakeGoogle;
let keys: MemoryKeys;
let sendQueue: RecordingQueue;
let held: Map<string, string>;
let users: Set<string>;
let deps: DeletionDeps;

function waitingSend(outboxId: string): StoredSend {
  return {
    outboxId,
    entry: outboxFromDoc({
      status: "waiting",
      draftId: `draft-${outboxId}`,
      from: "me@gmail.example.com",
      sendAt: { toMillis: () => NOW + HOUR },
    }),
    writtenAt: NOW - HOUR,
  };
}

beforeEach(async () => {
  records = new MemoryRecords();
  google = new FakeGoogle();
  keys = new MemoryKeys();
  sendQueue = new RecordingQueue();
  held = new Map([[UID, ADDRESS]]);
  users = new Set([UID]);
  records.addresses.set(UID, ADDRESS);
  records.outbox.set(UID, [
    waitingSend("o1"),
    {
      ...waitingSend("o2"),
      entry: { ...waitingSend("o2").entry!, status: "sent" },
    },
  ]);
  google.tokens.set(UID, "refresh-1");
  keys.entries.set(await kvKeyFor(ADDRESS), new Uint8Array([1]));
  deps = {
    records,
    google,
    async retireAddress(uid) {
      const address = held.get(uid);
      held.delete(uid);
      return address;
    },
    keys: () => keys,
    sendQueue,
    async deleteUser(uid) {
      users.delete(uid);
    },
    now: () => NOW,
    log: { info: () => {}, warn: () => {} },
  };
});

describe("deleteAccount", () => {
  it("removes everything the server holds", async () => {
    expect(await deleteAccount(deps, UID)).toEqual({ deleted: true });
    expect(sendQueue.removed).toEqual([
      sendTaskId({
        uid: UID,
        outboxId: "o1",
        sendAt: NOW + HOUR,
        writtenAt: NOW - HOUR,
        hop: 1,
        attempt: 0,
      }),
    ]);
    expect(keys.entries.size).toBe(0);
    expect(held.size).toBe(0);
    expect(google.calls).toEqual([
      "refresh refresh-1",
      "stop access-refresh-1",
      "revoke refresh-1",
    ]);
    expect(records.deleted).toEqual([UID]);
    expect(records.marked.get(UID)).toBe(NOW + DELETED_MARK_MS);
    expect(users.size).toBe(0);
  });

  it("closes the account to writes before reading or stopping anything", async () => {
    const markedBefore: string[] = [];
    const sends = records.sends.bind(records);
    records.sends = async (uid) => {
      if (records.marked.has(uid)) markedBefore.push("sends");
      return sends(uid);
    };
    const retire = deps.retireAddress;
    deps.retireAddress = async (uid) => {
      if (records.marked.has(uid)) markedBefore.push("address");
      return retire(uid);
    };
    await deleteAccount(deps, UID);
    expect(markedBefore).toEqual(["sends", "address"]);
  });

  it("runs again cleanly once everything is gone", async () => {
    await deleteAccount(deps, UID);
    google.tokens.clear();
    google.calls = [];
    sendQueue.removed = [];
    keys.deletes = [];
    expect(await deleteAccount(deps, UID)).toEqual({ deleted: true });
    expect(google.calls).toEqual([]);
    expect(sendQueue.removed).toEqual([]);
    expect(keys.deletes).toEqual([]);
    expect(records.deleted).toEqual([UID, UID]);
  });

  it("resumes after a failure partway", async () => {
    const deleteAll = records.deleteAll.bind(records);
    records.deleteAll = async () => {
      throw new Error("firestore unavailable");
    };
    await expect(deleteAccount(deps, UID)).rejects.toThrow(
      "firestore unavailable",
    );
    records.deleteAll = deleteAll;
    google.calls = [];
    expect(await deleteAccount(deps, UID)).toEqual({ deleted: true });
    expect(google.calls).toEqual(["refresh refresh-1"]);
    expect(records.deleted).toEqual([UID]);
    expect(users.size).toBe(0);
  });

  it("skips the watch and revocation when Google already revoked the grant", async () => {
    google.revoked.add("refresh-1");
    await deleteAccount(deps, UID);
    expect(google.calls).toEqual(["refresh refresh-1"]);
    expect(records.deleted).toEqual([UID]);
    expect(users.size).toBe(0);
  });

  it("still revokes when stopping the watch fails", async () => {
    google.stopWatch = async () => {
      throw new Error("403");
    };
    await deleteAccount(deps, UID);
    expect(google.revoked.has("refresh-1")).toBe(true);
  });

  it("works for a user with no address", async () => {
    held.clear();
    records.addresses.clear();
    await deleteAccount(deps, UID);
    expect(keys.deletes).toEqual([]);
    expect(keys.entries.size).toBe(1);
    expect(google.revoked.has("refresh-1")).toBe(true);
    expect(records.deleted).toEqual([UID]);
    expect(users.size).toBe(0);
  });

  it("works for an account that only ever signed in for todos", async () => {
    held.clear();
    records.addresses.clear();
    records.outbox.clear();
    google.tokens.clear();
    keys.entries.clear();
    expect(await deleteAccount(deps, UID)).toEqual({ deleted: true });
    expect(google.calls).toEqual([]);
    expect(sendQueue.removed).toEqual([]);
    expect(records.deleted).toEqual([UID]);
    expect(users.size).toBe(0);
  });

  it("carries on past a task it can't delete", async () => {
    records.outbox.set(UID, [waitingSend("o1"), waitingSend("o3")]);
    const remove = sendQueue.remove.bind(sendQueue);
    let failed = false;
    sendQueue.remove = async (id) => {
      if (!failed) {
        failed = true;
        throw new Error("queue down");
      }
      await remove(id);
    };
    await deleteAccount(deps, UID);
    expect(sendQueue.removed.length).toBe(1);
    expect(users.size).toBe(0);
  });

  it("leaves Workers KV alone when it isn't set up", async () => {
    deps.keys = () => undefined;
    await deleteAccount(deps, UID);
    expect(keys.entries.size).toBe(1);
    expect(held.size).toBe(0);
  });
});
