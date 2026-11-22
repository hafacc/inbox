import { expect, test } from "bun:test";
import {
  type ConnectionEvent,
  MAX_CONCURRENT,
  SAVING_HOLD_MS,
  SAVING_SPACING_MS,
} from "@inbox/shared/gmail-pace";
import { initializeApp } from "firebase/app";
import { getFunctions, httpsCallableFromURL } from "firebase/functions";
import { GmailClient } from "./client";
import {
  type BackoffEvent,
  type CallTicket,
  FetchFailedError,
  GmailError,
  isNetworkError,
  isTimeout,
  isUnreachableCall,
  RateGate,
  reportingFetch,
  requestJson,
  UnreachableError,
} from "./request";

const TOKENS = { get: async () => "token", invalidate: () => {} };
const LIMITED = () =>
  Response.json(
    {
      error: {
        message: "User Rate Limit Exceeded",
        errors: [{ reason: "userRateLimitExceeded" }],
      },
    },
    { status: 403 },
  );

// A clock that only moves when told; timers wait for it.
function manualClock() {
  let now = 1_000;
  const timers: { at: number; callback: () => void }[] = [];
  return {
    now: () => now,
    setTimer: (callback: () => void, ms: number) => {
      timers.push({ at: now + ms, callback });
    },
    advance(ms: number) {
      now += ms;
      for (const timer of timers.filter((entry) => entry.at <= now)) {
        timers.splice(timers.indexOf(timer), 1);
        timer.callback();
      }
    },
  };
}

// A clock that jumps ahead to each timer it is asked for, so pauses pass at once.
function jumpingClock() {
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

function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

test("runs at most MAX_CONCURRENT calls at once, the next as one ends", async () => {
  const gate = new RateGate({ log: null });
  const started: number[] = [];
  const releases: (() => void)[] = [];
  for (let index = 0; index < MAX_CONCURRENT + 2; index++) {
    void gate.acquire({ priority: "visible" }).then((release) => {
      started.push(index);
      releases.push(release);
    });
  }
  await settle();
  expect(started).toEqual([0, 1, 2, 3]);
  expect(gate.running).toBe(MAX_CONCURRENT);
  releases[0]!();
  releases[0]!();
  await settle();
  expect(started).toEqual([0, 1, 2, 3, 4]);
});

test("lets the most urgent waiting call go first, and a raised one jumps ahead", async () => {
  const gate = new RateGate({ log: null });
  const held = await Promise.all(
    Array.from({ length: MAX_CONCURRENT }, () =>
      gate.acquire({ priority: "visible" }),
    ),
  );
  const order: string[] = [];
  const wait = (name: string, ticket: CallTicket) =>
    void gate.acquire(ticket).then((release) => {
      order.push(name);
      release();
    });
  const prefetch: CallTicket = { priority: "background" };
  wait("refetch", { priority: "background" });
  wait("prefetch", prefetch);
  wait("list", { priority: "visible" });
  wait("modify", { priority: "action" });
  gate.raise(prefetch, "action");
  gate.raise(prefetch, "background");
  expect(prefetch.priority).toBe("action");
  held[0]!();
  await settle();
  // The raised call asked before the action, so it goes first among the two.
  expect(order).toEqual(["prefetch", "modify", "list", "refetch"]);
});

test("an open starts at once while the saved copy fills, which waits for it and is spaced", async () => {
  const clock = manualClock();
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    log: null,
  });
  const started: string[] = [];
  const releases = new Map<string, () => void>();
  const ask = (name: string, priority: CallTicket["priority"]) =>
    void gate.acquire({ priority }).then((release) => {
      started.push(name);
      releases.set(name, release);
    });
  for (const name of ["fill1", "fill2", "fill3"]) ask(name, "saving");
  await settle();
  // One saved-copy read starts; the next only after the spacing.
  expect(started).toEqual(["fill1"]);
  clock.advance(SAVING_SPACING_MS);
  await settle();
  expect(started).toEqual(["fill1", "fill2"]);
  ask("open", "visible");
  await settle();
  expect(started).toEqual(["fill1", "fill2", "open"]);
  releases.get("fill1")!();
  clock.advance(SAVING_SPACING_MS);
  await settle();
  // While the open runs, the saved copy waits.
  expect(started).toEqual(["fill1", "fill2", "open"]);
  releases.get("open")!();
  await settle();
  expect(started).toEqual(["fill1", "fill2", "open", "fill3"]);
});

test("after a usage limit the saved copy stays held past the pause while an open goes", async () => {
  const clock = manualClock();
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: null,
  });
  const started: string[] = [];
  const ask = (name: string, priority: CallTicket["priority"]) =>
    void gate.acquire({ priority }).then((release) => {
      started.push(name);
      release();
    });
  const until = gate.slowDown(
    new GmailError(403, "limit", "userRateLimitExceeded"),
    null,
    "saving",
  );
  ask("fill", "saving");
  ask("open", "visible");
  await settle();
  expect(started).toEqual([]);
  clock.advance(until - clock.now());
  await settle();
  expect(started).toEqual(["open"]);
  clock.advance(SAVING_HOLD_MS - 1);
  await settle();
  expect(started).toEqual(["open"]);
  clock.advance(1);
  await settle();
  expect(started).toEqual(["open", "fill"]);
});

test("holds every call while a pause lasts, then lets them go", async () => {
  const clock = manualClock();
  const events: BackoffEvent[] = [];
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: (event) => events.push(event),
  });
  const until = gate.slowDown(
    new GmailError(403, "limit", "userRateLimitExceeded"),
    null,
    "background",
  );
  expect(until).toBe(2_000);
  expect(events).toEqual([
    {
      status: 403,
      reason: "userRateLimitExceeded",
      strike: 1,
      pauseMs: 1_000,
      priority: "background",
    },
  ]);
  let started = false;
  void gate.acquire({ priority: "action" }).then(() => {
    started = true;
  });
  await settle();
  expect(started).toBe(false);
  clock.advance(999);
  await settle();
  expect(started).toBe(false);
  clock.advance(1);
  await settle();
  expect(started).toBe(true);
});

test("counts limits that come back during a pause as one strike, and starts over after a call passes", () => {
  const clock = manualClock();
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: null,
  });
  const limit = new GmailError(429, "limit");
  gate.slowDown(limit, null, "visible");
  gate.slowDown(limit, null, "visible");
  expect(gate.pausedUntil).toBe(2_000);
  clock.advance(500);
  gate.slowDown(limit, 30, "visible");
  expect(gate.pausedUntil).toBe(2_000);
  clock.advance(500);
  gate.slowDown(limit, null, "visible");
  expect(gate.pausedUntil).toBe(4_000);
  clock.advance(2_000);
  gate.passed();
  expect(gate.pausedUntil).toBe(0);
  gate.slowDown(limit, 3, "visible");
  expect(gate.pausedUntil).toBe(7_000);
});

test("spreads a pause by up to half at random", () => {
  const gate = new RateGate({ now: () => 0, random: () => 1, log: null });
  expect(gate.slowDown(new GmailError(429, "limit"), null, "visible")).toBe(
    1_500,
  );
});

test("tries a read four more times after usage limits, then gives the error; a write once", async () => {
  const clock = jumpingClock();
  const gate = new RateGate({
    now: clock.now,
    setTimer: clock.setTimer,
    random: () => 0,
    log: null,
  });
  let calls = 0;
  const limited = async () => {
    calls++;
    return LIMITED();
  };
  const read = await requestJson(
    TOKENS,
    limited,
    "https://example/r",
    {},
    {
      gate,
    },
  ).catch((error: unknown) => error);
  expect(read).toMatchObject({ status: 403, reason: "userRateLimitExceeded" });
  expect(calls).toBe(5);
  expect(clock.waits).toEqual([1_000, 2_000, 4_000, 8_000]);
  calls = 0;
  await requestJson(
    TOKENS,
    limited,
    "https://example/w",
    { method: "POST" },
    { gate },
  ).catch(() => {});
  expect(calls).toBe(1);
  expect(gate.running).toBe(0);
});

test("fails a call with no answer in time as a timeout and frees its turn", async () => {
  const gate = new RateGate({ log: null });
  const hanging = (_url: string, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () =>
        reject(new DOMException("aborted", "AbortError")),
      );
    });
  const error = await requestJson(
    TOKENS,
    hanging,
    "https://example/r",
    {},
    { gate, timeoutMs: 5 },
  ).catch((caught: unknown) => caught);
  expect(isTimeout(error)).toBe(true);
  expect(gate.running).toBe(0);
});

test("shares one request for a read asked twice, raised to the more urgent asker", async () => {
  const gate = new RateGate({ log: null });
  const held = await Promise.all(
    Array.from({ length: MAX_CONCURRENT }, () =>
      gate.acquire({ priority: "visible" }),
    ),
  );
  const fetched: string[] = [];
  const client = new GmailClient(
    TOKENS,
    async (url) => {
      fetched.push(new URL(url).pathname.split("/").at(-1)!);
      return Response.json({ id: "t" });
    },
    gate,
  );
  const first = client.getThread("t1", "metadata", "background");
  const other = client.getThread("t2", "metadata", "visible");
  const again = client.getThread("t1", "metadata", "action");
  held[0]!();
  await Promise.all([first, other, again]);
  expect(await first).toBe(await again);
  expect(fetched).toEqual(["t1", "t2"]);
});

test("asks a list row's thread for the headers and fields a summary reads", async () => {
  let asked = "";
  const client = new GmailClient(
    TOKENS,
    async (url) => {
      asked = url;
      return Response.json({ id: "t1" });
    },
    new RateGate({ log: null }),
  );
  await client.getThread("t1", "metadata");
  const params = new URL(asked).searchParams;
  expect(params.getAll("metadataHeaders")).toEqual([
    "From",
    "Subject",
    "Message-ID",
    "In-Reply-To",
    "References",
  ]);
  expect(params.get("fields")).toContain("payload/headers");
});

test("counts a callable that never reached its server as unreachable, as the Firebase SDK reports it", async () => {
  const app = initializeApp(
    { projectId: "demo-probe", apiKey: "probe", appId: "probe" },
    "unreachable-probe",
  );
  const functions = getFunctions(app);
  const refused = await httpsCallableFromURL(
    functions,
    "http://127.0.0.1:1/gmailToken",
  )().catch((caught: unknown) => caught);
  expect(isUnreachableCall(refused)).toBe(true);
  const timedOut = await httpsCallableFromURL(
    functions,
    "http://127.0.0.1:1/gmailToken",
    { timeout: 1 },
  )().catch((caught: unknown) => caught);
  expect(isUnreachableCall(timedOut)).toBe(true);
  expect(
    isUnreachableCall({
      code: "functions/internal",
      message: "INTERNAL [500]",
    }),
  ).toBe(false);
  expect(
    isUnreachableCall({
      code: "functions/failed-precondition",
      message: "reauth",
    }),
  ).toBe(false);
});

test("fails a call whose token couldn't be fetched offline as a network error", async () => {
  const offlineTokens = {
    get: () => Promise.reject(new UnreachableError(new Error("internal [0]"))),
    invalidate: () => {},
  };
  const error = await requestJson(
    offlineTokens,
    () => Promise.resolve(Response.json({})),
    "https://example/r",
    {},
    { gate: new RateGate({ log: null }) },
  ).catch((caught: unknown) => caught);
  expect(isNetworkError(error)).toBe(true);
});

test("every Gmail call says whether Gmail was reached, an error status included", async () => {
  const events: ConnectionEvent[] = [];
  let reachable = false;
  const fetchImpl = async () => {
    if (!reachable) throw new TypeError("Failed to fetch");
    return Response.json({ error: { message: "Not Found" } }, { status: 404 });
  };
  const client = new GmailClient(
    TOKENS,
    reportingFetch(fetchImpl, (event) => events.push(event)),
  );
  await client.getThread("t1", "full").catch(() => {});
  reachable = true;
  await client.getThread("t1", "full").catch(() => {});
  expect(events).toEqual([
    { kind: "unanswered" },
    { kind: "answered", status: 404 },
  ]);
});

test("a call cut off by its time limit says it timed out", async () => {
  const events: ConnectionEvent[] = [];
  const fetchImpl = (_input: string, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () =>
        reject(new DOMException("aborted", "AbortError")),
      );
    });
  const reporting = reportingFetch(fetchImpl, (event) => events.push(event));
  const controller = new AbortController();
  const call = reporting("https://example.com", { signal: controller.signal });
  controller.abort();
  await call.catch(() => {});
  expect(events).toEqual([{ kind: "timedOut" }]);
});

test("only a fetch that got no answer counts as offline, not a TypeError from code", async () => {
  const failure = await requestJson(
    TOKENS,
    async () => {
      throw new TypeError("Failed to fetch");
    },
    "https://example.com",
  ).catch((error: unknown) => error);
  expect(failure).toBeInstanceOf(FetchFailedError);
  expect(isNetworkError(failure)).toBe(true);
  expect(isNetworkError(new TypeError("x is undefined"))).toBe(false);
});
