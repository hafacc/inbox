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

/** Composers' contents and queued draft saves on the device, sealed when the account has a key. */
export class ComposerStore {
  readonly #stores: ComposeStores;
  readonly #protection: () => Promise<Protection>;

  constructor(stores: ComposeStores, protection: () => Promise<Protection>) {
    this.#stores = stores;
    this.#protection = protection;
  }

  async #write<Name extends "composers" | "drafts">(
    name: Name,
    key: string,
    value: Kept<Name>,
  ): Promise<void> {
    const protection = await this.#protection();
    if (protection.kind === "plain") {
      await this.#stores.put(name, key, value);
    } else if (protection.kind === "sealed") {
      const sealed: SealedRecord = {
        sealed: await sealValue(protection.key, value),
      };
      await this.#stores.put(name, key, sealed as unknown as Kept<Name>);
    } else {
      await this.#stores.delete(name, key);
    }
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
    const opened: [string, Kept<Name>][] = [];
    for (const [key, stored] of await this.#stores.entries(name)) {
      const value = await this.#open(name, String(key), stored, protection);
      if (value !== null) opened.push([String(key), value]);
    }
    return opened;
  }

  /** A composer's kept contents. */
  async composer(localId: string): Promise<ComposeSnapshot | undefined> {
    const kept = await this.#read("composers", localId);
    return isPendingDelete(kept) ? undefined : kept;
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
    return this.#stores.put("composers", pending.localId, pending);
  }

  /** Keep a composer's contents. */
  putComposer(snapshot: ComposeSnapshot): Promise<void> {
    return this.#write("composers", snapshot.localId, snapshot);
  }

  /** Forget a composer's contents. */
  deleteComposer(localId: string): Promise<void> {
    return this.#stores.delete("composers", localId);
  }

  /** Every queued draft save. */
  jobs(): Promise<[string, DraftJob][]> {
    return this.#all("drafts");
  }

  /** Keep a queued draft save. */
  putJob(job: DraftJob): Promise<void> {
    return this.#write("drafts", job.localId, job);
  }

  /** Forget a queued draft save. */
  deleteJob(localId: string): Promise<void> {
    return this.#stores.delete("drafts", localId);
  }
}
