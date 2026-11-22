import { beforeEach, describe, expect, it } from "bun:test";
import { parseActionToken } from "./action-token.js";
import {
  type GmailApi,
  GmailError,
  type GmailHeader,
  type GmailHistoryPage,
  type GmailLabel,
  type GmailMessage,
  type GmailThread,
} from "./gmail-api.js";
import {
  type Account,
  ActionRefused,
  type Deps,
  FCM_MULTICAST_LIMIT,
  handleGmailPush,
  handleNotificationAction,
  type NotifyPrefs,
  renewWatches,
  type SendResult,
  type Store,
  type StoredAction,
} from "./gmail-push.js";
import type { ClearData, NotificationData } from "./notification.js";

const UID = "google:1";
const EMAIL = "me@gmail.example.com";
const NOW = 1_800_000_000_000;
const DAY = 24 * 60 * 60 * 1000;

class MemoryStore implements Store {
  accounts = new Map<string, Account>();
  /** threads with a snooze doc */
  snoozed = new Set<string>();
  devices = ["device-a", "device-b"];
  /** the Android ones among them */
  android = ["device-b"];
  actions = new Map<string, StoredAction>();
  watches: { uid: string; historyId: string; expiresAt: number }[] = [];
  raceOnClaim = false;
  /** runs once at the next claim, as another run moving the stored id first */
  beforeClaim: (() => void) | undefined;

  async accountByEmail(email: string) {
    return [...this.accounts.values()].find((entry) => entry.email === email);
  }
  async account(uid: string) {
    return this.accounts.get(uid);
  }
  async accountsWithGrant() {
    return [...this.accounts.values()];
  }
  async saveWatch(uid: string, historyId: string, expiresAt: number) {
    this.watches.push({ uid, historyId, expiresAt });
    const account = this.accounts.get(uid)!;
    account.historyId ??= historyId;
  }
  async claimHistory(
    uid: string,
    from: string | undefined,
    to: string,
    syncedAt: number,
  ) {
    const account = this.accounts.get(uid)!;
    const other = this.beforeClaim;
    this.beforeClaim = undefined;
    other?.();
    if (this.raceOnClaim || account.historyId !== from) return false;
    account.historyId = to;
    account.lastSyncAt = syncedAt;
    return true;
  }
  tokenReads = 0;

  async clearSnooze(_uid: string, threadId: string) {
    this.snoozed.delete(threadId);
  }
  /** each thread's email todo text */
  emailTodos = new Map<string, string>();
  prefs: NotifyPrefs = { quietBundles: false, bundleOff: [], showTodos: true };
  prefsError = false;
  async notifyPrefs() {
    if (this.prefsError) throw new Error("unavailable");
    return this.prefs;
  }
  todoError = false;
  todoReads = 0;
  async emailTodoText(_uid: string, threadId: string) {
    this.todoReads++;
    if (this.todoError) throw new Error("unavailable");
    return this.emailTodos.get(threadId) ?? null;
  }
  tokensError = false;
  async deviceTokens() {
    this.tokenReads++;
    if (this.tokensError) throw new Error("unavailable");
    return [...this.devices];
  }
  async androidTokens() {
    return this.devices.filter((token) => this.android.includes(token));
  }
  async removeDevices(_uid: string, tokens: readonly string[]) {
    this.devices = this.devices.filter((token) => !tokens.includes(token));
  }
  async saveAction(_uid: string, nonce: string, action: StoredAction) {
    if (!this.accounts.has(UID)) return false;
    this.actions.set(nonce, action);
    return true;
  }
  async action(_uid: string, nonce: string) {
    return this.actions.get(nonce);
  }
  async deleteAction(_uid: string, nonce: string) {
    this.actions.delete(nonce);
  }
}

class FakeGmail implements GmailApi {
  messages = new Map<string, GmailMessage>();
  threads = new Map<string, GmailThread>();
  history: GmailHistoryPage[] = [];
  /** pages by start id, when a test reads from more than one */
  historyFrom = new Map<string, GmailHistoryPage[]>();
  historyStarts: string[] = [];
  historyGone = false;
  currentHistoryId = "900";
  modified: { id: string; add: string[]; remove: string[] }[] = [];
  watched: string[] = [];
  searches: string[] = [];
  /** thread holding each `Message-ID`, for `rfc822msgid:` searches */
  threadsByMessageId = new Map<string, string>();

  async watch(topicName: string) {
    this.watched.push(topicName);
    return { historyId: this.currentHistoryId, expiration: NOW + 7 * DAY };
  }
  async stop(): Promise<never> {
    throw new Error("unused");
  }
  async listHistory(start: string, pageToken?: string) {
    if (this.historyGone)
      throw new GmailError(404, "Requested entity was not found.");
    this.historyStarts.push(start);
    const index = pageToken === undefined ? 0 : Number(pageToken);
    return (this.historyFrom.get(start) ?? this.history)[index] ?? {};
  }
  async getMessage(id: string) {
    const message = this.messages.get(id);
    if (message === undefined) throw new GmailError(404, "not found");
    return message;
  }
  threadError = false;
  labels: GmailLabel[] = [{ id: "Label_1", name: "Work", type: "user" }];
  labelReads = 0;
  async listLabels() {
    this.labelReads++;
    return this.labels;
  }
  async getThread(id: string) {
    if (this.threadError) throw new GmailError(500, "backend error");
    const thread = this.threads.get(id);
    if (thread === undefined) throw new GmailError(404, "not found");
    return thread;
  }
  async modifyThread(id: string, add: string[], remove: string[]) {
    this.modified.push({ id, add, remove });
  }
  async listMessages(query: string) {
    this.searches.push(query);
    const wanted = [...query.matchAll(/rfc822msgid:(\S+)/g)].map(
      ([, id]) => id!,
    );
    if (wanted.length > 0) {
      return wanted.flatMap((id) => {
        const threadId = this.threadsByMessageId.get(id);
        return threadId === undefined ? [] : [{ id: `m-${id}`, threadId }];
      });
    }
    return [...this.messages.values()].map(({ id, threadId }) => ({
      id,
      threadId,
    }));
  }
  async getHistoryId() {
    return this.currentHistoryId;
  }
  async getDraftRaw(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
  async getDraftHeaders(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
  async sendDraft(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
  async deleteDraft(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
  async insertMessage(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
  async listSendAs(): Promise<never> {
    throw new Error("not used by Gmail push handling");
  }
}

function inboxMessage(id: string, threadId: string, extra: string[] = []) {
  return {
    id,
    threadId,
    labelIds: ["INBOX", "UNREAD", ...extra],
    snippet: "hello",
    internalDate: String(NOW - 1000),
    payload: {
      headers: [
        { name: "From", value: "Ana <ana@example.com>" },
        { name: "Subject", value: "Hi" },
      ],
    },
  } satisfies GmailMessage;
}

let store: MemoryStore;
let gmail: FakeGmail;
let sent: { tokens: string[]; data: NotificationData }[];
let cleared: { tokens: string[]; data: ClearData }[];
let sendResults: SendResult[] | undefined;
let grant: boolean;
let deps: Deps;
let infos: { message: string; fields?: Record<string, unknown> }[];

beforeEach(() => {
  store = new MemoryStore();
  store.accounts.set(UID, {
    uid: UID,
    email: EMAIL,
    historyId: "100",
    lastSyncAt: NOW - DAY,
  });
  gmail = new FakeGmail();
  sent = [];
  cleared = [];
  sendResults = undefined;
  grant = true;
  infos = [];
  deps = {
    store,
    gmailFor: async () => (grant ? { gmail, email: EMAIL } : undefined),
    messenger: {
      async send(tokens, data) {
        if (data.kind === "clear") cleared.push({ tokens, data });
        else sent.push({ tokens, data });
        return sendResults ?? tokens.map(() => ({ success: true }));
      },
    },
    topic: "projects/p/topics/gmail",
    now: () => NOW,
    log: {
      info(message, fields) {
        infos.push({ message, ...(fields === undefined ? {} : { fields }) });
      },
      warn() {},
    },
  };
});

describe("handleGmailPush", () => {
  it("notifies new unread inbox mail and moves the history id", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
        historyId: "105",
      },
    ];
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "104" }))
        .result,
    ).toBe("handled");
    expect(sent).toHaveLength(1);
    expect(sent[0]!.tokens).toEqual(["device-a", "device-b"]);
    expect(sent[0]!.data).toMatchObject({
      kind: "message",
      threadId: "t1",
      sender: "Ana",
      subject: "Hi",
    });
    const token = parseActionToken(sent[0]!.data.actionToken)!;
    expect(token.uid).toBe(UID);
    expect(store.actions.get(token.nonce)?.threadId).toBe("t1");
    expect(store.accounts.get(UID)?.historyId).toBe("105");
  });

  it("leaves the range to the next push when the devices can't be read", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
        historyId: "105",
      },
    ];
    store.tokensError = true;
    await expect(
      handleGmailPush(deps, { emailAddress: EMAIL, historyId: "104" }),
    ).rejects.toThrow("unavailable");
    expect(store.accounts.get(UID)?.historyId).toBe("100");

    store.tokensError = false;
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "105" });
    expect(sent).toHaveLength(1);
    expect(store.accounts.get(UID)?.historyId).toBe("105");
  });

  it("asks Android phones to clear threads read or archived elsewhere", async () => {
    gmail.history = [
      {
        history: [
          {
            id: "101",
            labelsRemoved: [
              { message: { id: "m1", threadId: "t1" }, labelIds: ["UNREAD"] },
              { message: { id: "m2", threadId: "t2" }, labelIds: ["INBOX"] },
            ],
          },
        ],
        historyId: "101",
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(sent).toHaveLength(0);
    expect(cleared).toEqual([
      {
        tokens: ["device-b"],
        data: { kind: "clear", account: EMAIL, threadIds: "t1,t2" },
      },
    ]);
  });

  it("sends no clear push without Android phones", async () => {
    store.android = [];
    gmail.history = [
      {
        history: [
          {
            id: "101",
            labelsRemoved: [
              { message: { id: "m1", threadId: "t1" }, labelIds: ["UNREAD"] },
            ],
          },
        ],
        historyId: "101",
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(cleared).toHaveLength(0);
  });

  it("follows history pages", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.messages.set("m2", inboxMessage("m2", "t2"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
        nextPageToken: "1",
      },
      {
        history: [
          { id: "102", messagesAdded: [{ message: inboxMessage("m2", "t2") }] },
        ],
        historyId: "102",
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "102" });
    expect(sent.map((entry) => entry.data.messageId)).toEqual(["m1", "m2"]);
    expect(store.tokenReads).toBe(1);
  });

  it("reads no devices when history added no mail", async () => {
    gmail.history = [
      {
        history: [
          {
            id: "101",
            labelsAdded: [
              {
                message: { id: "m1", threadId: "t1" },
                labelIds: ["STARRED"],
              },
            ],
          },
        ],
        historyId: "101",
      },
    ];
    expect(
      await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" }),
    ).toEqual({ result: "handled", mailArrived: false });
    expect(store.tokenReads).toBe(0);
  });

  it("skips mail already read or archived by the time it is fetched", async () => {
    gmail.messages.set("m1", {
      ...inboxMessage("m1", "t1"),
      labelIds: ["INBOX"],
    });
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(sent).toHaveLength(0);
  });

  it("resyncs recent inbox mail when history is too old", async () => {
    gmail.historyGone = true;
    gmail.currentHistoryId = "5000";
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.messages.set("m2", {
      ...inboxMessage("m2", "t2"),
      internalDate: String(NOW - 2 * DAY),
    });
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "4000" }))
        .result,
    ).toBe("handled");
    expect(gmail.searches).toEqual(["in:inbox newer_than:2d"]);
    expect(sent.map((entry) => entry.data.messageId)).toEqual(["m1"]);
    expect(store.accounts.get(UID)?.historyId).toBe("5000");
  });

  it("ignores pushes already handled, unknown accounts and lost grants", async () => {
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "100" }))
        .result,
    ).toBe("already-handled");
    expect(
      (await handleGmailPush(deps, { emailAddress: "x@y.z", historyId: "200" }))
        .result,
    ).toBe("unknown-account");
    grant = false;
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "200" }))
        .result,
    ).toBe("no-grant");
  });

  it("only records a baseline when no history id is stored", async () => {
    store.accounts.get(UID)!.historyId = undefined;
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "300" }))
        .result,
    ).toBe("baseline");
    expect(store.accounts.get(UID)?.historyId).toBe("300");
    expect(sent).toHaveLength(0);
  });

  it("sends nothing when another run claimed the range first", async () => {
    store.raceOnClaim = true;
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" }))
        .result,
    ).toBe("raced");
    expect(sent).toHaveLength(0);
  });

  it("reads again from where the run that won the claim stopped", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.messages.set("m2", inboxMessage("m2", "t2"));
    gmail.historyFrom.set("100", [
      {
        historyId: "103",
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
          { id: "103", messagesAdded: [{ message: inboxMessage("m2", "t2") }] },
        ],
      },
    ]);
    gmail.historyFrom.set("101", [
      {
        historyId: "103",
        history: [
          { id: "103", messagesAdded: [{ message: inboxMessage("m2", "t2") }] },
        ],
      },
    ]);
    store.beforeClaim = () => {
      store.accounts.get(UID)!.historyId = "101";
    };
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "103" }))
        .result,
    ).toBe("handled");
    expect(gmail.historyStarts).toEqual(["100", "101"]);
    expect(store.accounts.get(UID)?.historyId).toBe("103");
    expect(sent).toHaveLength(1);
    expect(sent[0]?.data.messageId).toBe("m2");
  });

  it("stops when the run that won the claim already passed the push", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    store.beforeClaim = () => {
      store.accounts.get(UID)!.historyId = "105";
    };
    expect(
      (await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" }))
        .result,
    ).toBe("raced");
    expect(gmail.historyStarts).toEqual(["100"]);
    expect(sent).toHaveLength(0);
  });

  it("forgets devices FCM no longer knows", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    sendResults = [
      { success: true },
      {
        success: false,
        error: { code: "messaging/registration-token-not-registered" },
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(store.devices).toEqual(["device-a"]);
  });

  it("logs each send's devices, results and FCM error codes, and nothing of the mail", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.messages.set("m2", {
      ...inboxMessage("m2", "t2"),
      labelIds: ["INBOX"],
    });
    gmail.history = [
      {
        history: [
          {
            id: "101",
            messagesAdded: [
              { message: inboxMessage("m1", "t1") },
              { message: inboxMessage("m2", "t2") },
            ],
          },
        ],
      },
    ];
    store.devices = ["device-a", "device-b", "device-c"];
    sendResults = [
      { success: true },
      { success: false, error: { code: "messaging/internal-error" } },
      {
        success: false,
        error: { code: "messaging/registration-token-not-registered" },
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(infos.filter(({ message }) => message === "push sent")).toEqual([
      {
        message: "push sent",
        fields: {
          uid: UID,
          kind: "message",
          devices: 3,
          succeeded: 1,
          failed: 2,
          errors: {
            "messaging/internal-error": 1,
            "messaging/registration-token-not-registered": 1,
          },
          forgotten: 1,
        },
      },
    ]);
    expect(infos.find(({ message }) => message === "new mail")).toEqual({
      message: "new mail",
      fields: {
        uid: UID,
        messages: 2,
        readOrArchived: 1,
        bundled: 0,
        devices: 3,
      },
    });
    const logged = JSON.stringify(infos);
    for (const secret of ["ana@example.com", "Ana", "Hi", "hello", EMAIL]) {
      expect(logged).not.toContain(secret);
    }
  });

  it("logs new mail with no device to send to", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    store.devices = [];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(sent).toHaveLength(0);
    expect(infos.find(({ message }) => message === "new mail")).toEqual({
      message: "new mail",
      fields: {
        uid: UID,
        messages: 1,
        readOrArchived: 0,
        bundled: 0,
        devices: 0,
      },
    });
  });

  it("sends to many devices in batches FCM takes, forgetting the dead ones", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    store.devices = Array.from({ length: 1200 }, (_, index) => `d${index}`);
    deps.messenger.send = async (tokens, data) => {
      if (data.kind === "message") sent.push({ tokens, data });
      return tokens.map((token) =>
        token === "d700"
          ? {
              success: false,
              error: { code: "messaging/registration-token-not-registered" },
            }
          : { success: true },
      );
    };
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(sent.map((entry) => entry.tokens.length)).toEqual([
      FCM_MULTICAST_LIMIT,
      FCM_MULTICAST_LIMIT,
      200,
    ]);
    expect(store.devices).toHaveLength(1199);
    expect(store.devices).not.toContain("d700");
  });

  it("notifies only the newest ten of a long backlog", async () => {
    const ids = Array.from({ length: 12 }, (_, index) => `m${index + 1}`);
    for (const id of ids) gmail.messages.set(id, inboxMessage(id, `t-${id}`));
    gmail.history = [
      {
        history: ids.map((id, index) => ({
          id: String(101 + index),
          messagesAdded: [{ message: inboxMessage(id, `t-${id}`) }],
        })),
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "120" });
    expect(sent.map(({ data }) => data.threadId)).toEqual(
      ids.slice(2).map((id) => `t-${id}`),
    );
  });

  it("notifies nobody once the account is gone", async () => {
    gmail.messages.set("m1", inboxMessage("m1", "t1"));
    gmail.history = [
      {
        history: [
          { id: "101", messagesAdded: [{ message: inboxMessage("m1", "t1") }] },
        ],
      },
    ];
    const claim = store.claimHistory.bind(store);
    store.claimHistory = async (...args) => {
      const claimed = await claim(...args);
      store.accounts.delete(UID);
      return claimed;
    };
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    expect(sent).toEqual([]);
    expect(store.actions.size).toBe(0);
  });

  it("records only as far as it read when history runs past its page limit", async () => {
    const pages: GmailHistoryPage[] = Array.from(
      { length: 25 },
      (_, index) => ({
        history: [{ id: String(101 + index) }],
        nextPageToken: String(index + 1),
        historyId: "900",
      }),
    );
    gmail.history = pages;
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "900" });
    expect(store.accounts.get(UID)?.historyId).toBe("120");
    gmail.history = [{ history: [{ id: "121" }], historyId: "901" }];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "901" });
    expect(gmail.historyStarts.at(-1)).toBe("120");
    expect(store.accounts.get(UID)?.historyId).toBe("901");
  });
});

describe("quiet bundles", () => {
  function arrive(...messages: GmailMessage[]) {
    for (const message of messages) {
      gmail.messages.set(message.id, message);
      gmail.threads.set(message.threadId, {
        id: message.threadId,
        messages: [message],
      });
    }
    const start = Number(store.accounts.get(UID)!.historyId) + 1;
    gmail.history = [
      {
        history: messages.map((message, index) => ({
          id: String(start + index),
          messagesAdded: [{ message }],
        })),
      },
    ];
    return handleGmailPush(deps, {
      emailAddress: EMAIL,
      historyId: String(start + messages.length),
    });
  }

  beforeEach(() => {
    store.prefs = { quietBundles: true, bundleOff: [], showTodos: true };
  });

  it("sends nothing for mail that lands in a bundle", async () => {
    await arrive(inboxMessage("m1", "t1", ["Label_1"]));
    expect(sent).toEqual([]);
    expect(infos.find((entry) => entry.message === "new mail")?.fields).toEqual(
      {
        uid: UID,
        messages: 1,
        readOrArchived: 0,
        bundled: 1,
        devices: 2,
      },
    );
  });

  it("notifies mail outside a bundle", async () => {
    await arrive(inboxMessage("m1", "t1"));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("counts a category other than Primary as bundled", async () => {
    await arrive(inboxMessage("m1", "t1", ["CATEGORY_PROMOTIONS"]));
    expect(sent).toEqual([]);
  });

  it("notifies Primary mail", async () => {
    await arrive(inboxMessage("m1", "t1", ["CATEGORY_PERSONAL"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("goes by every message of the thread, not only the new one", async () => {
    const earlier = { ...inboxMessage("m0", "t1", ["Label_1"]) };
    const message = inboxMessage("m1", "t1");
    gmail.messages.set("m1", message);
    gmail.threads.set("t1", { id: "t1", messages: [earlier, message] });
    gmail.history = [
      { history: [{ id: "101", messagesAdded: [{ message }] }] },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "120" });
    expect(sent).toEqual([]);
  });

  it("notifies mail of a label with bundling off", async () => {
    store.prefs = {
      quietBundles: true,
      bundleOff: ["Label_1"],
      showTodos: true,
    };
    await arrive(inboxMessage("m1", "t1", ["Label_1"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("sends nothing for mail whose todo's tag names a label that bundles", async () => {
    store.emailTodos.set("t1", "reply #work");
    await arrive(inboxMessage("m1", "t1"));
    expect(sent).toEqual([]);
  });

  it("notifies mail whose todo's tags name no label", async () => {
    store.emailTodos.set("t1", "reply #home");
    await arrive(inboxMessage("m1", "t1"));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("reads no todo when no label of the account bundles", async () => {
    gmail.labels = [{ id: "INBOX", name: "INBOX", type: "system" }];
    store.emailTodos.set("t1", "reply #work");
    await arrive(inboxMessage("m1", "t1"));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
    expect(store.todoReads).toBe(0);
  });

  it("reads the label list once a push, however many messages it checks", async () => {
    store.emailTodos.set("t2", "reply #work");
    await arrive(
      inboxMessage("m1", "t1"),
      inboxMessage("m2", "t2"),
      inboxMessage("m3", "t3"),
    );
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1", "t3"]);
    expect(gmail.labelReads).toBe(1);
  });

  it("goes by the todo's tags only while the apps show todos", async () => {
    store.prefs = { quietBundles: true, bundleOff: [], showTodos: false };
    store.emailTodos.set("t1", "reply #work");
    await arrive(inboxMessage("m1", "t1"));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("notifies when the todo can't be read", async () => {
    store.todoError = true;
    await arrive(inboxMessage("m1", "t1"));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("notifies a pinned thread, which never sits in a bundle", async () => {
    await arrive(inboxMessage("m1", "t1", ["Label_1", "STARRED"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("notifies bundled mail while the setting is off", async () => {
    store.prefs = { quietBundles: false, bundleOff: [], showTodos: true };
    await arrive(inboxMessage("m1", "t1", ["Label_1"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
  });

  it("notifies when Gmail or the settings can't be read", async () => {
    gmail.threadError = true;
    await arrive(inboxMessage("m1", "t1", ["Label_1"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1"]);
    gmail.threadError = false;
    store.prefsError = true;
    await arrive(inboxMessage("m2", "t2", ["Label_1"]));
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t1", "t2"]);
  });

  it("quiets every bundled message of a push, the label's only inbox thread included", async () => {
    await arrive(
      inboxMessage("m1", "t1", ["Label_1"]),
      inboxMessage("m2", "t2", ["Label_2"]),
      inboxMessage("m4", "t4"),
    );
    expect(sent.map(({ data }) => data.threadId)).toEqual(["t4"]);
  });
});

describe("watches", () => {
  it("renews every account's watch on the topic", async () => {
    expect(await renewWatches(deps)).toBe(1);
    expect(gmail.watched).toEqual(["projects/p/topics/gmail"]);
    expect(store.watches).toEqual([
      { uid: UID, historyId: "900", expiresAt: NOW + 7 * DAY },
    ]);
    expect(store.accounts.get(UID)?.historyId).toBe("100");
  });

  it("skips accounts without Gmail access", async () => {
    grant = false;
    expect(await renewWatches(deps)).toBe(0);
  });
});

describe("handleNotificationAction", () => {
  async function tokenFor(
    threadId: string,
    message: GmailMessage = inboxMessage("m1", threadId),
  ): Promise<string> {
    store.accounts.get(UID)!.historyId = "100";
    gmail.messages.set("m1", message);
    if (!gmail.threads.has(threadId)) {
      gmail.threads.set(threadId, { id: threadId, messages: [message] });
    }
    gmail.history = [
      {
        history: [{ id: "101", messagesAdded: [{ message }] }],
      },
    ];
    await handleGmailPush(deps, { emailAddress: EMAIL, historyId: "101" });
    return sent[0]!.data.actionToken;
  }

  it("archives and drops the snooze on done", async () => {
    const token = await tokenFor("t1");
    store.snoozed.add("t1");
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.modified).toEqual([{ id: "t1", add: [], remove: ["INBOX"] }]);
    expect(store.snoozed.has("t1")).toBe(false);
    expect(store.actions.size).toBe(0);
    expect(gmail.searches).toEqual([]);
  });

  function encryptedReply(threadId: string, replyHeaders: GmailHeader[]) {
    const message = inboxMessage("m1", threadId);
    message.payload.headers = [
      { name: "From", value: "Ana <ana@example.com>" },
      { name: "Subject", value: "..." },
      ...replyHeaders,
    ];
    return message;
  }

  it("also archives and unsnoozes every thread an encrypted reply answers", async () => {
    gmail.threadsByMessageId.set("first@example.com", "t-first");
    gmail.threadsByMessageId.set("root@example.com", "t-root");
    gmail.threadsByMessageId.set("other@example.com", "t-first");
    const token = await tokenFor(
      "t-reply",
      encryptedReply("t-reply", [
        { name: "In-Reply-To", value: "<first@example.com>" },
        {
          name: "References",
          value: "<root@example.com> <other@example.com> <first@example.com>",
        },
      ]),
    );
    store.snoozed.add("t-reply");
    store.snoozed.add("t-first");
    store.snoozed.add("t-root");
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.searches).toEqual([
      "rfc822msgid:first@example.com OR rfc822msgid:root@example.com OR rfc822msgid:other@example.com",
    ]);
    expect(gmail.modified).toEqual([
      { id: "t-reply", add: [], remove: ["INBOX"] },
      { id: "t-first", add: [], remove: ["INBOX"] },
      { id: "t-root", add: [], remove: ["INBOX"] },
    ]);
    expect(store.snoozed.size).toBe(0);
    expect(store.actions.size).toBe(0);
  });

  it("follows the ids of every message in a thread whose first message has the hidden subject", async () => {
    gmail.threadsByMessageId.set("parent@example.com", "t-first");
    const later = inboxMessage("m1", "t-reply");
    later.payload.headers = [
      { name: "Subject", value: "Re: ..." },
      { name: "In-Reply-To", value: "<parent@example.com>" },
    ];
    const first = { ...encryptedReply("t-reply", []), id: "m0" };
    gmail.threads.set("t-reply", { id: "t-reply", messages: [first, later] });
    const token = await tokenFor("t-reply", later);
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.searches).toEqual(["rfc822msgid:parent@example.com"]);
    expect(gmail.modified.map(({ id }) => id)).toEqual(["t-reply", "t-first"]);
  });

  it("doesn't follow a reply in a thread whose first message has a plain subject", async () => {
    gmail.threadsByMessageId.set("parent@example.com", "t-first");
    const first = inboxMessage("m0", "t-plain");
    const reply = encryptedReply("t-plain", [
      { name: "In-Reply-To", value: "<parent@example.com>" },
    ]);
    gmail.threads.set("t-plain", { id: "t-plain", messages: [first, reply] });
    const token = await tokenFor("t-plain", reply);
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.searches).toEqual([]);
    expect(gmail.modified.map(({ id }) => id)).toEqual(["t-plain"]);
  });

  it("archives only the notified thread when the answered mail isn't found", async () => {
    const token = await tokenFor(
      "t-reply",
      encryptedReply("t-reply", [
        { name: "In-Reply-To", value: "<gone@example.com>" },
      ]),
    );
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.searches).toEqual(["rfc822msgid:gone@example.com"]);
    expect(gmail.modified.map(({ id }) => id)).toEqual(["t-reply"]);
  });

  it("archives the notified thread when it can't be read", async () => {
    const token = await tokenFor("t1");
    gmail.threads.delete("t1");
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.modified.map(({ id }) => id)).toEqual(["t1"]);
  });

  it("doesn't search for an encrypted message that answers nothing", async () => {
    const token = await tokenFor("t-new", encryptedReply("t-new", []));
    await handleNotificationAction(deps, { token, action: "done" });
    expect(gmail.searches).toEqual([]);
    expect(gmail.modified.map(({ id }) => id)).toEqual(["t-new"]);
  });

  it("refuses unknown, used and expired tokens", async () => {
    const refusal = (promise: Promise<void>) =>
      promise.then(
        () => "ok",
        (error) => (error as ActionRefused).reason,
      );
    expect(
      await refusal(
        handleNotificationAction(deps, { token: "bogus", action: "done" }),
      ),
    ).toBe("not-found");
    const token = await tokenFor("t1");
    const done = { token, action: "done" as const };
    await handleNotificationAction(deps, done);
    expect(await refusal(handleNotificationAction(deps, done))).toBe(
      "not-found",
    );
    sent = [];
    const fresh = await tokenFor("t2");
    deps.now = () => NOW + 15 * DAY;
    expect(
      await refusal(handleNotificationAction(deps, { ...done, token: fresh })),
    ).toBe("not-found");
  });

  it("asks for sign-in when Gmail access is gone", async () => {
    const token = await tokenFor("t1");
    grant = false;
    const result = await handleNotificationAction(deps, {
      token,
      action: "done",
    }).catch((error) => error);
    expect(result).toBeInstanceOf(ActionRefused);
    expect((result as ActionRefused).reason).toBe("reauth");
  });
});
