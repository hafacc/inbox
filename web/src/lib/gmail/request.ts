// Authorized JSON calls to Google REST APIs (Gmail, People) with the user's access token, and the
// pacing every Gmail call on this device shares (shared/src/gmail-pace.ts).

import {
  backoffMs,
  type CallPriority,
  type ConnectionEvent,
  isUsageLimit,
  nextCall,
  retriesAfterLimit,
  savingHeldUntil,
} from "@inbox/shared/gmail-pace";
import { openRequest } from "../open-timing";

export type { CallPriority } from "@inbox/shared/gmail-pace";

// The reason a call that got no answer in time carries.
const TIMEOUT_REASON = "timeout";

/** A Google API call (Gmail, People) that came back with an error status. */
export class GmailError extends Error {
  /** The HTTP status. */
  readonly status: number;
  /** Google's reason for the error (`userRateLimitExceeded`, `notFound`…), or null. */
  readonly reason: string | null;

  constructor(status: number, message: string, reason: string | null = null) {
    super(message);
    this.name = "GmailError";
    this.status = status;
    this.reason = reason;
  }
}

/** Whether a Google API error is a usage limit, which passes if the call is tried again later. */
export function isRateLimited(error: unknown): boolean {
  return (
    error instanceof GmailError && isUsageLimit(error.status, error.reason)
  );
}

/** Whether an error is a call Google didn't answer in time. */
export function isTimeout(error: unknown): boolean {
  return error instanceof GmailError && error.reason === TIMEOUT_REASON;
}

/** Whether a failed call may go through later: offline, a usage limit, a timeout, or a Google server error. */
export function isRetryable(error: unknown): boolean {
  return (
    isNetworkError(error) ||
    isRateLimited(error) ||
    isTimeout(error) ||
    (error instanceof GmailError && error.status >= 500)
  );
}

type ErrorBody = {
  error?: {
    message?: string;
    errors?: { reason?: string }[];
    details?: { reason?: string }[];
  };
};

function reasonOf(body: ErrorBody): string | null {
  const error = body.error;
  return (
    error?.errors?.find((entry) => typeof entry.reason === "string")?.reason ??
    error?.details?.find((entry) => typeof entry.reason === "string")?.reason ??
    null
  );
}

/**
 * Whether an error means a call got no answer (a failed fetch, or a Firebase callable that never
 * reached its server), rather than that Gmail refused or the code failed.
 */
export function isNetworkError(error: unknown): boolean {
  return error instanceof FetchFailedError || error instanceof UnreachableError;
}

/** A Google API fetch that got no answer: no network, or the host couldn't be found or reached. */
export class FetchFailedError extends TypeError {
  constructor(cause: unknown) {
    super("Couldn't reach Google.", { cause });
    this.name = "FetchFailedError";
  }
}

/** A Firebase callable that got no answer from its server, as a network error. */
export class UnreachableError extends TypeError {
  constructor(cause: unknown) {
    super("Couldn't reach the server.", { cause });
    this.name = "UnreachableError";
  }
}

/**
 * Whether a Firebase callable failed without an answer from its server: the fetch itself failed
 * (the SDK reports that as `internal` with status 0), it timed out, or the server was unavailable.
 */
export function isUnreachableCall(error: unknown): boolean {
  const { code, message } = (error ?? {}) as {
    code?: unknown;
    message?: unknown;
  };
  return (
    code === "functions/deadline-exceeded" ||
    code === "functions/unavailable" ||
    (code === "functions/internal" &&
      typeof message === "string" &&
      message.endsWith("[0]"))
  );
}

/** Where an access token comes from, and how to drop one Gmail rejected. */
export type TokenSource = {
  get: () => Promise<string>;
  invalidate: () => void;
};

/** The part of `fetch` the client uses. */
export type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

/**
 * `fetchImpl`, telling `report` how each call went: `answered` for any status, `timedOut` when it
 * was cut off (as its time limit does), `unanswered` for a network failure.
 */
export function reportingFetch(
  fetchImpl: FetchLike,
  report: (event: ConnectionEvent) => void,
): FetchLike {
  return async (input, init) => {
    let response: Response;
    try {
      response = await fetchImpl(input, init);
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        report({ kind: "timedOut" });
      } else if (error instanceof TypeError) {
        report({ kind: "unanswered" });
      }
      throw error;
    }
    report({ kind: "answered", status: response.status });
    return response;
  };
}

/** A call's place in line: its priority, which a more urgent caller of the same read may raise. */
export type CallTicket = { priority: CallPriority };

/** What a backoff logs: the limit Google gave and the pause it started, never ids or content. */
export type BackoffEvent = {
  status: number;
  reason: string | null;
  strike: number;
  pauseMs: number;
  priority: CallPriority;
};

/** Options for a `RateGate`. */
export type RateGateOptions = {
  /** the clock, swappable in tests */
  now?: () => number;
  /** run `callback` after `ms`, swappable in tests */
  setTimer?: (callback: () => void, ms: number) => void;
  /** a number from 0 up to 1 that spreads each pause, swappable in tests */
  random?: () => number;
  /** told of each backoff; by default the console in development only */
  log?: ((event: BackoffEvent) => void) | null;
};

const URGENCY: readonly CallPriority[] = [
  "action",
  "visible",
  "background",
  "saving",
];

/** Whether `priority` is more urgent than `than`. */
export function moreUrgent(
  priority: CallPriority,
  than: CallPriority,
): boolean {
  return URGENCY.indexOf(priority) < URGENCY.indexOf(than);
}

type Waiting = {
  id: string;
  order: number;
  ticket: CallTicket;
  start: (release: () => void) => void;
};

function devLog(event: BackoffEvent): void {
  console.info("gmail backoff", event);
}

/**
 * Every Gmail call one device makes takes a turn here: at most `MAX_CONCURRENT` run at once, the
 * most urgent waiting call goes next (`nextCall`, which also yields and paces the saved copy's
 * reads), and after Google says to slow down all of them wait out one shared pause (`backoffMs`,
 * Google's `Retry-After` when it gives one, spread at random), the saved copy's reads longer
 * (`savingHeldUntil`).
 */
export class RateGate {
  #now: () => number;
  #setTimer: (callback: () => void, ms: number) => void;
  #random: () => number;
  #log: ((event: BackoffEvent) => void) | null;
  #waiting: Waiting[] = [];
  #running = 0;
  #runningSaving = 0;
  #savingStartedAt = 0;
  #asked = 0;
  #until = 0;
  #savingHeld = 0;
  #strikes = 0;
  #wakeAt = 0;
  #listeners = new Set<(until: number) => void>();

  constructor(options: RateGateOptions = {}) {
    this.#now = options.now ?? Date.now;
    this.#setTimer =
      options.setTimer ?? ((callback, ms) => void setTimeout(callback, ms));
    this.#random = options.random ?? Math.random;
    this.#log =
      options.log === undefined
        ? import.meta.env?.DEV
          ? devLog
          : null
        : options.log;
  }

  /** When calls may go again, epoch milliseconds; 0 when they may go now. */
  get pausedUntil(): number {
    return this.#until > this.#now() ? this.#until : 0;
  }

  /** How many calls are running now. */
  get running(): number {
    return this.#running;
  }

  /** Call `listener` with the pause's end (0 when it ends) whenever it changes; returns an unsubscribe. */
  subscribe(listener: (until: number) => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /** Wait for a turn; resolves with the function that ends it, to be called once the call is done. */
  acquire(ticket: CallTicket): Promise<() => void> {
    return new Promise((resolve) => {
      this.#asked++;
      this.#waiting.push({
        id: String(this.#asked),
        order: this.#asked,
        ticket,
        start: resolve,
      });
      this.#pump();
    });
  }

  /** Move a waiting call up to `priority`, when that is more urgent. */
  raise(ticket: CallTicket, priority: CallPriority): void {
    if (!moreUrgent(priority, ticket.priority)) return;
    ticket.priority = priority;
    this.#pump();
  }

  /**
   * Start the pause after a usage limit; returns when it ends. A limit that comes back while a
   * pause already runs belongs to the same burst: it counts as no strike and leaves the pause as it is.
   */
  slowDown(
    error: GmailError,
    retryAfterSeconds: number | null,
    priority: CallPriority,
  ): number {
    const now = this.#now();
    if (this.#until > now) return this.#until;
    this.#strikes++;
    const pauseMs = backoffMs(this.#strikes, retryAfterSeconds, this.#random());
    this.#until = now + pauseMs;
    this.#savingHeld = savingHeldUntil(this.#until);
    this.#log?.({
      status: error.status,
      reason: error.reason,
      strike: this.#strikes,
      pauseMs,
      priority,
    });
    for (const listener of this.#listeners) listener(this.#until);
    return this.#until;
  }

  /** Note a call that went through: the next limit starts the backoff over, and listeners hear the pause ended. */
  passed(): void {
    this.#strikes = 0;
    if (this.#until !== 0 && this.#until <= this.#now()) {
      this.#until = 0;
      for (const listener of this.#listeners) listener(0);
    }
  }

  #pump(): void {
    for (;;) {
      const now = this.#now();
      const turn = nextCall(
        this.#waiting.map((call) => ({
          id: call.id,
          priority: call.ticket.priority,
          order: call.order,
        })),
        {
          running: this.#running,
          runningSaving: this.#runningSaving,
          pausedUntil: this.#until,
          savingStartedAt: this.#savingStartedAt,
          savingHeldUntil: this.#savingHeld,
        },
        now,
      );
      if (turn.id === null) {
        this.#wakeLater(turn.wakeAt, now);
        break;
      }
      const index = this.#waiting.findIndex((call) => call.id === turn.id);
      const [call] = this.#waiting.splice(index, 1);
      const saving = call!.ticket.priority === "saving";
      this.#running++;
      if (saving) {
        this.#runningSaving++;
        this.#savingStartedAt = now;
      }
      let ended = false;
      call!.start(() => {
        if (ended) return;
        ended = true;
        this.#running--;
        if (saving) this.#runningSaving--;
        this.#pump();
      });
    }
  }

  // Look again at `wakeAt`, when a pause or the saved copy's spacing ends, unless a timer already will.
  #wakeLater(wakeAt: number | null, now: number): void {
    if (wakeAt === null || wakeAt <= now || this.#wakeAt === wakeAt) return;
    this.#wakeAt = wakeAt;
    this.#setTimer(() => {
      if (this.#wakeAt === wakeAt) this.#wakeAt = 0;
      this.#pump();
    }, wakeAt - now);
  }
}

function retryAfterOf(response: Response): number | null {
  const header = response.headers.get("Retry-After");
  if (header === null) return null;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds >= 0) return seconds;
  const date = Date.parse(header);
  return Number.isNaN(date) ? null : Math.max(0, (date - Date.now()) / 1000);
}

/** How one call is paced: the device's gate, its place in line, and how long it may take. */
export type CallOptions = {
  /** the gate it takes a turn from; none for APIs outside Gmail's quota */
  gate?: RateGate | null;
  /** its place in line; by default "action" for a write and "visible" for a read */
  ticket?: CallTicket;
  /** how long each try may take before it fails as a timeout, in milliseconds */
  timeoutMs?: number;
};

// Long enough for a draft with attachments on a slow link; a call that never answers would
// otherwise hold its turn, and its list's loading flag, for good.
const READ_TIMEOUT_MS = 30_000;
const WRITE_TIMEOUT_MS = 120_000;

/**
 * Fetch `url` with the access token as JSON, retrying once with a fresh token on 401. With a
 * `gate`, every try waits for its turn, and a usage limit starts a pause; a read (GET) is then
 * tried again as `retriesAfterLimit` allows. A try with no answer in time fails as a timeout.
 */
export async function requestJson<Result>(
  tokens: TokenSource,
  fetchImpl: FetchLike,
  url: string,
  init: RequestInit = {},
  options: CallOptions = {},
): Promise<Result> {
  const isRead = (init.method ?? "GET").toUpperCase() === "GET";
  const gate = options.gate ?? null;
  const ticket = options.ticket ?? {
    priority: isRead ? "visible" : "action",
  };
  const timeoutMs =
    options.timeoutMs ?? (isRead ? READ_TIMEOUT_MS : WRITE_TIMEOUT_MS);
  const timing =
    gate === null
      ? null
      : openRequest(init.method ?? "GET", url, ticket.priority);
  try {
    for (let attempt = 0; ; attempt++) {
      const asked = performance.now();
      const release = gate === null ? null : await gate.acquire(ticket);
      const sent = performance.now();
      try {
        const result = await requestOnce<Result>(
          tokens,
          fetchImpl,
          url,
          init,
          timeoutMs,
        );
        timing?.attempt(
          sent - asked,
          performance.now() - sent,
          "ok",
          ticket.priority,
        );
        gate?.passed();
        return result;
      } catch (error) {
        timing?.attempt(
          sent - asked,
          performance.now() - sent,
          outcomeOf(error),
          ticket.priority,
        );
        if (!(error instanceof LimitedError)) throw error;
        gate?.slowDown(error.cause, error.retryAfter, ticket.priority);
        if (gate === null || !retriesAfterLimit(isRead, attempt)) {
          throw error.cause;
        }
      } finally {
        release?.();
      }
    }
  } finally {
    timing?.ended();
  }
}

// How a failed try ended, for open timing: its HTTP status and Google's reason, or "failed" for
// no answer.
function outcomeOf(error: unknown): string {
  const gmail = error instanceof LimitedError ? error.cause : error;
  if (gmail instanceof GmailError) {
    return gmail.reason === null
      ? String(gmail.status)
      : `${gmail.status} ${gmail.reason}`;
  } else {
    return "failed";
  }
}

// A usage limit, carrying Google's Retry-After for the gate.
class LimitedError extends Error {
  readonly retryAfter: number | null;
  override readonly cause: GmailError;

  constructor(cause: GmailError, retryAfter: number | null) {
    super(cause.message);
    this.cause = cause;
    this.retryAfter = retryAfter;
  }
}

async function requestOnce<Result>(
  tokens: TokenSource,
  fetchImpl: FetchLike,
  url: string,
  init: RequestInit,
  timeoutMs: number,
  retried = false,
): Promise<Result> {
  const token = await tokens.get();
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  let response: Response;
  let text: string;
  try {
    response = await fetchImpl(url, {
      ...init,
      signal: controller.signal,
      headers: {
        ...(init.headers ?? {}),
        Authorization: `Bearer ${token}`,
        ...(init.body ? { "Content-Type": "application/json" } : {}),
      },
    });
    text = response.status === 401 && !retried ? "" : await response.text();
  } catch (error) {
    if (timedOut) {
      throw new GmailError(408, "Gmail didn't answer in time.", TIMEOUT_REASON);
    }
    throw error instanceof TypeError ? new FetchFailedError(error) : error;
  } finally {
    clearTimeout(timer);
  }
  if (response.status === 401 && !retried) {
    tokens.invalidate();
    return requestOnce(tokens, fetchImpl, url, init, timeoutMs, true);
  }
  if (!response.ok) {
    let message = response.statusText;
    let reason: string | null = null;
    try {
      const body = JSON.parse(text) as ErrorBody;
      message = body.error?.message ?? message;
      reason = reasonOf(body);
    } catch {
      // not JSON
    }
    const error = new GmailError(response.status, message, reason);
    if (isRateLimited(error)) {
      throw new LimitedError(error, retryAfterOf(response));
    }
    throw error;
  }
  if (response.status === 204 || text === "") return undefined as Result;
  return JSON.parse(text) as Result;
}
