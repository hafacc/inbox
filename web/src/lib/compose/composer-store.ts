// What composing keeps on the device: composers' contents and draft saves waiting for Gmail. When
// the account has an encryption key, both are sealed under the device key, like decrypted mail in
// the offline cache (docs/pgp-contract.md "Offline cache and search").

import type { CacheSchema } from "../idb";
import { openValue, sealValue } from "../pgp/device-key";
import type { ComposeSnapshot } from "./compose-model";
import type { DraftJob } from "./draft-queue";

/** A record sealed under the device key. */
export type SealedRecord = { sealed: Uint8Array<ArrayBuffer> };

/**
 * A thrown-away draft whose Gmail copy is still to be deleted, kept in place of its composer. It
 * holds only Gmail ids, so it is kept as it is, readable before the device is unlocked.
 */
export type PendingDelete = {
  /** the composer's local id */
  localId: string;
  /** the Gmail draft to delete */
  draftId: string;
  /** the Gmail message id of the draft as last saved there, kept hidden from its thread */
  messageId: string | null;
  /** the thread a reply or forward belongs to */
  threadId: string | null;
  /** marks the record as a delete rather than a composer */
  deleted: true;
};

/** What the composers store keeps: a composer's contents, or a delete waiting for Gmail. */
export type KeptComposer = ComposeSnapshot | PendingDelete;

/** Whether a stored composer record is a pending delete. */
export function isPendingDelete(value: unknown): value is PendingDelete {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as Partial<PendingDelete>).deleted === true
  );
}

/** How records must be kept now. */
export type Protection =
  /** the account has no key: kept as they are */
  | { kind: "plain" }
  /** sealed under this device key */
  | { kind: "sealed"; key: CryptoKey }
  /** the account has a key this device hasn't unlocked: nothing is written */
  | { kind: "locked" };

/** The two stores composing uses, as the device cache gives them. */
export type ComposeStores = {
  /** a value, or undefined */
  get<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
  ): Promise<CacheSchema[Name] | undefined>;
  /** every key and value */
  entries<Name extends "composers" | "drafts">(
    name: Name,
  ): Promise<[IDBValidKey, CacheSchema[Name]][]>;
  /** store a value */
  put<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
    value: CacheSchema[Name],
  ): Promise<void>;
  /** remove a value */
  delete(name: "composers" | "drafts", key: string): Promise<void>;
};

type Kept<Name extends "composers" | "drafts"> = CacheSchema[Name];

function isSealed(value: unknown): value is SealedRecord {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as Partial<SealedRecord>).sealed instanceof Uint8Array
  );
}

/** A change to the kept composers: the record now kept for `localId`, or null when none is. */
export type ComposerChange = (
  localId: string,
  kept: KeptComposer | null,
) => void;

/**
 * Composers' contents and queued draft saves on the device, sealed when the account has a key.
 * While nothing may be written (the account has a key this device hasn't unlocked, or it isn't
 * known yet whether it has one), they are kept in memory for this session instead, and written
 * once they may be. Changes to one record run in the order they were asked for.
 */
export class ComposerStore {
  readonly #stores: ComposeStores;
  readonly #protection: () => Promise<Protection>;
  readonly #changed: ComposerChange;
  readonly #memory = {
    composers: new Map<string, KeptComposer>(),
    drafts: new Map<string, DraftJob>(),
  };
  // The last change asked for on each record, by store and key.
  readonly #queues = new Map<string, Promise<void>>();

  constructor(
    stores: ComposeStores,
    protection: () => Promise<Protection>,
    changed: ComposerChange = () => {},
  ) {
    this.#stores = stores;
    this.#protection = protection;
    this.#changed = changed;
  }

  // Run `change` once every earlier change to the same record is done.
  #queued<Result>(
    name: "composers" | "drafts",
    key: string,
    change: () => Promise<Result>,
  ): Promise<Result> {
    const id = `${name}/${key}`;
    const next = (this.#queues.get(id) ?? Promise.resolve()).then(change);
    const done = next.then(
      () => {},
      () => {},
    );
    this.#queues.set(id, done);
    void done.then(() => {
      if (this.#queues.get(id) === done) this.#queues.delete(id);
    });
    return next;
  }

  async #write<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
    value: Kept<Name>,
  ): Promise<void> {
    const protection = await this.#protection();
    const memory = this.#memory[name] as Map<string, Kept<Name>>;
    if (protection.kind === "plain") {
      await this.#stores.put(name, key, value);
      memory.delete(key);
    } else if (protection.kind === "sealed") {
      const sealed: SealedRecord = {
        sealed: await sealValue(protection.key, value),
      };
      await this.#stores.put(name, key, sealed as unknown as Kept<Name>);
      memory.delete(key);
    } else {
      memory.set(key, value);
      // The copy in memory is the one read from now on, whatever an older one on disk says.
      await this.#stores.delete(name, key).catch(() => {});
    }
  }

  // Write down what was kept in memory, once it may be written.
  async #persistMemory(
    name: "composers" | "drafts",
    protection: Protection,
  ): Promise<void> {
    if (protection.kind === "locked") return;
    const memory = this.#memory[name] as Map<string, Kept<typeof name>>;
    await Promise.all(
      [...memory].map(([key, value]) =>
        this.#queued(name, key, async () => {
          if (memory.get(key) === value) await this.#write(name, key, value);
        }).catch(() => {}),
      ),
    );
  }

  // A stored record as the caller sees it, or null when it can't be opened here.
  async #open<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
    stored: unknown,
    protection: Protection,
  ): Promise<Kept<Name> | null> {
    if (isPendingDelete(stored)) {
      return stored as Kept<Name>;
    } else if (!isSealed(stored)) {
      const plain = stored as Kept<Name>;
      // Kept before the account had a key: seal it now.
      if (protection.kind === "sealed") {
        await this.#write(name, key, plain).catch(() => {});
      }
      return plain;
    }
    if (protection.kind !== "sealed") return null;
    try {
      return await openValue<Kept<Name>>(protection.key, stored.sealed);
    } catch {
      // Sealed under a device key this device no longer has.
      await this.#stores.delete(name, key).catch(() => {});
      return null;
    }
  }

  async #read<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
  ): Promise<Kept<Name> | undefined> {
    const held = (this.#memory[name] as Map<string, Kept<Name>>).get(key);
    if (held !== undefined) return held;
    const stored = await this.#stores.get(name, key);
    if (stored === undefined) return undefined;
    return (
      (await this.#open(name, key, stored, await this.#protection())) ??
      undefined
    );
  }

  async #all<Name extends "composers" | "drafts">(
    name: Name,
  ): Promise<[string, Kept<Name>][]> {
    const protection = await this.#protection();
    await this.#persistMemory(name, protection);
    const memory = this.#memory[name] as Map<string, Kept<Name>>;
    const opened: [string, Kept<Name>][] = [];
    for (const [key, stored] of await this.#stores.entries(name)) {
      if (memory.has(String(key))) continue;
      const value = await this.#open(name, String(key), stored, protection);
      if (value !== null) opened.push([String(key), value]);
    }
    return [...opened, ...memory];
  }

  /** A composer's kept contents. */
  async composer(localId: string): Promise<ComposeSnapshot | undefined> {
    const kept = await this.#read("composers", localId);
    return isPendingDelete(kept) ? undefined : kept;
  }

  /** Whether a composer's contents are kept, opened here or not (sealed while locked, say). */
  async holds(localId: string): Promise<boolean> {
    if (this.#memory.composers.has(localId)) return true;
    const stored = await this.#stores.get("composers", localId);
    return stored !== undefined && !isPendingDelete(stored);
  }

  /** Every kept composer. */
  async composers(): Promise<[string, ComposeSnapshot][]> {
    return (await this.#all("composers")).flatMap(([key, kept]) =>
      isPendingDelete(kept) ? [] : [[key, kept] as [string, ComposeSnapshot]],
    );
  }

  /** Every thrown-away draft whose Gmail copy is still to be deleted. */
  async pendingDeletes(): Promise<PendingDelete[]> {
    return (await this.#stores.entries("composers")).flatMap(([, stored]) =>
      isPendingDelete(stored) ? [stored] : [],
    );
  }

  /** Keep a delete waiting for Gmail in place of its composer. */
  putDelete(pending: PendingDelete): Promise<void> {
    return this.#queued("composers", pending.localId, async () => {
      await this.#stores.put("composers", pending.localId, pending);
      this.#memory.composers.delete(pending.localId);
      this.#changed(pending.localId, pending);
    });
  }

  /** Keep a composer's contents. */
  putComposer(snapshot: ComposeSnapshot): Promise<void> {
    return this.#queued("composers", snapshot.localId, async () => {
      await this.#write("composers", snapshot.localId, snapshot);
      this.#changed(snapshot.localId, snapshot);
    });
  }

  /**
   * Change a kept composer's contents in one step, after every change asked for before; nothing is
   * written when none is kept. Resolves to what is kept after.
   */
  updateComposer(
    localId: string,
    change: (snapshot: ComposeSnapshot) => ComposeSnapshot,
  ): Promise<ComposeSnapshot | undefined> {
    return this.#queued("composers", localId, async () => {
      const kept = await this.#read("composers", localId);
      if (kept === undefined || isPendingDelete(kept)) return undefined;
      const next = change(kept);
      await this.#write("composers", localId, next);
      this.#changed(localId, next);
      return next;
    });
  }

  /** Forget a composer's contents. */
  deleteComposer(localId: string): Promise<void> {
    return this.#queued("composers", localId, async () => {
      this.#memory.composers.delete(localId);
      this.#changed(localId, null);
      await this.#stores.delete("composers", localId);
    });
  }

  /** Every queued draft save. */
  jobs(): Promise<[string, DraftJob][]> {
    return this.#all("drafts");
  }

  /** Keep a queued draft save. */
  putJob(job: DraftJob): Promise<void> {
    return this.#queued("drafts", job.localId, () =>
      this.#write("drafts", job.localId, job),
    );
  }

  /** Forget a queued draft save. */
  deleteJob(localId: string): Promise<void> {
    return this.#queued("drafts", localId, async () => {
      this.#memory.drafts.delete(localId);
      await this.#stores.delete("drafts", localId);
    });
  }
}
