// Which threads a device keeps with their bodies for reading offline, and which of its other saved
// copies (lists, rows) it drops first. Android :core mail/OfflineSet.kt is the same; both run
// shared/testdata/offline-set-fixtures.json.

/**
 * Most threads saved with bodies but encrypted mail; those in the inbox, snoozed or with a draft are
 * kept past it.
 */
export const OFFLINE_THREAD_CAP = 3_000;
/** Most encrypted threads saved past the inbox, apart from `OFFLINE_THREAD_CAP`. */
export const ENCRYPTED_THREAD_CAP = 10_000;
/**
 * Gmail's search for encrypted mail: PGP/MIME's payload file, or an inline block. Gmail can't
 * search inside it, so every such thread is saved for searching on the device.
 */
export const ENCRYPTED_MAIL_QUERY =
  '{filename:encrypted.asc "BEGIN PGP MESSAGE"}';
/** Gmail's search for every thread with a draft, all of which are saved. */
export const DRAFT_QUERY = "in:draft";
/** Days of mail saved besides the inbox, unless the device's setting says otherwise. */
export const DEFAULT_OFFLINE_DAYS = 30;
/** The most days of mail the setting allows. */
export const MAX_OFFLINE_DAYS = 30;

const DAY_MS = 24 * 60 * 60 * 1000;
const INBOX = "INBOX";
const DRAFT = "DRAFT";
const SPAM = "SPAM";
const TRASH = "TRASH";

/** A thread as the offline set weighs it. */
export type OfflineThread = {
  /** Gmail thread id */
  id: string;
  /** every label on any of its messages */
  labelIds: readonly string[];
  /** its newest message's date, epoch milliseconds */
  newestAt: number;
  /** when it was last opened on this device, epoch milliseconds, or null if never */
  openedAt: number | null;
  /** whether any of its messages is encrypted */
  encrypted: boolean;
};

/**
 * Why a thread is saved, most important first: in the inbox (pinned ones too) or snoozed; holding a
 * draft, at any age; encrypted, at any age (Gmail can't search it); with a message in the last days; opened in the
 * last days. A pinned thread out of the inbox is like any other.
 */
export type OfflineTier = "inbox" | "draft" | "encrypted" | "recent" | "opened";

/** A day count clamped to the setting's range, whole days; anything unreadable is the default. */
export function offlineDays(days: unknown): number {
  if (typeof days !== "number" || !Number.isFinite(days)) {
    return DEFAULT_OFFLINE_DAYS;
  }
  return Math.min(MAX_OFFLINE_DAYS, Math.max(0, Math.floor(days)));
}

/** Why `thread` is saved, or null when it isn't. `snoozed` says whether a snooze is still ahead. */
export function offlineTier(
  thread: OfflineThread,
  snoozed: boolean,
  days: number,
  now: number,
): OfflineTier | null {
  const labels = thread.labelIds;
  if (snoozed || labels.includes(INBOX)) {
    return "inbox";
  }
  if (labels.includes(DRAFT)) {
    return "draft";
  }
  const hidden = labels.includes(SPAM) || labels.includes(TRASH);
  if (thread.encrypted && !hidden) {
    return "encrypted";
  }
  const since = now - days * DAY_MS;
  if (days > 0 && thread.newestAt >= since && !hidden) {
    return "recent";
  }
  if (days > 0 && thread.openedAt !== null && thread.openedAt >= since) {
    return "opened";
  }
  return null;
}

/** Which threads stay saved, in the order they are worth fetching, and which go. */
export type OfflinePlan = {
  /**
   * kept: the inbox tier newest first, then threads with a draft newest first, then encrypted newest first, then recent newest first, then
   * opened most recent first
   */
  keep: string[];
  /** dropped: in no tier, or past the cap */
  drop: string[];
};

/**
 * Split threads into those kept and those dropped. Every inbox-tier thread and every thread with a
 * draft is kept. Encrypted
 * threads fill up to `encryptedCap`, newest first; one past it is weighed as recent or opened
 * mail when it is either. Recent and opened threads fill what the inbox and drafts leave of `cap`, so past
 * it the least recently opened go first, then the oldest recent ones.
 */
export function offlinePlan(
  threads: readonly OfflineThread[],
  snoozed: ReadonlySet<string>,
  days: number,
  now: number,
  cap: number = OFFLINE_THREAD_CAP,
  encryptedCap: number = ENCRYPTED_THREAD_CAP,
): OfflinePlan {
  const inbox: OfflineThread[] = [];
  const drafts: OfflineThread[] = [];
  const encrypted: OfflineThread[] = [];
  const recent: OfflineThread[] = [];
  const opened: OfflineThread[] = [];
  const drop: string[] = [];
  const place = (thread: OfflineThread, tier: OfflineTier | null) => {
    if (tier === "inbox") inbox.push(thread);
    else if (tier === "draft") drafts.push(thread);
    else if (tier === "encrypted") encrypted.push(thread);
    else if (tier === "recent") recent.push(thread);
    else if (tier === "opened") opened.push(thread);
    else drop.push(thread.id);
  };
  for (const thread of threads) {
    place(thread, offlineTier(thread, snoozed.has(thread.id), days, now));
  }
  const byNewest = (left: OfflineThread, right: OfflineThread) =>
    right.newestAt - left.newestAt || compareIds(left.id, right.id);
  inbox.sort(byNewest);
  drafts.sort(byNewest);
  encrypted.sort(byNewest);
  const keptEncrypted = encrypted.slice(0, Math.max(0, encryptedCap));
  for (const thread of encrypted.slice(keptEncrypted.length)) {
    const plain = { ...thread, encrypted: false };
    place(plain, offlineTier(plain, false, days, now));
  }
  recent.sort(byNewest);
  opened.sort(
    (left, right) =>
      (right.openedAt ?? 0) - (left.openedAt ?? 0) ||
      compareIds(left.id, right.id),
  );
  const rest = [...recent, ...opened].map((thread) => thread.id);
  const room = Math.max(0, cap - inbox.length - drafts.length);
  return {
    keep: [
      ...inbox.map((thread) => thread.id),
      ...drafts.map((thread) => thread.id),
      ...keptEncrypted.map((thread) => thread.id),
      ...rest.slice(0, room),
    ],
    drop: [...drop, ...rest.slice(room)],
  };
}

function compareIds(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

/** One Gmail search a full rebuild lists. */
export type OfflineQuery = {
  /** the search */
  query: string;
  /** most threads worth listing from it, or null for all */
  cap: number | null;
};

/**
 * The Gmail searches a full rebuild lists, in the order their threads are fetched: the inbox, then
 * every thread with a draft, then (with `days` above 0) every thread with a message in the last `days` days, then encrypted mail.
 * `threads.list` leaves out spam and trash by itself.
 */
export function offlineQueries(days: number): OfflineQuery[] {
  return [
    { query: "in:inbox", cap: null },
    { query: DRAFT_QUERY, cap: null },
    ...(days > 0
      ? [{ query: `newer_than:${days}d`, cap: OFFLINE_THREAD_CAP }]
      : []),
    { query: ENCRYPTED_MAIL_QUERY, cap: ENCRYPTED_THREAD_CAP },
  ];
}

/** The keys to drop so at most `cap` remain: the least recently used. */
export function evictionKeys(
  usage: readonly (readonly [string, number])[],
  cap: number,
): string[] {
  if (usage.length <= cap) return [];
  return [...usage]
    .sort(([, left], [, right]) => left - right)
    .slice(0, usage.length - cap)
    .map(([key]) => key);
}

/**
 * The keys to drop from a saved store: those `expired` says are too old, then the least recently
 * used past `cap`. Keys without a recorded use count as never used; keys `kept` names are never
 * dropped and don't count toward the cap.
 */
export function pruneKeys(
  keys: readonly string[],
  usage: ReadonlyMap<string, number>,
  cap: number,
  expired: (key: string, usedAt: number) => boolean = () => false,
  kept: ReadonlySet<string> = new Set(),
): string[] {
  const dropped: string[] = [];
  const counted: [string, number][] = [];
  for (const key of keys) {
    if (kept.has(key)) continue;
    const usedAt = usage.get(key) ?? 0;
    if (expired(key, usedAt)) dropped.push(key);
    else counted.push([key, usedAt]);
  }
  return [...dropped, ...evictionKeys(counted, cap)];
}
