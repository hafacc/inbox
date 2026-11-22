// By relative path: the deploy uploads only functions/, and tsconfig.build.json compiles this in.
import { bundledOnArrival } from "../../../shared/src/arrival-bundles.js";
import { joinKeys } from "../../../shared/src/join-keys.js";
import { answeredSearch } from "../../../shared/src/thread-joins.js";
import {
  ACTION_TOKEN_TTL_MS,
  type NotificationActionInput,
  newActionToken,
  parseActionToken,
} from "./action-token.js";
import {
  type GmailApi,
  GmailError,
  type GmailHistoryRecord,
  type GmailMessage,
  LABEL,
} from "./gmail-api.js";
import {
  analyzeHistory,
  analyzeRecent,
  compareHistoryIds,
  type GmailPush,
  type HistoryOutcome,
  isNotifiable,
} from "./history.js";
import {
  type ClearData,
  clearData,
  type NotificationData,
  notificationData,
  staleTokens,
} from "./notification.js";
import { headerValue } from "./raw-message.js";
import { type Log, mapLimit, messageOf } from "./util.js";

/** A signed-in account as the server keeps it in `private/{uid}`. */
export interface Account {
  /** Firebase uid */
  uid: string;
  /** Gmail address */
  email: string;
  /** history id the server has handled up to */
  historyId: string | undefined;
  /** when `gmailChanged` last finished, in epoch milliseconds */
  lastSyncAt: number | undefined;
}

/** A pending notification action, stored at `private/{uid}/actions/{nonce}`. */
export interface StoredAction {
  /** Gmail thread id the notification was about */
  threadId: string;
  /** when the action stops working, in epoch milliseconds */
  expiresAt: number;
}

/** A user's choices about which new mail notifies. */
export interface NotifyPrefs {
  /** whether mail that lands in a bundle sends no notification, `settings.quietBundles` */
  quietBundles: boolean;
  /** labels with bundling off in `labelPrefs`; read only while `quietBundles` is on */
  bundleOff: string[];
  /** whether the apps show todos, `settings.showTodos`: then a thread with one is never bundled */
  showTodos: boolean;
}

/** Firestore reads and writes the Gmail push and notification code needs. */
export interface Store {
  /** The account for a Gmail address. */
  accountByEmail(email: string): Promise<Account | undefined>;
  /** The account for a uid. */
  account(uid: string): Promise<Account | undefined>;
  /** Every account with a stored Google grant. */
  accountsWithGrant(): Promise<Account[]>;
  /** Record a watch: its expiry always, its history id only when none is stored. */
  saveWatch(uid: string, historyId: string, expiresAt: number): Promise<void>;
  /** Move the stored history id from `from` to `to`; false when another run moved it first. */
  claimHistory(
    uid: string,
    from: string | undefined,
    to: string,
    syncedAt: number,
  ): Promise<boolean>;
  /** Delete a thread's snooze doc; a missing one counts as done. */
  clearSnooze(uid: string, threadId: string): Promise<void>;
  /** A user's choices about which new mail notifies. */
  notifyPrefs(uid: string): Promise<NotifyPrefs>;
  /** Whether the thread's email has a todo, which the apps show in its row. */
  hasEmailTodo(uid: string, threadId: string): Promise<boolean>;
  /** FCM tokens of a user's devices. */
  deviceTokens(uid: string): Promise<string[]>;
  /** FCM tokens of a user's Android devices. */
  androidTokens(uid: string): Promise<string[]>;
  /** Forget devices FCM no longer knows. */
  removeDevices(uid: string, tokens: readonly string[]): Promise<void>;
  /** Store a notification action; false (storing nothing) when the account is gone. */
  saveAction(
    uid: string,
    nonce: string,
    action: StoredAction,
  ): Promise<boolean>;
  /** Read a notification action. */
  action(uid: string, nonce: string): Promise<StoredAction | undefined>;
  /** Delete a notification action once used. */
  deleteAction(uid: string, nonce: string): Promise<void>;
}

/** One device's result of a multicast send. */
export interface SendResult {
  /** whether FCM accepted it */
  success: boolean;
  /** FCM's error, when it didn't */
  error?: { code: string };
}

/** Sends FCM data messages. */
export interface Messenger {
  /** Send one payload to several devices, returning a result per token in order. */
  send(
    tokens: string[],
    data: NotificationData | ClearData,
  ): Promise<SendResult[]>;
}

/** A user's Gmail and its address. */
export interface GmailAccess {
  /** Gmail through the user's grant */
  gmail: GmailApi;
  /** the Gmail address */
  email: string;
}

/** Everything the Gmail push and notification handlers talk to, injectable for tests. */
export interface Deps {
  /** Firestore */
  store: Store;
  /** Gmail for a user, or undefined when their grant is gone */
  gmailFor(uid: string): Promise<GmailAccess | undefined>;
  /** FCM */
  messenger: Messenger;
  /** the Pub/Sub topic Gmail publishes to (`projects/…/topics/…`) */
  topic: string;
  /** the clock, in epoch milliseconds */
  now(): number;
  /** logger */
  log: Log;
}

/** Most devices one FCM multicast takes. */
export const FCM_MULTICAST_LIMIT = 500;

/** Most history pages read per push. */
const MAX_HISTORY_PAGES = 20;
/** Most notifications sent per push; the newest are kept. */
const MAX_NOTIFICATIONS = 10;
/** Most watches renewed at once. */
const RENEW_CONCURRENCY = 10;
/** Search for recent inbox mail when history is too old to read. */
const RESYNC_QUERY = "in:inbox newer_than:2d";
/** Most messages a resync reads. */
const RESYNC_MESSAGES = 25;

/** Result of handling a Gmail push, for logs and tests. */
export type PushResult =
  | "unknown-account"
  | "already-handled"
  | "no-grant"
  | "baseline"
  | "raced"
  | "handled";

/** What handling a Gmail push did, for logs and tests. */
export interface PushOutcome {
  /** how it ended */
  result: PushResult;
  /** whether the history it acted on added messages (always, after a resync) */
  mailArrived: boolean;
}

/** Start (or renew) a Gmail watch and record it. */
export async function startWatch(
  deps: Deps,
  uid: string,
  gmail: GmailApi,
): Promise<void> {
  const watch = await gmail.watch(deps.topic);
  await deps.store.saveWatch(uid, watch.historyId, watch.expiration);
}

/** Renew every account's watch; one failure doesn't stop the rest. Returns how many renewed. */
export async function renewWatches(deps: Deps): Promise<number> {
  const accounts = await deps.store.accountsWithGrant();
  const outcomes = await mapLimit(
    accounts,
    RENEW_CONCURRENCY,
    async (account) => {
      try {
        const access = await deps.gmailFor(account.uid);
        if (access === undefined) return false;
        await startWatch(deps, account.uid, access.gmail);
        return true;
      } catch (error) {
        deps.log.warn("watch renewal failed", {
          uid: account.uid,
          message: messageOf(error),
        });
        return false;
      }
    },
  );
  return outcomes.filter(Boolean).length;
}

/**
 * Read up to `MAX_HISTORY_PAGES` of history. `latest` is how far that got: the mailbox's history id
 * when it read to the end, else (`truncated`) the id of the last record read, so the rest is read
 * next time instead of skipped.
 */
async function readHistory(
  gmail: GmailApi,
  startHistoryId: string,
): Promise<{
  records: GmailHistoryRecord[];
  latest: string | undefined;
  truncated: boolean;
}> {
  const records: GmailHistoryRecord[] = [];
  let latest: string | undefined;
  let pageToken: string | undefined;
  for (let page = 0; page < MAX_HISTORY_PAGES; page++) {
    const result = await gmail.listHistory(startHistoryId, pageToken);
    records.push(...(result.history ?? []));
    latest = result.historyId ?? latest;
    pageToken = result.nextPageToken;
    if (pageToken === undefined) break;
  }
  const last = records.at(-1)?.id;
  if (pageToken === undefined || last === undefined) {
    return { records, latest, truncated: false };
  } else {
    return { records, latest: last, truncated: true };
  }
}

async function resync(
  gmail: GmailApi,
): Promise<{ messages: GmailMessage[]; latest: string }> {
  const latest = await gmail.getHistoryId();
  const stubs = await gmail.listMessages(RESYNC_QUERY, RESYNC_MESSAGES);
  const messages = await Promise.all(
    stubs.map((stub) => gmail.getMessage(stub.id)),
  );
  return { messages, latest };
}

/** FCM error codes by how many devices each refused a send. */
function errorCounts(results: readonly SendResult[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const result of results) {
    if (result.success) continue;
    const code = result.error?.code ?? "unknown";
    counts[code] = (counts[code] ?? 0) + 1;
  }
  return counts;
}

/**
 * Send one payload to the user's devices with these FCM tokens, in batches FCM takes, and log how
 * it went (counts and FCM error codes only). Returns the tokens still good, without those FCM says
 * are gone (which are forgotten).
 */
async function sendToDevices(
  deps: Deps,
  uid: string,
  tokens: readonly string[],
  data: NotificationData | ClearData,
): Promise<string[]> {
  const results: SendResult[] = [];
  for (let start = 0; start < tokens.length; start += FCM_MULTICAST_LIMIT) {
    results.push(
      ...(await deps.messenger.send(
        tokens.slice(start, start + FCM_MULTICAST_LIMIT),
        data,
      )),
    );
  }
  const stale = staleTokens(tokens, results);
  const succeeded = results.filter((result) => result.success).length;
  deps.log.info("push sent", {
    uid,
    kind: data.kind,
    devices: tokens.length,
    succeeded,
    failed: results.length - succeeded,
    errors: errorCounts(results),
    forgotten: stale.length,
  });
  if (stale.length > 0) await deps.store.removeDevices(uid, stale);
  return tokens.filter((entry) => !stale.includes(entry));
}

/** The outer subject encrypted mail goes out with; `OBSCURED_SUBJECT` in `shared/src/pgp`. */
const HIDDEN_SUBJECT = "...";

/**
 * The other Gmail threads the apps show in one row with `threadId`: those holding a message it
 * answers when it is an encrypted reply, found with the search `answeredSearch` makes.
 */
async function answeredThreadIds(
  gmail: GmailApi,
  threadId: string,
): Promise<string[]> {
  const messages = (await gmail.getThread(threadId)).messages ?? [];
  const subject = headerValue(messages[0]?.payload?.headers ?? [], "Subject");
  const { answers } = joinKeys(
    messages.map((message) => {
      const headers = message.payload?.headers ?? [];
      return {
        messageId: headerValue(headers, "Message-ID") ?? null,
        inReplyTo: headerValue(headers, "In-Reply-To") ?? null,
        references: headerValue(headers, "References") ?? null,
        date: Number(message.internalDate ?? 0),
      };
    }),
    subject?.trim() === HIDDEN_SUBJECT,
  );
  const query = answeredSearch(answers);
  if (query === null) return [];
  const found = await gmail.listMessages(query, 100);
  return [
    ...new Set(
      found.map((message) => message.threadId).filter((id) => id !== threadId),
    ),
  ];
}

/**
 * Send a notification about a message to the user's devices with these FCM tokens. Returns the
 * tokens still good.
 */
async function notify(
  deps: Deps,
  uid: string,
  email: string,
  message: GmailMessage,
  tokens: readonly string[],
): Promise<string[]> {
  if (tokens.length === 0) return [];
  const { token, nonce } = newActionToken(uid);
  const saved = await deps.store.saveAction(uid, nonce, {
    threadId: message.threadId,
    expiresAt: deps.now() + ACTION_TOKEN_TTL_MS,
  });
  if (!saved) {
    deps.log.info("push skipped, account gone", { uid });
    return [];
  }
  return sendToDevices(
    deps,
    uid,
    tokens,
    notificationData(message, email, token),
  );
}

/** Most times a push re-reads history after another run moved the stored id first. */
const MAX_CLAIM_ROUNDS = 3;

/**
 * Handle one Gmail push: read history since the stored id (or recent inbox mail when Gmail no
 * longer has it), notify about new inbox mail that is still unread (and, when the user asked for
 * that, outside a bundle), and ask Android phones to clear notifications of threads read or taken
 * out of the inbox.
 *
 * Nothing is acted on until the run has moved the stored id past what it read. When another run
 * moved it first, the range this run read is dropped and, while the stored id is still short of the
 * push's, read again from where the other run stopped (at most `MAX_CLAIM_ROUNDS` times), so mail
 * past the other run's range isn't lost.
 */
export async function handleGmailPush(
  deps: Deps,
  push: GmailPush,
): Promise<PushOutcome> {
  const account = await deps.store.accountByEmail(push.emailAddress);
  if (account === undefined) {
    return { result: "unknown-account", mailArrived: false };
  }
  const { uid } = account;
  if (
    account.historyId !== undefined &&
    compareHistoryIds(push.historyId, account.historyId) <= 0
  ) {
    return { result: "already-handled", mailArrived: false };
  }
  const access = await deps.gmailFor(uid);
  if (access === undefined) return { result: "no-grant", mailArrived: false };
  if (account.historyId === undefined) {
    await deps.store.claimHistory(uid, undefined, push.historyId, deps.now());
    return { result: "baseline", mailArrived: false };
  }

  let from: string = account.historyId;
  let lastSyncAt = account.lastSyncAt;
  for (let round = 1; ; round++) {
    const handled = await handleRange(
      deps,
      { ...account, lastSyncAt },
      from,
      access.gmail,
      push,
    );
    if (handled !== undefined) {
      return { result: "handled", mailArrived: handled.mailArrived };
    }
    const fresh = await deps.store.account(uid);
    if (
      fresh?.historyId === undefined ||
      compareHistoryIds(push.historyId, fresh.historyId) <= 0
    ) {
      return { result: "raced", mailArrived: false };
    }
    if (round >= MAX_CLAIM_ROUNDS) {
      deps.log.warn("history claim kept racing", { uid, rounds: round });
      return { result: "raced", mailArrived: false };
    }
    from = fresh.historyId;
    lastSyncAt = fresh.lastSyncAt;
  }
}

/** Thread ids of the messages these records added, each once. */
function addedThreadIds(records: readonly GmailHistoryRecord[]): string[] {
  const threadIds = new Set<string>();
  for (const record of records) {
    for (const { message } of record.messagesAdded ?? []) {
      threadIds.add(message.threadId);
    }
  }
  return [...threadIds];
}

/**
 * Read history from the account's stored id, claim the range read, and act on it; undefined (having
 * done nothing) when another run moved the stored id first.
 */
async function handleRange(
  deps: Deps,
  account: Account,
  from: string,
  gmail: GmailApi,
  push: GmailPush,
): Promise<{ mailArrived: boolean } | undefined> {
  const { uid } = account;
  let outcome: HistoryOutcome;
  let latest: string;
  let mailArrived: boolean;
  let truncated = false;
  try {
    const history = await readHistory(gmail, from);
    const threadIds = addedThreadIds(history.records);
    outcome = analyzeHistory(history.records);
    latest = history.latest ?? push.historyId;
    truncated = history.truncated;
    mailArrived = threadIds.length > 0;
  } catch (error) {
    if (!(error instanceof GmailError && error.status === 404)) throw error;
    deps.log.info("history too old, resyncing", { uid });
    const recent = await resync(gmail);
    outcome = analyzeRecent(recent.messages, account.lastSyncAt);
    latest = recent.latest;
    mailArrived = true;
  }
  if (truncated) {
    deps.log.info("history backlog left for the next push", { uid });
  } else if (compareHistoryIds(latest, push.historyId) < 0) {
    latest = push.historyId;
  }
  const notifyIds = outcome.notifyMessageIds.slice(-MAX_NOTIFICATIONS);
  // Read before the claim, so a failed read leaves the range to the next push instead of losing it.
  const tokens =
    notifyIds.length === 0 ? [] : await deps.store.deviceTokens(uid);
  if (!(await deps.store.claimHistory(uid, from, latest, deps.now()))) {
    return undefined;
  }

  await notifyNewMail(deps, account, gmail, notifyIds, tokens);
  await clearNotifications(deps, account, outcome.clearedThreadIds);
  return { mailArrived };
}

/** Ask the user's Android phones to clear notifications about these threads; a failure is only logged. */
async function clearNotifications(
  deps: Deps,
  account: Account,
  threadIds: readonly string[],
): Promise<void> {
  if (threadIds.length === 0) return;
  const { uid } = account;
  try {
    let tokens = await deps.store.androidTokens(uid);
    for (const data of clearData(threadIds, account.email)) {
      if (tokens.length === 0) break;
      tokens = await sendToDevices(deps, uid, tokens, data);
    }
  } catch (error) {
    deps.log.warn("clear push failed", { uid, message: messageOf(error) });
  }
}

/**
 * Whether a message's thread counts as bundled (`bundledOnArrival`): its labels, the thread's other
 * messages' included, have one that bundles, and (while the apps show todos) it has no todo, which
 * keeps it out of every bundle. False when Gmail or the todos can't say, so a failed check never
 * costs a notification.
 */
async function landsInBundle(
  deps: Deps,
  uid: string,
  gmail: GmailApi,
  message: GmailMessage,
  prefs: NotifyPrefs,
): Promise<boolean> {
  try {
    const thread = await gmail.getThread(message.threadId);
    const labels = [
      ...new Set([
        ...(message.labelIds ?? []),
        ...(thread.messages ?? []).flatMap((entry) => entry.labelIds ?? []),
      ]),
    ];
    if (!bundledOnArrival(labels, prefs.bundleOff)) {
      return false;
    } else if (!prefs.showTodos) {
      return true;
    } else {
      const hasTodo = await deps.store.hasEmailTodo(uid, message.threadId);
      return bundledOnArrival(labels, prefs.bundleOff, hasTodo);
    }
  } catch (error) {
    deps.log.warn("bundle check failed", { uid, message: messageOf(error) });
    return false;
  }
}

/** Choices when they can't be read: notify about all new mail, as by default. */
const DEFAULT_NOTIFY_PREFS: NotifyPrefs = {
  quietBundles: false,
  bundleOff: [],
  showTodos: true,
};

/**
 * Notify the devices with these FCM tokens about each of these messages that is still unread in
 * the inbox, and, when the user asked for quiet bundles, doesn't land in a bundle.
 */
async function notifyNewMail(
  deps: Deps,
  account: Account,
  gmail: GmailApi,
  messageIds: readonly string[],
  initialTokens: readonly string[],
): Promise<void> {
  if (messageIds.length === 0) return;
  const { uid } = account;
  const [fetched, prefs] = await Promise.all([
    Promise.allSettled(messageIds.map((id) => gmail.getMessage(id))),
    deps.store.notifyPrefs(uid).catch((error: unknown) => {
      deps.log.warn("notify settings unread", {
        uid,
        message: messageOf(error),
      });
      return DEFAULT_NOTIFY_PREFS;
    }),
  ]);
  let tokens: readonly string[] = initialTokens;
  let readOrArchived = 0;
  let bundled = 0;
  for (const [index, settled] of fetched.entries()) {
    const messageId = messageIds[index];
    try {
      if (settled.status === "rejected") throw settled.reason;
      const message = settled.value;
      const labels = message.labelIds ?? [];
      if (!isNotifiable(labels) || !labels.includes("UNREAD")) {
        readOrArchived++;
        continue;
      }
      if (
        prefs.quietBundles &&
        (await landsInBundle(deps, uid, gmail, message, prefs))
      ) {
        bundled++;
        continue;
      }
      tokens = await notify(deps, uid, account.email, message, tokens);
    } catch (error) {
      deps.log.warn("notify failed", {
        uid,
        messageId,
        message: messageOf(error),
      });
    }
  }
  deps.log.info("new mail", {
    uid,
    messages: messageIds.length,
    readOrArchived,
    bundled,
    devices: initialTokens.length,
  });
}

/** Why a notification action was refused. */
export class ActionRefused extends Error {
  /** `not-found` for an unknown or expired token, `reauth` when Gmail access is gone */
  readonly reason: "not-found" | "reauth";

  /** Create a refusal */
  constructor(reason: "not-found" | "reauth") {
    super(reason);
    this.name = "ActionRefused";
    this.reason = reason;
  }
}

/**
 * Run Done from a notification: archive the thread and drop its snooze (its todo, if any, is done
 * with it, as its state is the email's), and do the same for the threads an encrypted reply
 * answers, as the apps show them as one row.
 */
export async function handleNotificationAction(
  deps: Deps,
  input: NotificationActionInput,
): Promise<void> {
  const parts = parseActionToken(input.token);
  if (parts === undefined) throw new ActionRefused("not-found");
  const { uid, nonce } = parts;
  const stored = await deps.store.action(uid, nonce);
  if (stored === undefined || stored.expiresAt <= deps.now()) {
    throw new ActionRefused("not-found");
  }
  const access = await deps.gmailFor(uid);
  if (access === undefined) throw new ActionRefused("reauth");
  const { threadId } = stored;
  const threadIds = [threadId];
  try {
    threadIds.push(...(await answeredThreadIds(access.gmail, threadId)));
  } catch (error) {
    deps.log.warn("answered threads not found", {
      uid,
      message: messageOf(error),
    });
  }
  for (const id of threadIds) {
    await access.gmail.modifyThread(id, [], [LABEL.inbox]);
    await deps.store.clearSnooze(uid, id);
  }
  await deps.store.deleteAction(uid, nonce);
}
