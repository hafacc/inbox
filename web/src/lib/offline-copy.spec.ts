import { describe, expect, test } from "bun:test";
import type { SavedMessages } from "@inbox/shared/history-apply";
import { ENCRYPTED_MAIL_QUERY } from "@inbox/shared/offline-set";
import { FetchFailedError, GmailError } from "./gmail/client";
import type {
  GmailHistoryList,
  GmailThread,
  GmailThreadList,
} from "./gmail/types";
import {
  OfflineCopy,
  type OfflineGmail,
  type OfflineHost,
  type OfflineLock,
  type SavedThread,
  savedListLabels,
  savedThread,
} from "./offline-copy";

const NOW = 1_700_000_000_000;
const DAY = 86_400_000;

function thread(
  id: string,
  labels: string[],
  daysAgo: number,
  historyId = "10",
): GmailThread {
  return {
    id,
    historyId,
    messages: [
      {
        id: `${id}-m1`,
        threadId: id,
        labelIds: labels,
        internalDate: String(NOW - daysAgo * DAY),
      },
    ],
  };
}

type Fake = {
  copy: OfflineCopy;
  meta: Map<string, unknown>;
  index: Map<string, SavedThread>;
  saved: string[];
  relabeled: Map<string, SavedMessages>;
  // message ids added to saved threads' bodies, by thread
  appended: Map<string, string[]>;
  removed: string[];
  calls: string[];
  rebuilt: Promise<void>;
  // threads another tab changed, as the copy told the app
  reloaded: string[];
};

function fake(
  gmail: Partial<OfflineGmail>,
  start: {
    meta?: Record<string, unknown>;
    index?: [string, SavedThread][];
    snoozed?: string[];
    // another tab's store and lock, shared with it
    shared?: { fake: Fake; lock: OfflineLock };
    lock?: OfflineLock;
  } = {},
): Fake {
  const meta =
    start.shared?.fake.meta ?? new Map(Object.entries(start.meta ?? {}));
  const index = start.shared?.fake.index ?? new Map(start.index ?? []);
  const reloaded: string[] = [];
  const saved: string[] = [];
  const relabeled = new Map<string, SavedMessages>();
  const appended = new Map<string, string[]>();
  const removed: string[] = [];
  const calls: string[] = [];
  let markRebuilt = () => {};
  const rebuilt = new Promise<void>((resolve) => {
    markRebuilt = resolve;
  });
  const host: OfflineHost = {
    snoozed: () => new Set(start.snoozed ?? []),
    changedHere: () => new Set(),
    save: async (threads) => {
      saved.push(...threads.map((entry) => entry.id));
    },
    append: async (threadId, messages) => {
      appended.set(threadId, [
        ...(appended.get(threadId) ?? []),
        ...messages.map((message) => message.id),
      ]);
      return true;
    },
    relabel: async (messages) => {
      for (const [id, own] of messages) relabeled.set(id, own);
    },
    remove: (ids) => removed.push(...ids),
    rebuilt: () => markRebuilt(),
    caughtUp: () => {},
    changed: () => {},
    reloaded: (ids) => reloaded.push(...ids),
  };
  const logged = {
    getProfile: async () => {
      calls.push("profile");
      return { emailAddress: "a@b.c", historyId: "900" };
    },
    ...gmail,
  } as OfflineGmail;
  const copy = new OfflineCopy(
    logged,
    {
      entries: async () => [...index],
      put: async (entries) => {
        for (const [id, value] of entries) index.set(id, value);
      },
      delete: async (ids) => {
        for (const id of ids) index.delete(id);
      },
      meta: async (key) => meta.get(key),
      putMeta: async (key, value) => {
        meta.set(key, value);
      },
    },
    host,
    () => NOW,
    start.shared?.lock ?? start.lock,
  );
  return {
    copy,
    meta,
    index,
    saved,
    relabeled,
    appended,
    removed,
    calls,
    rebuilt,
    reloaded,
  };
}

test("with no days, new mail out of the inbox is saved only when encrypted", async () => {
  const added = (id: string) => ({
    message: { id: `${id}-m1`, threadId: id, labelIds: ["SENT"] },
  });
  const plain = thread("p", ["SENT"], 0, "120");
  const sealed = thread("e", ["SENT"], 0, "120");
  sealed.messages![0]!.snippet = "-----BEGIN PGP MESSAGE-----";
  const reads: string[] = [];
  const { copy, saved, meta } = fake(
    {
      listHistory: async () => ({
        historyId: "120",
        history: [{ id: "101", messagesAdded: [added("p"), added("e")] }],
      }),
      getThread: async (id, format) => {
        reads.push(`${id}:${format}`);
        return id === "e" ? sealed : plain;
      },
    },
    {
      meta: {
        offlineHistoryId: "100",
        offlineFilled: true,
        offlineDays: 0,
      },
    },
  );
  await copy.sync();
  await copy.settled();
  expect(reads).toEqual(["p:metadata", "e:metadata", "e:full"]);
  expect(saved).toEqual(["e"]);
  expect(meta.get("offlineHistoryId")).toBe("120");
});

describe("savedListLabels", () => {
  test("the inbox, leaving bundles out", () => {
    expect(
      savedListLabels({
        labelIds: ["INBOX"],
        q: "-label:news -category:social",
      }),
    ).toEqual(["INBOX"]);
  });
  test("a bundle and pinned", () => {
    expect(savedListLabels({ labelIds: ["INBOX", "Label_1"] })).toEqual([
      "INBOX",
      "Label_1",
    ]);
    expect(savedListLabels({ labelIds: ["INBOX", "STARRED"] })).toEqual([
      "INBOX",
      "STARRED",
    ]);
  });
  test("focus mode", () => {
    expect(savedListLabels({ labelIds: ["INBOX"], q: "is:starred" })).toEqual([
      "INBOX",
      "STARRED",
    ]);
  });
  test("other lists aren't answered", () => {
    expect(savedListLabels({ labelIds: ["SENT"] })).toBeNull();
    expect(savedListLabels({ labelIds: ["INBOX"], q: "from:x" })).toBeNull();
    expect(savedListLabels({ q: "in:inbox" })).toBeNull();
  });
});

test("a record keeps each message's labels and the newest date", () => {
  expect(savedThread(thread("t", ["INBOX"], 2), null)).toEqual({
    historyId: "10",
    messages: { "t-m1": ["INBOX"] },
    newestAt: NOW - 2 * DAY,
    openedAt: null,
    encrypted: false,
  });
});

test("a record says whether the thread is encrypted", () => {
  const sealed = thread("t", ["INBOX"], 2);
  sealed.messages![0]!.payload = {
    mimeType: "multipart/encrypted",
    headers: [],
    filename: "",
    body: { size: 0 },
  } as never;
  expect(savedThread(sealed, null).encrypted).toBe(true);
});

test("the first sync rebuilds: the inbox first, snoozed, drafts, then recent", async () => {
  const lists: Record<string, GmailThreadList> = {
    "in:inbox": {
      threads: [{ id: "a", historyId: "10" }],
    },
    "in:draft": {
      threads: [{ id: "d", historyId: "10" }],
    },
    "newer_than:30d": {
      threads: [
        { id: "a", historyId: "10" },
        { id: "b", historyId: "10" },
      ],
    },
    [ENCRYPTED_MAIL_QUERY]: {
      threads: [
        { id: "b", historyId: "10" },
        { id: "e", historyId: "10" },
      ],
    },
  };
  const threads: Record<string, GmailThread> = {
    a: thread("a", ["INBOX"], 1),
    b: thread("b", ["SENT"], 3),
    s: thread("s", ["INBOX"], 200),
    // A draft is saved at any age.
    d: thread("d", ["DRAFT"], 400),
    e: {
      ...thread("e", ["SENT"], 900),
      messages: [
        {
          ...thread("e", ["SENT"], 900).messages![0]!,
          snippet: "-----BEGIN PGP MESSAGE-----",
        },
      ],
    },
  };
  const { copy, meta, saved, rebuilt, calls } = fake(
    {
      listThreads: async (options) => lists[options.q ?? ""]!,
      getThread: async (id) => threads[id]!,
    },
    { snoozed: ["s"] },
  );
  expect(await copy.sync()).toBeNull();
  await rebuilt;
  expect(calls).toEqual(["profile"]);
  expect(saved).toEqual(["a", "s", "d", "b", "e"]);
  // Encrypted mail and drafts stay at any age.
  expect(copy.has("e")).toBe(true);
  expect(copy.has("d")).toBe(true);
  expect(copy.filled).toBe(true);
  expect(meta.get("offlineHistoryId")).toBe("900");
  expect(copy.savedIds(["INBOX"]).sort()).toEqual(["a", "s"]);
});

test("a first fill cut short by Gmail's limits isn't complete; one Gmail refused for good is", async () => {
  const fill = async (failure: GmailError) => {
    const { copy } = fake({
      listThreads: async (options) =>
        options.q === "in:inbox"
          ? {
              threads: [
                { id: "a", historyId: "10" },
                { id: "b", historyId: "10" },
              ],
            }
          : {},
      getThread: async (id) => {
        if (id === "b") throw failure;
        return thread(id, ["INBOX"], 1);
      },
    });
    await copy.sync();
    await copy.settled();
    return copy.filled;
  };
  expect(
    await fill(new GmailError(429, "slow down", "rateLimitExceeded")),
  ).toBe(false);
  expect(await fill(new GmailError(503, "unavailable"))).toBe(false);
  expect(await fill(new GmailError(400, "bad thread"))).toBe(true);
});

test("a rebuild skips threads saved at the same history and drops ones gone from every list", async () => {
  const old: SavedThread = savedThread(thread("old", ["INBOX"], 100), null);
  const opened: SavedThread = savedThread(
    thread("opened", ["INBOX"], 100),
    NOW - DAY,
  );
  const same = savedThread(thread("a", ["INBOX"], 1, "10"), null);
  const { copy, rebuilt, saved, index, relabeled } = fake(
    {
      listThreads: async (options) =>
        options.q === "in:inbox"
          ? { threads: [{ id: "a", historyId: "10" }] }
          : {},
      getThread: async (id) => thread(id, ["INBOX"], 1),
    },
    {
      index: [
        ["a", same],
        ["old", old],
        ["opened", opened],
      ],
    },
  );
  await copy.sync();
  await copy.settled();
  await rebuilt;
  expect(saved).toEqual([]);
  expect([...index.keys()].sort()).toEqual(["a", "opened"]);
  expect(relabeled.get("opened")).toEqual({ "opened-m1": [] });
});

test("history relabels saved threads, reads new mail and moves the id on", async () => {
  const history: GmailHistoryList = {
    historyId: "120",
    history: [
      {
        id: "101",
        labelsRemoved: [
          { message: { id: "a-m1", threadId: "a" }, labelIds: ["INBOX"] },
        ],
      },
      {
        id: "102",
        messagesAdded: [
          { message: { id: "n-m1", threadId: "n", labelIds: ["INBOX"] } },
        ],
      },
    ],
  };
  const { copy, meta, saved, relabeled } = fake(
    {
      listHistory: async () => history,
      getThread: async (id) => thread(id, ["INBOX"], 0, "120"),
    },
    {
      meta: { offlineHistoryId: "100", offlineFilled: true },
      index: [["a", savedThread(thread("a", ["INBOX"], 1), null)]],
    },
  );
  const outcome = await copy.sync();
  await copy.settled();
  expect(outcome === "busy" ? null : outcome?.records.length).toBe(2);
  expect(relabeled.get("a")).toEqual({ "a-m1": [] });
  expect(saved).toEqual(["n"]);
  expect(meta.get("offlineHistoryId")).toBe("120");
  expect(copy.has("a")).toBe(true);
});

test("new mail on a saved thread reads only the new message and adds it", async () => {
  const threadReads: string[] = [];
  const { copy, meta, saved, appended, relabeled, index } = fake(
    {
      listHistory: async () => ({
        historyId: "120",
        history: [
          {
            id: "101",
            messagesAdded: [
              {
                message: {
                  id: "a-m2",
                  threadId: "a",
                  labelIds: ["INBOX", "UNREAD"],
                },
              },
            ],
          },
        ],
      }),
      getThread: async (id) => {
        threadReads.push(id);
        return thread(id, ["INBOX"], 0, "120");
      },
      getMessage: async (id) => ({
        id,
        threadId: "a",
        historyId: "115",
        labelIds: ["INBOX", "UNREAD"],
        internalDate: String(NOW),
      }),
    },
    {
      meta: { offlineHistoryId: "100", offlineFilled: true },
      index: [["a", savedThread(thread("a", ["INBOX"], 1), null)]],
    },
  );
  await copy.sync();
  await copy.settled();
  expect(threadReads).toEqual([]);
  expect(saved).toEqual([]);
  expect(appended.get("a")).toEqual(["a-m2"]);
  expect(relabeled.get("a")).toEqual({
    "a-m1": ["INBOX"],
    "a-m2": ["INBOX", "UNREAD"],
  });
  expect(index.get("a")?.newestAt).toBe(NOW);
  expect(index.get("a")?.historyId).toBe("115");
  expect(meta.get("offlineHistoryId")).toBe("120");
});

test("a new message Gmail won't give has its thread read whole", async () => {
  const { copy, saved, appended } = fake(
    {
      listHistory: async () => ({
        historyId: "120",
        history: [
          {
            id: "101",
            messagesAdded: [
              { message: { id: "a-m2", threadId: "a", labelIds: ["INBOX"] } },
            ],
          },
        ],
      }),
      getThread: async (id) => thread(id, ["INBOX"], 0, "120"),
      getMessage: async () => {
        throw new GmailError(403, "no");
      },
    },
    {
      meta: { offlineHistoryId: "100", offlineFilled: true },
      index: [["a", savedThread(thread("a", ["INBOX"], 1), null)]],
    },
  );
  await copy.sync();
  await copy.settled();
  expect(appended.size).toBe(0);
  expect(saved).toEqual(["a"]);
});

test("a failed read keeps the history id, so the next sync tries again", async () => {
  const { copy, meta } = fake(
    {
      listHistory: async () => ({
        historyId: "120",
        history: [
          {
            id: "101",
            messagesAdded: [
              { message: { id: "n-m1", threadId: "n", labelIds: ["INBOX"] } },
            ],
          },
        ],
      }),
      getThread: async () => {
        throw new FetchFailedError(new TypeError("Failed to fetch"));
      },
    },
    { meta: { offlineHistoryId: "100", offlineFilled: true } },
  );
  await copy.sync();
  await copy.settled();
  expect(meta.get("offlineHistoryId")).toBe("100");
});

test("a thread Gmail refuses doesn't hold the id back and is read at the next sync", async () => {
  let refuse = true;
  const asked: string[] = [];
  const { copy, meta, saved } = fake(
    {
      listHistory: async (start) => ({
        historyId: start === "100" ? "120" : "130",
        history:
          start === "100"
            ? [
                {
                  id: "101",
                  messagesAdded: [
                    {
                      message: {
                        id: "n-m1",
                        threadId: "n",
                        labelIds: ["INBOX"],
                      },
                    },
                  ],
                },
              ]
            : [],
      }),
      getThread: async (id) => {
        asked.push(id);
        if (refuse) throw new GmailError(403, "no");
        return thread(id, ["INBOX"], 0, "130");
      },
    },
    { meta: { offlineHistoryId: "100", offlineFilled: true } },
  );
  await copy.sync();
  await copy.settled();
  expect(meta.get("offlineHistoryId")).toBe("120");
  expect(meta.get("offlineRetry")).toEqual(["n"]);
  refuse = false;
  await copy.sync();
  await copy.settled();
  expect(saved).toEqual(["n"]);
  expect(meta.get("offlineRetry")).toEqual([]);
  expect(asked).toEqual(["n", "n"]);
});

test("history syncs while a rebuild runs", async () => {
  let finishList = () => {};
  const listing = new Promise<void>((resolve) => {
    finishList = resolve;
  });
  const { copy, rebuilt } = fake(
    {
      listHistory: async () => ({ historyId: "120", history: [] }),
      listThreads: async () => {
        await listing;
        return {};
      },
    },
    { meta: { offlineHistoryId: "100", offlineFilled: true } },
  );
  await copy.setDays(10);
  await copy.setDays(20);
  // The history sync more days started alongside the rebuild ends; the rebuild still lists.
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(copy.busy).toBe(true);
  expect(await copy.sync()).not.toBeNull();
  finishList();
  await rebuilt;
});

test("an id Gmail no longer knows rebuilds", async () => {
  const { copy, rebuilt, meta } = fake(
    {
      listHistory: async () => {
        throw new GmailError(404, "gone");
      },
      listThreads: async () => ({}),
      getThread: async (id) => thread(id, ["INBOX"], 1),
    },
    { meta: { offlineHistoryId: "100", offlineFilled: true } },
  );
  expect(await copy.sync()).toBeNull();
  await rebuilt;
  expect(meta.get("offlineHistoryId")).toBe("900");
});

test("fewer days drop the threads no longer kept", async () => {
  const { copy, index } = fake(
    {},
    {
      index: [
        ["inbox", savedThread(thread("inbox", ["INBOX"], 40), null)],
        ["recent", savedThread(thread("recent", [], 10), null)],
      ],
    },
  );
  await copy.setDays(5);
  expect([...index.keys()]).toEqual(["inbox"]);
  expect(copy.days).toBe(5);
});

// A lock two tabs share, as Web Locks would give it.
function sharedLock(): OfflineLock {
  let held = false;
  const waiting: (() => void)[] = [];
  const release = () => {
    held = false;
    waiting.shift()?.();
  };
  return {
    take: async (wait) => {
      if (!held) {
        held = true;
        return release;
      } else if (!wait) {
        return null;
      } else {
        return new Promise((resolve) => {
          waiting.push(() => {
            held = true;
            resolve(release);
          });
        });
      }
    },
  };
}

test("one tab syncs at a time; the other reads what it saved once it's done", async () => {
  const lock = sharedLock();
  let finishHistory = () => {};
  const historyHeld = new Promise<void>((resolve) => {
    finishHistory = resolve;
  });
  const history: GmailHistoryList = {
    historyId: "120",
    history: [
      {
        id: "101",
        messagesAdded: [
          { message: { id: "n-m1", threadId: "n", labelIds: ["INBOX"] } },
        ],
      },
    ],
  };
  const first = fake(
    {
      listHistory: async () => {
        await historyHeld;
        return history;
      },
      getThread: async (id) => thread(id, ["INBOX"], 0, "120"),
    },
    { meta: { offlineHistoryId: "100", offlineFilled: true }, lock },
  );
  const otherCalls: string[] = [];
  const second = fake(
    {
      listHistory: async (start) => {
        otherCalls.push(`history:${start}`);
        return { historyId: start, history: [] };
      },
      getThread: async (id) => {
        otherCalls.push(id);
        return thread(id, ["INBOX"], 0, "120");
      },
    },
    { shared: { fake: first, lock } },
  );
  await Promise.all([first.copy.ready, second.copy.ready]);
  const syncing = first.copy.sync();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(await second.copy.sync()).toBe("busy");
  expect(await second.copy.whileHeld(async () => "pruned")).toBeNull();
  finishHistory();
  await syncing;
  await first.copy.settled();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(otherCalls).toEqual([]);
  expect(second.copy.has("n")).toBe(true);
  expect(second.reloaded).toEqual(["n"]);
  // Its next sync reads history from where the other tab left it.
  await second.copy.sync();
  await second.copy.settled();
  expect(otherCalls).toEqual(["history:120"]);
  expect(await second.copy.whileHeld(async () => "pruned")).toBe("pruned");
});

test("a saved draft Gmail's listing doesn't name yet is listed, so it opens offline", async () => {
  let listings = 0;
  const { copy, meta } = fake(
    {
      listHistory: async () => ({ historyId: "120", history: [] }),
      listDrafts: async () => {
        listings += 1;
        return [{ id: "r1", message: { id: "d-m1", threadId: "d" } }];
      },
    },
    {
      meta: { offlineHistoryId: "100", offlineFilled: true },
      index: [["d", savedThread(thread("d", ["DRAFT"], 1), null)]],
    },
  );
  await copy.sync();
  await copy.settled();
  expect(copy.draftRefs).toEqual([
    { draftId: "r1", messageId: "d-m1", threadId: "d" },
  ]);
  expect(meta.get("offlineDrafts")).toEqual({
    refs: [{ draftId: "r1", messageId: "d-m1", threadId: "d" }],
    unmatched: [],
  });
  // Named now: the next sync lists nothing.
  await copy.sync();
  await copy.settled();
  expect(listings).toBe(1);
});

test("a sent draft stays recorded after its doc is gone, until the saved copy drops it", async () => {
  const refs = {
    refs: [{ draftId: "r1", messageId: "d-m1", threadId: "d" }],
    unmatched: [],
  };
  const { copy, meta } = fake(
    {},
    {
      meta: {
        offlineHistoryId: "100",
        offlineFilled: true,
        offlineDrafts: refs,
      },
      index: [["d", savedThread(thread("d", ["DRAFT"], 1), null)]],
    },
  );
  await copy.noteSent([{ draftId: "r1", status: "sent" }], []);
  await copy.noteSent([], []);
  expect(copy.sentDrafts.map((draft) => draft.messageId)).toEqual(["d-m1"]);
  // History later says the message went: the next sync forgets the record.
  const later = fake(
    { listHistory: async () => ({ historyId: "120", history: [] }) },
    {
      meta: Object.fromEntries(meta),
      index: [["d", savedThread(thread("d", ["SENT"], 1), null)]],
    },
  );
  await later.copy.ready;
  expect(later.copy.sentDrafts).toHaveLength(1);
  await later.copy.sync();
  await later.copy.settled();
  expect(later.copy.sentDrafts).toEqual([]);
});
