import { expect, test } from "bun:test";
import {
  FetchFailedError,
  GmailClient,
  GmailError,
  isRateLimited,
  isRetryable,
  mapLimited,
  RateGate,
} from "./client";

// A clock that jumps ahead to each timer it is asked for, so pauses pass at once.
function fakeClock() {
  let now = 1_000;
  const waits: number[] = [];
  return {
    waits,
    now: () => now,
    setTimer: (callback: () => void, ms: number) => {
      waits.push(ms);
      now += ms;
      queueMicrotask(callback);
    },
  };
}

function quickGate(): RateGate {
  const clock = fakeClock();
  return new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: null,
  });
}

function tokens() {
  let issued = 0;
  return {
    get: async () => `token-${issued + 1}`,
    invalidate: () => {
      issued++;
    },
  };
}

test("lists threads with repeated labelIds and retries once with a fresh token on 401", async () => {
  const calls: { url: string; auth: string }[] = [];
  const client = new GmailClient(tokens(), async (url, init) => {
    const auth = new Headers(init?.headers).get("Authorization") ?? "";
    calls.push({ url, auth });
    if (calls.length === 1) return new Response("", { status: 401 });
    return Response.json({ threads: [{ id: "t1" }] });
  });
  const page = await client.listThreads({
    labelIds: ["INBOX", "STARRED"],
    maxResults: 20,
  });
  expect(page.threads).toEqual([{ id: "t1" }]);
  expect(calls.map((call) => call.auth)).toEqual([
    "Bearer token-1",
    "Bearer token-2",
  ]);
  expect(calls[1]!.url).toBe(
    "https://gmail.googleapis.com/gmail/v1/users/me/threads?labelIds=INBOX&labelIds=STARRED&maxResults=20",
  );
});

test("reports Gmail's error message and status", async () => {
  const client = new GmailClient(tokens(), async () =>
    Response.json({ error: { message: "Not Found" } }, { status: 404 }),
  );
  const failure = await client
    .getThread("gone", "full")
    .catch((error) => error);
  expect(failure).toBeInstanceOf(GmailError);
  expect(failure).toMatchObject({ status: 404, message: "Not Found" });
});

test("modify sends JSON with the label changes", async () => {
  let sent: unknown = null;
  const client = new GmailClient(tokens(), async (_url, init) => {
    sent = JSON.parse(String(init?.body));
    return Response.json({ id: "t1" });
  });
  await client.modifyThread("t1", ["INBOX"], ["UNREAD"]);
  expect(sent).toEqual({ addLabelIds: ["INBOX"], removeLabelIds: ["UNREAD"] });
});

test("mapLimited keeps order and caps how many run at once", async () => {
  let running = 0;
  let peak = 0;
  const results = await mapLimited([1, 2, 3, 4, 5], 2, async (value) => {
    running++;
    peak = Math.max(peak, running);
    await Bun.sleep(5);
    running--;
    if (value === 3) throw new Error("three");
    return value * 10;
  });
  expect(peak).toBe(2);
  expect(results.map((result) => result.status)).toEqual([
    "fulfilled",
    "fulfilled",
    "rejected",
    "fulfilled",
    "fulfilled",
  ]);
  expect(results[4]).toEqual({ status: "fulfilled", value: 50 });
});

test("saves a draft in its thread, replaces it, and deletes it without a body", async () => {
  const calls: { url: string; method: string; body: unknown }[] = [];
  const client = new GmailClient(tokens(), async (url, init) => {
    calls.push({
      url,
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : null,
    });
    if (init?.method === "DELETE") return new Response(null, { status: 204 });
    return Response.json({ id: "d1", message: { id: "m1", threadId: "t1" } });
  });
  expect((await client.createDraft("cmF3", "t1")).id).toBe("d1");
  await client.updateDraft("d1", "cmF3", null);
  await client.deleteDraft("d1");
  expect(calls).toEqual([
    {
      url: "https://gmail.googleapis.com/gmail/v1/users/me/drafts",
      method: "POST",
      body: { message: { raw: "cmF3", threadId: "t1" } },
    },
    {
      url: "https://gmail.googleapis.com/gmail/v1/users/me/drafts/d1",
      method: "PUT",
      body: { id: "d1", message: { raw: "cmF3" } },
    },
    {
      url: "https://gmail.googleapis.com/gmail/v1/users/me/drafts/d1",
      method: "DELETE",
      body: null,
    },
  ]);
});

test("reads Google's error reason and treats usage limits as worth retrying", async () => {
  const failWith = (status: number, body: unknown) =>
    new GmailClient(
      tokens(),
      async () => Response.json(body, { status }),
      quickGate(),
    )
      .getThread("t1", "full")
      .catch((error: unknown) => error);
  const userLimit = await failWith(403, {
    error: {
      message: "User Rate Limit Exceeded",
      errors: [{ reason: "userRateLimitExceeded", domain: "usageLimits" }],
    },
  });
  expect(userLimit).toMatchObject({
    status: 403,
    reason: "userRateLimitExceeded",
  });
  expect(isRateLimited(userLimit)).toBe(true);
  expect(isRetryable(userLimit)).toBe(true);
  const errorInfo = await failWith(403, {
    error: { message: "Quota", details: [{ reason: "RATE_LIMIT_EXCEEDED" }] },
  });
  expect(isRetryable(errorInfo)).toBe(true);
  const forbidden = await failWith(403, {
    error: { message: "Forbidden", errors: [{ reason: "forbidden" }] },
  });
  expect(isRateLimited(forbidden)).toBe(false);
  expect(isRetryable(forbidden)).toBe(false);
  expect(isRetryable(await failWith(429, {}))).toBe(true);
  expect(isRetryable(await failWith(503, {}))).toBe(true);
  expect(isRetryable(await failWith(404, {}))).toBe(false);
  expect(
    isRetryable(new FetchFailedError(new TypeError("Failed to fetch"))),
  ).toBe(true);
  expect(isRetryable(new TypeError("x is undefined"))).toBe(false);
});

test("waits out Retry-After on a usage limit, tries a read again, and backs off writes", async () => {
  const clock = fakeClock();
  const heard: number[] = [];
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: null,
  });
  gate.subscribe((until) => heard.push(until));
  let calls = 0;
  const client = new GmailClient(
    tokens(),
    async () => {
      calls++;
      return calls === 1
        ? Response.json(
            { error: { message: "Quota exceeded" } },
            { status: 429, headers: { "Retry-After": "7" } },
          )
        : Response.json({ id: "t1" });
    },
    gate,
  );
  expect(await client.getThread("t1", "metadata")).toEqual({ id: "t1" });
  expect(calls).toBe(2);
  expect(clock.waits).toEqual([7_000]);
  expect(heard).toEqual([8_000, 0]);

  const limited = new GmailClient(
    tokens(),
    async () => Response.json({ error: { message: "Quota" } }, { status: 429 }),
    gate,
  );
  const error = await limited
    .modifyThread("t1", [], ["INBOX"])
    .catch((caught: unknown) => caught);
  expect(isRateLimited(error)).toBe(true);
  expect(gate.pausedUntil).toBe(clock.now() + 1_000);
});

test("sends one request for the same read asked for twice at once", async () => {
  let calls = 0;
  const client = new GmailClient(tokens(), async () => {
    calls++;
    return Response.json({ id: "t1" });
  });
  const [first, second] = await Promise.all([
    client.getThread("t1", "full"),
    client.getThread("t1", "full"),
  ]);
  expect(first).toEqual(second);
  expect(calls).toBe(1);
});

test("every write has a deadline, creates too, so one that never answers can't hold the queue", async () => {
  const signals = new Map<string, boolean>();
  const client = new GmailClient(
    tokens(),
    async (url, init) => {
      const path = new URL(url).pathname.replace("/gmail/v1/users/me", "");
      signals.set(`${init?.method} ${path}`, init?.signal != null);
      return Response.json({ id: "x", message: { id: "m", threadId: "t" } });
    },
    quickGate(),
  );
  await client.createDraft("cmF3", "t1");
  await client.createLabel({ name: "Trips" });
  await client.createFilter({ criteria: { from: "a@b.c" }, action: {} });
  await client.updateDraft("d1", "cmF3", "t1");
  await client.modifyThread("t1", ["STARRED"], []);
  await client.deleteDraft("d1");
  expect(Object.fromEntries(signals)).toEqual({
    "POST /drafts": true,
    "POST /labels": true,
    "POST /settings/filters": true,
    "PUT /drafts/d1": true,
    "POST /threads/t1/modify": true,
    "DELETE /drafts/d1": true,
  });
});

test("a draft is created and updated in its thread", async () => {
  const bodies: unknown[] = [];
  const client = new GmailClient(
    tokens(),
    async (_url, init) => {
      bodies.push(JSON.parse(String(init?.body)));
      return Response.json({ id: "d1", message: { id: "m", threadId: "t1" } });
    },
    quickGate(),
  );
  await client.createDraft("cmF3", "t1");
  await client.updateDraft("d1", "cmF3", "t1");
  expect(bodies).toEqual([
    { message: { raw: "cmF3", threadId: "t1" } },
    { id: "d1", message: { raw: "cmF3", threadId: "t1" } },
  ]);
});

test("an open joined to a background read in backoff notes the read, raised, with Google's reason", async () => {
  const lines: string[] = [];
  const storage = globalThis.localStorage;
  const info = console.info;
  Object.defineProperty(globalThis, "localStorage", {
    value: { getItem: () => "1" },
    configurable: true,
  });
  console.info = (line: unknown) => void lines.push(String(line));
  let tries = 0;
  try {
    const client = new GmailClient(
      tokens(),
      async () => {
        tries++;
        return tries === 1
          ? Response.json(
              {
                error: {
                  message: "limit",
                  errors: [{ reason: "userRateLimitExceeded" }],
                },
              },
              { status: 403 },
            )
          : Response.json({ id: "t1", messages: [] });
      },
      quickGate(),
    );
    const { openStarted } = await import("../open-timing");
    const background = client.getThread("t1", "full", "background");
    openStarted("t1");
    const opened = await client.getThread("t1", "full");
    await background;
    expect(opened.id).toBe("t1");
    expect(lines.some((line) => line.includes("visible joined"))).toBe(true);
    tries = 0;
    openStarted("t2");
    await client.getThread("t2", "full");
    expect(
      lines.some((line) => line.includes("visible 403 userRateLimitExceeded")),
    ).toBe(true);
  } finally {
    console.info = info;
    Object.defineProperty(globalThis, "localStorage", {
      value: storage,
      configurable: true,
    });
  }
});
