import { FOCUS_QUERY } from "@inbox/shared/focus";
import {
  applyHistory,
  type HistoryRecord,
  readOrder,
  type SavedMessages,
  threadLabels,
} from "@inbox/shared/history-apply";
import {
  afterDraftsListing,
  draftsListingWanted,
  NO_SAVED_DRAFT_REFS,
  type SavedDraftMessage,
  type SavedDraftRefs,
  savedDraftMessages,
  sentDrafts,
} from "@inbox/shared/offline-drafts";
import {
  DEFAULT_OFFLINE_DAYS,
  offlineDays,
  offlinePlan,
  offlineQueries,
  offlineTier,
} from "@inbox/shared/offline-set";
import {
  afterHistory,
  dropsUnlisted,
  type HistoryRead,
  type SyncRun,
  type SyncState,
  syncAfter,
  syncMode,
} from "@inbox/shared/offline-sync";
import type {
  GmailDraftRef,
  OutboxDraftDoc,
} from "@inbox/shared/outbox-drafts";
import {
  type CallPriority,
  type GmailClient,
  GmailError,
  isNetworkError,
  isRetryable,
  type ListOptions,
  mapLimited,
} from "./gmail/client";
import { isLaterHistory } from "./gmail/history-id";
import type {
  GmailHistoryRecord,
  GmailMessage,
  GmailThread,
} from "./gmail/types";
import { type Idb, OFFLINE_META } from "./idb";
import { isEncryptedMessage } from "./pgp/message-crypto";

/** What the saved copy knows of one saved thread besides its row and bodies. */
export type SavedThread = {
  /** the thread's history id when it was read */
  historyId: string;
  /** message id to its label ids */
  messages: Record<string, string[]>;
  /** its newest message's date, epoch milliseconds */
  newestAt: number;
  /** when it was last opened on this device, epoch milliseconds, or null if never */
  openedAt: number | null;
  /** whether any of its messages is encrypted, so it is kept at any age */
  encrypted: boolean;
};

/** Where the saved copy keeps its records and small values. */
export type OfflineStore = {
  /** every saved thread's record */
  entries(): Promise<[string, SavedThread][]>;
  /** save records */
  put(entries: readonly [string, SavedThread][]): Promise<void>;
  /** drop threads' records and bodies */
  delete(ids: readonly string[]): Promise<void>;
  /** a small saved value */
  meta(key: string): Promise<unknown>;
  /** save a small value */
  putMeta(key: string, value: unknown): Promise<void>;
};

/** The Gmail calls the saved copy makes. */
export type OfflineGmail = Pick<
  GmailClient,
  | "listHistory"
  | "listThreads"
  | "getThread"
  | "getMessage"
  | "getProfile"
  | "listDrafts"
>;

/** What the saved copy asks of the app around it. */
export type OfflineHost = {
  /** threads whose snooze is still ahead */
  snoozed(): ReadonlySet<string>;
  /** threads with a change made here not yet known to be in Gmail (queued, or undoable) */
  changedHere(): ReadonlySet<string>;
  /** save rows and bodies of threads just read whole; `changedBefore` is `changedHere` from before the read */
  save(
    threads: readonly GmailThread[],
    changedBefore: ReadonlySet<string>,
  ): Promise<void>;
  /**
   * add messages new to a saved thread to its saved body and row, `labels` being every message's
   * labels after; false when its body isn't saved, so it has to be read whole
   */
  append(
    threadId: string,
    messages: readonly GmailMessage[],
    labels: SavedMessages,
  ): Promise<boolean>;
  /** set saved threads' rows and bodies to new labels (thread id to its messages' labels) */
  relabel(messages: ReadonlyMap<string, SavedMessages>): Promise<void>;
  /** forget threads Gmail no longer has */
  remove(ids: readonly string[]): void;
  /** a rebuild came back whole: lists can be built from the saved copy again */
  rebuilt(): void;
  /** threads a sync read after answering are saved: lists from the saved copy may show more */
  caughtUp(): void;
  /** the saved threads' count or the setting changed */
  changed(): void;
  /** another tab changed these saved threads, whose rows and lists may be out of date here */
  reloaded(ids: readonly string[]): void;
};

/**
 * A lock the device's tabs share, so one at a time syncs, fills and prunes the saved copy and
 * none deletes what another just saved or reads what another is reading.
 */
export type OfflineLock = {
  /**
   * Take it, waiting for another tab to let go when `wait`; resolves the function that lets go,
   * or null when another tab has it and `wait` is false.
   */
  take(wait: boolean): Promise<(() => void) | null>;
};

/** A lock no other tab shares: always free. */
export const UNSHARED_LOCK: OfflineLock = {
  /** given at once */
  take: async () => () => {},
};

/** The lock named `name` shared by every tab of this browser (Web Locks); unshared without them. */
export function webLock(name: string): OfflineLock {
  return {
    take: (wait) => {
      const locks =
        typeof navigator === "undefined" ? undefined : navigator.locks;
      if (locks === undefined) return UNSHARED_LOCK.take(wait);
      return new Promise((resolve, reject) => {
        locks
          .request(name, { ifAvailable: !wait }, (lock) => {
            if (lock === null) {
              resolve(null);
              return;
            }
            // Held until the returned function settles this promise.
            return new Promise<void>((release) => resolve(() => release()));
          })
          .catch(reject);
      });
    },
  };
}

/** What an incremental sync read and did. */
export type SyncOutcome = {
  /** the history records applied, oldest first */
  records: GmailHistoryRecord[];
  /** threads history named that aren't saved, whose rows may be out of date */
  touched: string[];
};

const HISTORY_KEY = OFFLINE_META.historyId;
const FILLED_KEY = OFFLINE_META.filled;
const RETRY_KEY = OFFLINE_META.retry;
const HOLDER_KEY = OFFLINE_META.holder;
const DRAFTS_KEY = OFFLINE_META.drafts;
const SENT_KEY = OFFLINE_META.sentDrafts;
const DAYS_KEY = "offlineDays";
// Threads read whole at once; each batch is saved before the next, so a fill cut short keeps them.
const BATCH = 25;
const READS_AT_ONCE = 4;
const HISTORY_PAGE = 500;
const LIST_PAGE = 500;
// Pins are Gmail's stars.
const PINNED = "STARRED";
const INBOX = "INBOX";
const DRAFT = "DRAFT";
const HIDDEN: ReadonlySet<string> = new Set(["SPAM", "TRASH"]);
const INLINE_BEGIN = "BEGIN PGP MESSAGE";

// Whether a message read whole or as metadata is encrypted: by its type, its text, or its snippet.
function encryptedMessage(message: GmailMessage): boolean {
  return (
    isEncryptedMessage(message) ||
    (message.snippet ?? "").includes(INLINE_BEGIN)
  );
}

/**
 * The labels a list of the inbox's own threads asks for, when the saved copy can answer it: the
 * inbox, a bundle (INBOX and its label), pinned (INBOX and STARRED), and focus mode's query. Its
 * query may only leave bundles out (`-label:`, `-category:`), which the inbox's rows do anyway.
 * Null for any other list.
 */
export function savedListLabels(options: ListOptions): string[] | null {
  const labels = options.labelIds ?? [];
  if (!labels.includes(INBOX) || options.includeSpamTrash) return null;
  const extra: string[] = [];
  for (const term of (options.q ?? "").split(/\s+/)) {
    if (term === "" || /^-(label|category):/.test(term)) continue;
    if (term === FOCUS_QUERY) extra.push(PINNED);
    else return null;
  }
  return [...new Set([...labels, ...extra])];
}

// Draft refs as stored: only well-formed entries.
function draftRefList(value: unknown): GmailDraftRef[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is GmailDraftRef =>
      typeof entry === "object" &&
      entry !== null &&
      typeof entry.draftId === "string" &&
      typeof entry.messageId === "string" &&
      typeof entry.threadId === "string",
  );
}

// The stored drafts listing, or none.
function draftRefsFrom(value: unknown): SavedDraftRefs {
  if (typeof value !== "object" || value === null) return NO_SAVED_DRAFT_REFS;
  const { refs, unmatched } = value as { refs?: unknown; unmatched?: unknown };
  return {
    refs: draftRefList(refs),
    unmatched: Array.isArray(unmatched)
      ? unmatched.filter((id): id is string => typeof id === "string")
      : [],
  };
}

/** A saved-copy record of a thread read whole. */
export function savedThread(
  thread: GmailThread,
  openedAt: number | null,
): SavedThread {
  const messages = thread.messages ?? [];
  return {
    historyId: thread.historyId ?? "",
    messages: Object.fromEntries(
      messages.map((message) => [message.id, [...(message.labelIds ?? [])]]),
    ),
    newestAt: Math.max(
      0,
      ...messages.map((message) => Number(message.internalDate ?? 0) || 0),
    ),
    openedAt,
    encrypted: messages.some(encryptedMessage),
  };
}

// A saved thread's record with its messages' labels now and the messages just added to it.
function withMessages(
  saved: SavedThread,
  messages: Record<string, string[]>,
  fresh: readonly GmailMessage[],
): SavedThread {
  let historyId = saved.historyId;
  for (const message of fresh) {
    const next = message.historyId ?? "";
    if (isLaterHistory(next, historyId)) {
      historyId = next;
    }
  }
  return {
    ...saved,
    historyId,
    messages,
    newestAt: Math.max(
      saved.newestAt,
      ...fresh.map((message) => Number(message.internalDate ?? 0) || 0),
    ),
    encrypted: saved.encrypted || fresh.some(encryptedMessage),
  };
}

/** The saved copy's records and small values in the device's mail cache. */
export function idbOfflineStore(cache: Idb): OfflineStore {
  return {
    entries: async () =>
      (await cache.entries("offline")).map(([id, saved]) => [
        String(id),
        saved,
      ]),
    put: (entries) => cache.putMany("offline", entries),
    delete: async (ids) => {
      await cache.deleteMany("offline", ids);
      await cache.deleteMany("threads", ids);
    },
    meta: (key) => cache.get("meta", key),
    putMeta: (key, value) => cache.put("meta", key, value),
  };
}

/**
 * The device's saved copy of mail for reading offline (design.md, "Offline"): which threads are
 * saved with their bodies, kept in step with Gmail's history from a history id it keeps across
 * restarts, or rebuilt in full when it has none or Gmail no longer knows it.
 */
export class OfflineCopy {
  #gmail: OfflineGmail;
  #store: OfflineStore;
  #host: OfflineHost;
  #now: () => number;
  #index = new Map<string, SavedThread>();
  #state: SyncState = { historyId: null, filled: false };
  #days = DEFAULT_OFFLINE_DAYS;
  // The running rebuild and the running history sync; an incremental sync may run beside a rebuild.
  #rebuilding: Promise<unknown> | null = null;
  #syncing: Promise<unknown> | null = null;
  #rebuildWanted = false;
  // Gmail no longer knows the saved history id; only a whole rebuild replaces it.
  #expired = false;
  // Threads whose read failed other than offline, read again at each sync until one works.
  #retry = new Set<string>();
  // Gmail's drafts as last listed, and the drafts the device knows went (see offline-drafts.ts).
  #draftRefs: SavedDraftRefs = NO_SAVED_DRAFT_REFS;
  #sent: GmailDraftRef[] = [];
  #disposed = false;
  #lock: OfflineLock;
  // Lets go of the shared lock while this tab has it; work under it here counted in `#holders`.
  #release: (() => void) | null = null;
  #holders = 0;
  #acquiring: { waits: boolean; done: Promise<boolean> } | null = null;
  // Whether this tab changed the stored copy since it took the lock, so others must read it again.
  #wrote = false;
  // This tab's mark, left in the store when it lets go after changing the copy, and the last mark
  // this tab's records match; a different one means another tab changed the copy since.
  #token = crypto.randomUUID();
  #seenHolder: unknown;
  #waitingForOther = false;
  /** Resolves once the saved records and settings are read. */
  readonly ready: Promise<void>;

  constructor(
    gmail: OfflineGmail,
    store: OfflineStore,
    host: OfflineHost,
    now: () => number = Date.now,
    lock: OfflineLock = UNSHARED_LOCK,
  ) {
    this.#gmail = gmail;
    this.#store = store;
    this.#host = host;
    this.#now = now;
    this.#lock = lock;
    this.ready = this.#load();
  }

  async #load(): Promise<void> {
    try {
      await this.#readStored();
    } catch (error) {
      console.error("saved mail read failed", error);
    }
    this.#host.changed();
  }

  // Replace what this tab knows with the stored copy; answers the threads whose records changed.
  async #readStored(): Promise<string[]> {
    const [entries, historyId, filled, days, retry, holder, drafts, sent] =
      await Promise.all([
        this.#store.entries(),
        this.#store.meta(HISTORY_KEY),
        this.#store.meta(FILLED_KEY),
        this.#store.meta(DAYS_KEY),
        this.#store.meta(RETRY_KEY),
        this.#store.meta(HOLDER_KEY),
        this.#store.meta(DRAFTS_KEY),
        this.#store.meta(SENT_KEY),
      ]);
    const before = this.#index;
    const changed: string[] = [];
    this.#index = new Map(entries);
    for (const [id, saved] of this.#index) {
      const known = before.get(id);
      if (
        known === undefined ||
        known.historyId !== saved.historyId ||
        JSON.stringify(known.messages) !== JSON.stringify(saved.messages)
      ) {
        changed.push(id);
      }
    }
    for (const id of before.keys()) {
      if (!this.#index.has(id)) changed.push(id);
    }
    this.#retry = new Set(
      Array.isArray(retry)
        ? retry.filter((id): id is string => typeof id === "string")
        : [],
    );
    const nextHistoryId = typeof historyId === "string" ? historyId : null;
    // Another tab's rebuild replaced an id Gmail no longer knew.
    if (nextHistoryId !== this.#state.historyId) this.#expired = false;
    this.#state = { historyId: nextHistoryId, filled: filled === true };
    this.#days = days === undefined ? DEFAULT_OFFLINE_DAYS : offlineDays(days);
    this.#seenHolder = holder;
    this.#draftRefs = draftRefsFrom(drafts);
    this.#sent = draftRefList(sent);
    return changed;
  }

  // Read the stored copy again if another tab changed it since this one last read it, and tell
  // the app what changed.
  async #catchUp(): Promise<void> {
    const holder = await this.#store.meta(HOLDER_KEY);
    if (holder === this.#seenHolder || this.#disposed) return;
    const wasFilled = this.#state.filled;
    const changed = await this.#readStored();
    if (this.#disposed) return;
    this.#host.changed();
    if (this.#state.filled && !wasFilled) this.#host.rebuilt();
    else if (changed.length > 0) this.#host.reloaded(changed);
  }

  // Take the lock the device's tabs share, for one piece of work here; false when another tab has
  // it (after it lets go, when `wait`). Every true answer is matched by one `#leave`.
  async #enter(wait: boolean): Promise<boolean> {
    while (this.#release === null) {
      if (this.#disposed) return false;
      const acquiring = this.#acquiring;
      if (acquiring === null) {
        this.#acquiring = { waits: wait, done: this.#acquire(wait) };
      } else if (acquiring.waits && !wait) {
        return false;
      }
      const taken = await this.#acquiring!.done;
      if (!taken && !wait) return false;
    }
    this.#holders += 1;
    return true;
  }

  async #acquire(wait: boolean): Promise<boolean> {
    let release: (() => void) | null = null;
    try {
      release = await this.#lock.take(wait);
      if (release !== null && this.#disposed) {
        release();
        release = null;
      }
      if (release !== null) {
        this.#release = release;
        await this.#catchUp().catch((error: unknown) =>
          console.error("saved mail read failed", error),
        );
      }
    } catch (error) {
      console.error("saved mail lock failed", error);
    } finally {
      this.#acquiring = null;
    }
    return release !== null;
  }

  #leave(): void {
    this.#holders -= 1;
    const release = this.#release;
    if (this.#holders > 0 || release === null) return;
    const letGo = () => {
      if (this.#holders === 0 && this.#release === release) {
        this.#release = null;
        release();
      }
    };
    if (!this.#wrote || this.#disposed) {
      letGo();
      return;
    }
    this.#wrote = false;
    this.#seenHolder = this.#token;
    // Left before letting go, so the next tab to take the lock reads the copy again.
    void this.#store
      .putMeta(HOLDER_KEY, this.#token)
      .catch((error: unknown) =>
        console.error("saved mail write failed", error),
      )
      .finally(letGo);
  }

  // Once another tab lets go of the lock, read what it changed.
  #awaitOther(): void {
    if (this.#waitingForOther || this.#disposed) return;
    this.#waitingForOther = true;
    void this.#enter(true)
      .then((taken) => {
        if (taken) this.#leave();
      })
      .finally(() => {
        this.#waitingForOther = false;
      });
  }

  /**
   * Run `work` while this tab has the lock the device's tabs share for the saved copy (so it can
   * prune what the copy names without racing another tab's sync); null, without running it, when
   * another tab has it.
   */
  async whileHeld<Result>(work: () => Promise<Result>): Promise<Result | null> {
    await this.ready;
    if (!(await this.#enter(false))) return null;
    try {
      return await work();
    } finally {
      this.#leave();
    }
  }

  /** Whether a whole rebuild filled the copy, so the inbox's lists come from it. */
  get filled(): boolean {
    return this.#state.filled;
  }

  /** Days of mail saved besides the inbox. */
  get days(): number {
    return this.#days;
  }

  /** Whether a sync or rebuild is running. */
  get busy(): boolean {
    return this.#rebuilding !== null || this.#syncing !== null;
  }

  /** How many threads are saved. */
  get count(): number {
    return this.#index.size;
  }

  /** Whether a thread is saved. */
  has(id: string): boolean {
    return this.#index.has(id);
  }

  /** Every saved thread carrying all of `labels`. */
  savedIds(labels: readonly string[]): string[] {
    const ids: string[] = [];
    for (const [id, saved] of this.#index) {
      const own = threadLabels(saved.messages);
      if (labels.every((label) => own.includes(label))) ids.push(id);
    }
    return ids;
  }

  /** The saved threads' draft messages. */
  savedDrafts(): SavedDraftMessage[] {
    return savedDraftMessages(
      [...this.#index].map(([id, saved]) => [id, saved.messages] as const),
    );
  }

  /** Gmail's drafts as last listed, newest first. */
  get draftRefs(): readonly GmailDraftRef[] {
    return this.#draftRefs.refs;
  }

  /** The drafts the device knows went that the saved copy still holds (see `sentDrafts`). */
  get sentDrafts(): readonly GmailDraftRef[] {
    return this.#sent;
  }

  /**
   * Note the outbox docs: a draft a `sent` one names is kept out of Drafts until the saved copy
   * drops it, even once the doc is gone. `known` are the drafts this device is sending.
   */
  async noteSent(
    docs: readonly OutboxDraftDoc[],
    known: readonly GmailDraftRef[],
  ): Promise<void> {
    await this.ready;
    const next = sentDrafts(
      this.#sent,
      docs,
      [...known, ...this.#draftRefs.refs],
      this.savedDrafts(),
    );
    if (JSON.stringify(next) === JSON.stringify(this.#sent)) return;
    if (!(await this.#enter(true))) return;
    try {
      await this.#putSent(next);
    } finally {
      this.#leave();
    }
  }

  async #putSent(next: GmailDraftRef[]): Promise<void> {
    if (JSON.stringify(next) === JSON.stringify(this.#sent)) return;
    this.#sent = next;
    this.#wrote = true;
    await this.#store
      .putMeta(SENT_KEY, next)
      .catch((error: unknown) =>
        console.error("saved mail write failed", error),
      );
  }

  // List Gmail's drafts when a saved draft message isn't in the last listing, so each opens
  // offline; and forget sent drafts the saved copy no longer holds.
  async #settleDrafts(): Promise<void> {
    if (this.#disposed) return;
    const saved = this.savedDrafts();
    if (draftsListingWanted(saved, this.#draftRefs)) {
      try {
        const listed = await this.#gmail.listDrafts();
        if (this.#disposed) return;
        const refs = listed.map((draft) => ({
          draftId: draft.id,
          messageId: draft.message.id,
          threadId: draft.message.threadId,
        }));
        this.#draftRefs = afterDraftsListing(refs, this.savedDrafts());
        this.#wrote = true;
        await this.#store
          .putMeta(DRAFTS_KEY, this.#draftRefs)
          .catch((error: unknown) =>
            console.error("saved mail write failed", error),
          );
      } catch {
        // Listed again at the next sync.
      }
    }
    await this.#putSent(sentDrafts(this.#sent, [], [], this.savedDrafts()));
  }

  /** Stop; calls after this do nothing. */
  dispose(): void {
    this.#disposed = true;
    const release = this.#release;
    this.#release = null;
    release?.();
  }

  /** Note threads read whole elsewhere (an opened thread), whose rows and bodies are saved already. */
  async noteRead(
    threads: readonly GmailThread[],
    opened: boolean,
  ): Promise<void> {
    await this.ready;
    if (!(await this.#enter(true))) return;
    try {
      const now = opened ? this.#now() : null;
      const entries = threads.map((thread): [string, SavedThread] => [
        thread.id,
        savedThread(
          thread,
          now ?? this.#index.get(thread.id)?.openedAt ?? null,
        ),
      ]);
      await this.#remember(entries);
    } finally {
      this.#leave();
    }
  }

  /** Note that a saved thread was opened, so it stays saved as recently opened. */
  async noteOpened(id: string): Promise<void> {
    await this.ready;
    const openedAt = this.#now();
    if (!(await this.#enter(true))) return;
    try {
      const saved = this.#index.get(id);
      if (saved === undefined) return;
      await this.#remember([[id, { ...saved, openedAt }]]);
    } finally {
      this.#leave();
    }
  }

  /** Change how many days of mail are saved; more days fetch the older mail, fewer drop it. */
  async setDays(days: number): Promise<void> {
    await this.ready;
    const next = offlineDays(days);
    if (!(await this.#enter(true))) return;
    try {
      if (next === this.#days) return;
      const more = next > this.#days;
      this.#days = next;
      this.#wrote = true;
      await this.#store.putMeta(DAYS_KEY, next).catch((error: unknown) => {
        console.error("offline setting write failed", error);
      });
      this.#host.changed();
      if (more) {
        this.#rebuildWanted = true;
        void this.sync();
      } else {
        await this.#trim();
      }
    } finally {
      this.#leave();
    }
  }

  /**
   * Bring the copy up to date. Once filled, reads history from the saved id and answers what it
   * applied, even while a rebuild runs; a rebuild it needs (the first fill, more days, or an id
   * Gmail no longer knows) starts in the background. Answers "busy" while another history sync
   * runs, in this tab or another (what it saves shows here once it's done), and null when history
   * is unknown: before the first fill, with the id expired, or when history couldn't be read.
   */
  async sync(): Promise<SyncOutcome | "busy" | null> {
    await this.ready;
    if (this.#disposed) return null;
    if (!(await this.#enter(false))) {
      this.#awaitOther();
      return "busy";
    }
    try {
      return await this.#syncHeld();
    } finally {
      this.#leave();
    }
  }

  async #syncHeld(): Promise<SyncOutcome | "busy" | null> {
    const mode = syncMode(this.#state.historyId, this.#state.filled);
    if (
      this.#rebuilding === null &&
      (this.#rebuildWanted || this.#expired || mode === "rebuild")
    ) {
      this.#startRebuild();
    }
    if (mode === "rebuild" || this.#expired) return null;
    if (this.#syncing !== null) return "busy";
    // The slot stays held until the reads the sync left for after answering are done.
    let release = () => {};
    const slot = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.#syncing = slot;
    this.#holders += 1;
    const free = () => {
      if (this.#syncing === slot) this.#syncing = null;
      release();
      this.#leave();
    };
    try {
      const run = await this.#incremental();
      if (run === null) {
        free();
        return null;
      }
      void run.finish
        .catch((error: unknown) =>
          console.error("saved mail sync failed", error),
        )
        .finally(free);
      return run.outcome;
    } catch (error) {
      free();
      throw error;
    }
  }

  /** Wait for a running sync or rebuild to end. */
  async settled(): Promise<void> {
    await Promise.all([this.#syncing, this.#rebuilding]);
  }

  // Called only while this tab has the shared lock; the rebuild keeps it until done.
  #startRebuild(): void {
    this.#rebuildWanted = false;
    this.#holders += 1;
    const rebuild: Promise<unknown> = this.#rebuild()
      .catch((error: unknown) =>
        console.error("saved mail rebuild failed", error),
      )
      .finally(() => {
        if (this.#rebuilding === rebuild) this.#rebuilding = null;
        this.#leave();
      });
    this.#rebuilding = rebuild;
  }

  async #incremental(): Promise<{
    outcome: SyncOutcome;
    finish: Promise<void>;
  } | null> {
    const start = this.#state.historyId!;
    const records: GmailHistoryRecord[] = [];
    let reached: string | null = null;
    let read: HistoryRead = "complete";
    let pageToken: string | undefined;
    try {
      do {
        const page = await this.#gmail.listHistory(
          start,
          pageToken,
          HISTORY_PAGE,
          null,
          "background",
        );
        records.push(...(page.history ?? []));
        reached = page.historyId ?? reached;
        pageToken = page.nextPageToken;
      } while (pageToken !== undefined && !this.#disposed);
    } catch (error) {
      read =
        error instanceof GmailError && error.status === 404
          ? "expired"
          : "failed";
    }
    if (this.#disposed) return null;
    const next = afterHistory(read);
    if (next === "stop") return null;
    if (next === "rebuild") {
      this.#expired = true;
      if (this.#rebuilding === null) this.#startRebuild();
      return null;
    }
    const changes = applyHistory(
      records as readonly HistoryRecord[],
      (id) => this.#index.get(id)?.messages,
      this.#days,
    );
    const relabeled: [string, SavedThread][] = [];
    for (const [id, messages] of Object.entries(changes.relabeled)) {
      const saved = this.#index.get(id);
      if (saved === undefined) continue;
      relabeled.push([id, { ...saved, messages }]);
    }
    await this.#remember(relabeled);
    await this.#relabel(relabeled);
    await this.#forget(changes.removed);
    const addedRead = await this.#readAdded(changes.added);
    const ordered = readOrder(
      records as readonly HistoryRecord[],
      changes.fetch,
    );
    const checked = await this.#encryptedAmong(changes.check);
    const retried = [...this.#retry].filter(
      (id) => !ordered.includes(id) && !checked.ids.includes(id),
    );
    const fetch = [...ordered, ...checked.ids, ...retried];
    // The first batch (the inbox's newest) is saved before the sync answers; the rest after.
    const firstRead = await this.#read(fetch.slice(0, BATCH), "background");
    const finish = (async () => {
      const rest = fetch.slice(BATCH);
      const restRead = await this.#read(rest, "background");
      await this.#trim();
      await this.#settleDrafts();
      await this.#settle({
        mode: "incremental",
        listed: true,
        read: addedRead && firstRead && restRead && checked.complete,
        reached,
      });
      // Threads refused before don't count, so a refused one can't make every sync reload the lists.
      if (rest.some((id) => !retried.includes(id)) && !this.#disposed) {
        this.#host.caughtUp();
      }
    })();
    return { outcome: { records, touched: changes.touched }, finish };
  }

  async #rebuild(): Promise<void> {
    let reached: string | null;
    try {
      reached = (await this.#gmail.getProfile("saving")).historyId;
    } catch {
      return;
    }
    // Thread id to the history id its list gave (none for a snoozed thread no list named).
    const listed = new Map<string, string | undefined>();
    let complete = true;
    const queries = offlineQueries(this.#days);
    for (const [position, { query, cap }] of queries.entries()) {
      const before = listed.size;
      try {
        let pageToken: string | null = null;
        do {
          const page = await this.#gmail.listThreads(
            { q: query, maxResults: LIST_PAGE, pageToken },
            "saving",
          );
          for (const stub of page.threads ?? []) {
            if (!listed.has(stub.id)) listed.set(stub.id, stub.historyId);
          }
          pageToken = page.nextPageToken ?? null;
          // Past the cap the oldest threads would only be dropped again.
          if (cap !== null && listed.size - before >= cap) break;
        } while (pageToken !== null && !this.#disposed);
      } catch {
        complete = false;
        break;
      }
      if (position === 0) {
        for (const id of this.#host.snoozed()) {
          if (!listed.has(id)) listed.set(id, undefined);
        }
      }
    }
    if (this.#disposed) return;
    const wanted = [...listed]
      .filter(([id, historyId]) => {
        const saved = this.#index.get(id);
        return (
          saved === undefined ||
          (historyId !== undefined && saved.historyId !== historyId)
        );
      })
      .map(([id]) => id);
    const read = await this.#read(wanted, "saving");
    if (this.#disposed) return;
    if (dropsUnlisted({ mode: "rebuild", listed: complete })) {
      await this.#settleUnlisted(new Set(listed.keys()), reached);
    }
    await this.#trim();
    await this.#settleDrafts();
    await this.#settle({ mode: "rebuild", listed: complete, read, reached });
    if (complete && read && reached !== null) {
      this.#expired = false;
      this.#host.rebuilt();
    }
  }

  // Saved threads a whole rebuild didn't list are out of the inbox and not recent: those opened
  // lately stay without INBOX, the rest go. A thread read after the listing began (history id past
  // `reached`) may have arrived since, so it stays as read.
  async #settleUnlisted(
    listed: ReadonlySet<string>,
    reached: string,
  ): Promise<void> {
    const snoozed = this.#host.snoozed();
    const now = this.#now();
    const kept: [string, SavedThread][] = [];
    const dropped: string[] = [];
    for (const [id, saved] of this.#index) {
      if (listed.has(id) || isLaterHistory(saved.historyId, reached)) continue;
      const messages = Object.fromEntries(
        Object.entries(saved.messages).map(([messageId, own]) => [
          messageId,
          own.filter((label) => label !== INBOX),
        ]),
      );
      const own = threadLabels(messages);
      const tier = offlineTier(
        // A whole listing names every thread with a draft and every recent and encrypted thread, so
        // this one is none of those now.
        {
          id,
          labelIds: own.filter((label) => label !== DRAFT),
          newestAt: 0,
          openedAt: saved.openedAt,
          encrypted: false,
        },
        snoozed.has(id),
        this.#days,
        now,
      );
      if (tier === null) {
        dropped.push(id);
      } else if (own.length !== threadLabels(saved.messages).length) {
        kept.push([id, { ...saved, messages }]);
      }
    }
    await this.#remember(kept);
    await this.#relabel(kept);
    await this.#drop(dropped);
  }

  async #relabel(entries: readonly [string, SavedThread][]): Promise<void> {
    if (entries.length === 0 || this.#disposed) return;
    await this.#host.relabel(
      new Map(entries.map(([id, saved]) => [id, saved.messages])),
    );
  }

  // Read threads whole, a batch at a time, saving each batch; whether every read came back. A
  // thread Gmail no longer has is forgotten; once the network is down the rest wait. A rebuild
  // reads at "saving", which yields to everything else and is paced; a sync, at "background".
  async #read(
    ids: readonly string[],
    priority: CallPriority,
  ): Promise<boolean> {
    let complete = true;
    for (let start = 0; start < ids.length; start += BATCH) {
      if (this.#disposed) return false;
      const batch = ids.slice(start, start + BATCH);
      const changedBefore = this.#host.changedHere();
      const results = await mapLimited(batch, READS_AT_ONCE, (id) =>
        this.#gmail.getThread(id, "full", priority),
      );
      const threads: GmailThread[] = [];
      const gone: string[] = [];
      let offline = false;
      const retryBefore = this.#retry.size;
      let retryChanged = false;
      results.forEach((result, index) => {
        const id = batch[index]!;
        if (result.status === "fulfilled") {
          threads.push(result.value);
          retryChanged ||= this.#retry.delete(id);
        } else if (
          result.reason instanceof GmailError &&
          result.reason.status === 404
        ) {
          gone.push(id);
          retryChanged ||= this.#retry.delete(id);
        } else if (isNetworkError(result.reason)) {
          complete = false;
          offline = true;
        } else {
          // The sync goes on without this thread and reads it next time. Only a refusal for good
          // leaves the read whole; a usage limit, a timeout or a server error left it short.
          if (isRetryable(result.reason)) complete = false;
          this.#retry.add(id);
        }
      });
      if (retryChanged || this.#retry.size !== retryBefore) {
        this.#wrote = true;
        await this.#store
          .putMeta(RETRY_KEY, [...this.#retry])
          .catch((error: unknown) =>
            console.error("saved mail write failed", error),
          );
      }
      if (threads.length > 0) {
        await this.#host.save(threads, changedBefore);
        await this.noteRead(threads, false);
      }
      await this.#forget(gone);
      if (offline) return false;
    }
    return complete;
  }

  // Read saved threads' new messages one by one and add them to their saved bodies (a message
  // never changes once sent, so the rest isn't read again); whether every read came back. A
  // thread whose message couldn't be read, or whose body isn't saved, is read whole instead.
  async #readAdded(
    added: Readonly<Record<string, readonly string[]>>,
  ): Promise<boolean> {
    const wanted = Object.entries(added).flatMap(([threadId, ids]) =>
      ids.map((id) => ({ threadId, id })),
    );
    if (wanted.length === 0 || this.#disposed) return true;
    const results = await mapLimited(wanted, READS_AT_ONCE, ({ id }) =>
      this.#gmail.getMessage(id, "full", "background"),
    );
    if (this.#disposed) return false;
    let complete = true;
    const read = new Map<string, GmailMessage[]>();
    const gone = new Set<string>();
    const whole = new Set<string>();
    results.forEach((result, index) => {
      const { threadId, id } = wanted[index]!;
      if (result.status === "fulfilled") {
        read.set(threadId, [...(read.get(threadId) ?? []), result.value]);
      } else if (
        result.reason instanceof GmailError &&
        result.reason.status === 404
      ) {
        gone.add(id);
      } else {
        // The copy already names the message, so a later sync wouldn't ask for it again.
        whole.add(threadId);
        if (isNetworkError(result.reason)) complete = false;
      }
    });
    const entries: [string, SavedThread][] = [];
    const emptied: string[] = [];
    for (const threadId of Object.keys(added)) {
      const saved = this.#index.get(threadId);
      if (saved === undefined) continue;
      const messages = Object.fromEntries(
        Object.entries(saved.messages).filter(([id]) => !gone.has(id)),
      );
      if (Object.keys(messages).length === 0) {
        emptied.push(threadId);
        continue;
      }
      const fresh = read.get(threadId) ?? [];
      if (
        fresh.length > 0 &&
        !whole.has(threadId) &&
        !(await this.#host.append(threadId, fresh, messages))
      ) {
        whole.add(threadId);
      }
      entries.push([threadId, withMessages(saved, messages, fresh)]);
    }
    await this.#remember(entries);
    await this.#forget(emptied);
    await this.#retryLater(whole);
    return complete;
  }

  // Threads to read whole at the next sync (this one too, when it hasn't read the rest yet).
  async #retryLater(ids: ReadonlySet<string>): Promise<void> {
    const before = this.#retry.size;
    for (const id of ids) this.#retry.add(id);
    if (this.#retry.size === before) return;
    this.#wrote = true;
    await this.#store
      .putMeta(RETRY_KEY, [...this.#retry])
      .catch((error: unknown) =>
        console.error("saved mail write failed", error),
      );
  }

  // Which of `ids` (threads not saved, with new mail) are encrypted and out of spam and trash, read
  // only as far as their headers; whether every read came back.
  async #encryptedAmong(
    ids: readonly string[],
  ): Promise<{ ids: string[]; complete: boolean }> {
    const results = await mapLimited(ids, READS_AT_ONCE, (id) =>
      this.#gmail.getThread(id, "metadata", "background"),
    );
    const encrypted: string[] = [];
    let complete = true;
    results.forEach((result, index) => {
      if (result.status === "fulfilled") {
        const messages = result.value.messages ?? [];
        const hidden = messages.some((message) =>
          (message.labelIds ?? []).some((label) => HIDDEN.has(label)),
        );
        if (!hidden && messages.some(encryptedMessage)) {
          encrypted.push(ids[index]!);
        }
      } else if (
        !(result.reason instanceof GmailError && result.reason.status === 404)
      ) {
        complete = false;
      }
    });
    return { ids: encrypted, complete };
  }

  // Drop what the plan no longer keeps.
  async #trim(): Promise<void> {
    const threads = [...this.#index].map(([id, saved]) => ({
      id,
      labelIds: threadLabels(saved.messages),
      newestAt: saved.newestAt,
      openedAt: saved.openedAt,
      encrypted: saved.encrypted === true,
    }));
    const { drop } = offlinePlan(
      threads,
      this.#host.snoozed(),
      this.#days,
      this.#now(),
    );
    await this.#drop(drop);
  }

  async #remember(entries: readonly [string, SavedThread][]): Promise<void> {
    if (entries.length === 0 || this.#disposed) return;
    for (const [id, saved] of entries) this.#index.set(id, saved);
    this.#wrote = true;
    try {
      await this.#store.put(entries);
    } catch (error) {
      console.error("saved mail write failed", error);
    }
    this.#host.changed();
  }

  // Drop threads from the copy; their rows stay.
  async #drop(ids: readonly string[]): Promise<void> {
    if (ids.length === 0) return;
    for (const id of ids) this.#index.delete(id);
    this.#wrote = true;
    try {
      await this.#store.delete(ids);
    } catch (error) {
      console.error("saved mail write failed", error);
    }
    this.#host.changed();
  }

  // Drop threads Gmail no longer has, rows and all.
  async #forget(ids: readonly string[]): Promise<void> {
    if (ids.length === 0) return;
    await this.#drop(ids);
    this.#host.remove(ids);
  }

  async #settle(run: SyncRun): Promise<void> {
    const before = this.#state;
    const after = syncAfter(before, run);
    const same =
      after.historyId === before.historyId && after.filled === before.filled;
    if (same || this.#disposed) return;
    this.#state = after;
    this.#wrote = true;
    try {
      await Promise.all([
        this.#store.putMeta(HISTORY_KEY, after.historyId),
        this.#store.putMeta(FILLED_KEY, after.filled),
      ]);
    } catch (error) {
      console.error("saved mail write failed", error);
    }
  }
}
