import { GmailError, isNetworkError, type TokenSource } from "../gmail/request";
import type { Idb } from "../idb";
import {
  applySync,
  type Contact,
  type ContactSource,
  mergeBook,
  photoUrls,
} from "./contacts";
import { PeopleClient } from "./people";
import { forgetPhotos, retryFailedPhotos } from "./photo-cache";
import { photoDirectory } from "./photo-directory.svelte";

const SOURCES: ContactSource[] = ["connections", "other"];
// Contacts change rarely; a sync per hour of use is plenty.
const SYNC_EVERY_MS = 60 * 60 * 1000;
const MAX_PAGES = 50;

/**
 * Google Contacts on this device for autocomplete and avatar photos, kept current with People API
 * sync tokens.
 */
export class ContactBook {
  /** Every address, saved contacts first. */
  contacts = $state.raw<Contact[]>([]);
  #people: PeopleClient;
  #cache: Idb;
  #stored = new Map<ContactSource, Map<string, Contact[]>>();
  #loaded: Promise<void> | null = null;
  #syncing = false;
  #syncedAt = 0;
  // Set by `dispose`: a load or sync finishing later changes nothing.
  #disposed = false;

  constructor(tokens: TokenSource, cache: Idb) {
    this.#people = new PeopleClient(tokens);
    this.#cache = cache;
  }

  /** Read the device copy, then sync when it's stale. Safe to call often. */
  async ensure(): Promise<void> {
    this.#loaded ??= this.#load();
    await this.#loaded;
    if (Date.now() - this.#syncedAt > SYNC_EVERY_MS) void this.sync();
  }

  /** Stop: work under way finishes without changing the book or the device's copy. */
  dispose(): void {
    this.#disposed = true;
  }

  async #load(): Promise<void> {
    for (const source of SOURCES) {
      const saved = await this.#cache
        .get("contacts", source)
        .catch(() => undefined);
      if (this.#disposed) return;
      this.#stored.set(source, new Map(saved?.people ?? []));
      if (saved) {
        this.#syncedAt =
          this.#syncedAt === 0
            ? saved.syncedAt
            : Math.min(this.#syncedAt, saved.syncedAt);
      }
    }
    this.#publish();
  }

  #publish(): void {
    if (this.#disposed) return;
    this.contacts = mergeBook(
      this.#stored.get("connections")?.values() ?? [],
      this.#stored.get("other")?.values() ?? [],
    );
    photoDirectory.set(this.contacts);
  }

  /** Pull changes from Google for both lists. */
  async sync(): Promise<void> {
    if (this.#syncing || this.#disposed) return;
    this.#syncing = true;
    try {
      const before = photoUrls(
        SOURCES.flatMap((source) => [
          ...(this.#stored.get(source)?.values() ?? []),
        ]),
      );
      for (const source of SOURCES) await this.#syncSource(source);
      if (this.#disposed) return;
      this.#syncedAt = Date.now();
      const after = photoUrls(
        SOURCES.flatMap((source) => [
          ...(this.#stored.get(source)?.values() ?? []),
        ]),
      );
      // A changed URL is a new photo; the old one's image is dropped from the device.
      void forgetPhotos([...before].filter((url) => !after.has(url)));
      retryFailedPhotos();
    } catch (error) {
      if (!isNetworkError(error)) console.error("contacts sync failed", error);
    } finally {
      this.#syncing = false;
    }
  }

  async #syncSource(source: ContactSource): Promise<void> {
    const saved = await this.#cache
      .get("contacts", source)
      .catch(() => undefined);
    let syncToken = saved?.syncToken ?? null;
    let people = syncToken
      ? (this.#stored.get(source) ?? new Map<string, Contact[]>())
      : new Map<string, Contact[]>();
    let pageToken: string | null = null;
    let nextSyncToken: string | null = null;
    for (let page = 0; page < MAX_PAGES; page++) {
      if (this.#disposed) return;
      let result: Awaited<ReturnType<PeopleClient["page"]>>;
      try {
        result = await this.#people.page(source, syncToken, pageToken);
      } catch (error) {
        // An expired sync token (People says 400 or 410): start over with a full list.
        const expired =
          syncToken !== null &&
          error instanceof GmailError &&
          (error.status === 400 || error.status === 410);
        if (!expired) throw error;
        syncToken = null;
        people = new Map();
        pageToken = null;
        continue;
      }
      people = applySync(people, result.people, source);
      nextSyncToken = result.nextSyncToken ?? nextSyncToken;
      pageToken = result.nextPageToken;
      if (pageToken === null) break;
    }
    if (this.#disposed) return;
    this.#stored.set(source, people);
    this.#publish();
    await this.#cache.put("contacts", source, {
      syncToken: nextSyncToken,
      people: [...people],
      syncedAt: Date.now(),
    });
  }
}
