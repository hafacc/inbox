// Timing of opening a thread, for measuring a real session. In the dev server every open is
// timed and its line is sent to the dev server, which prints it (`web/open-timing-relay.ts`).
// Elsewhere, set localStorage "inbox.debugTiming" to "1" and reload; each open logs its phases
// to the console, in ms since the open.

/** The localStorage key that turns open timing on when it holds "1". */
export const DEBUG_TIMING_KEY = "inbox.debugTiming";

/** The dev server path that takes finished opens' lines. */
export const RELAY_PATH = "/__open-timing";

/** Where an open's messages came from. */
export type OpenSource = "memory" | "saved copy" | "Gmail";

/** One phase of an open: when it happened since the open, and how long it took when known. */
export type OpenPhase = { at: number; phase: string; took?: number };

/** One try of a Gmail call made during an open. */
export type OpenRequest = {
  /** when it was asked for, ms since the open */
  at: number;
  method: string;
  url: string;
  /** its place in line */
  priority: string;
  /** how long it waited for its turn */
  queuedMs: number;
  /** how long it took once its turn came */
  ms: number;
  /** "ok", the HTTP status it failed with, or "failed" for no answer */
  outcome: string;
};

/** What one open did, in order. */
export type OpenRecord = {
  id: string;
  source: OpenSource | null;
  phases: OpenPhase[];
  requests: OpenRequest[];
  /** why it counts as finished */
  end: string;
};

type Open = OpenRecord & {
  start: number;
  label: string;
  logs: boolean;
  inFlight: number;
  quietTimer: ReturnType<typeof setTimeout> | undefined;
  capTimer: ReturnType<typeof setTimeout> | undefined;
};

// An open is finished once nothing has happened for this long and no Gmail call it made is running.
const QUIET_MS = 2000;
// An open still busy this long after it started is finished anyway.
const CAP_MS = 30_000;
// How long finished opens wait to be sent together.
const SEND_DELAY_MS = 3000;

const relays: boolean = import.meta.env?.DEV === true;

let current: Open | null = null;
let unsent: string[] = [];
let sendTimer: ReturnType<typeof setTimeout> | undefined;
let listening = false;

/** Whether open timing logs to the console (read from localStorage). */
export function timingOn(): boolean {
  try {
    return globalThis.localStorage?.getItem(DEBUG_TIMING_KEY) === "1";
  } catch {
    return false;
  }
}

/** Start timing the open of thread `id`, finishing the one before; later marks count from here. */
export function openStarted(id: string): void {
  if (current !== null) finish(current, "next open");
  const logs = timingOn();
  if (!logs && !relays) return;
  listenForPageHide();
  current = {
    id,
    source: null,
    phases: [],
    requests: [],
    end: "",
    start: performance.now(),
    label: `open ${id.slice(-6)}`,
    logs,
    inFlight: 0,
    quietTimer: undefined,
    capTimer: undefined,
  };
  const open = current;
  open.capTimer = setTimeout(() => finish(open, "30s cap"), CAP_MS);
  performance.mark(`${open.label} start`);
  if (logs) console.info(`[${open.label}] start`);
  stillBusy(open);
}

/** Note where the open being timed got its messages. */
export function openFrom(source: OpenSource): void {
  if (current !== null) current.source = source;
}

/** Log a phase of the open being timed, with how long it took when `took` is given. */
export function openMark(phase: string, took?: number): void {
  const open = current;
  if (open === null) return;
  const at = performance.now() - open.start;
  open.phases.push(took === undefined ? { at, phase } : { at, phase, took });
  performance.mark(`${open.label} ${phase}`);
  if (open.logs) {
    const length = took === undefined ? "" : ` (${took.toFixed(1)}ms)`;
    console.info(`[${open.label}] +${at.toFixed(1)}ms ${phase}${length}`);
  }
  stillBusy(open);
}

/** Run `work`, logging it as a phase of the open being timed with how long it took. */
export function openTimed<Result>(phase: string, work: () => Result): Result {
  if (current === null) return work();
  const started = performance.now();
  const result = work();
  openMark(phase, performance.now() - started);
  return result;
}

/** Records the tries of one Gmail call made during an open. */
export type RequestTiming = {
  /** note one try: how long it waited for its turn, how long it took, how it ended, and its place in line then */
  attempt(
    queuedMs: number,
    ms: number,
    outcome: string,
    priority?: string,
  ): void;
  /** note that the call is done */
  ended(): void;
};

/** Start recording a Gmail call when an open is being timed; null when none is. */
export function openRequest(
  method: string,
  url: string,
  priority: string,
): RequestTiming | null {
  const open = current;
  if (open === null) return null;
  const asked = performance.now() - open.start;
  open.inFlight += 1;
  stillBusy(open);
  let done = false;
  return {
    attempt(queuedMs, ms, outcome, now = priority) {
      open.requests.push({
        at: asked,
        method,
        url,
        priority: now,
        queuedMs,
        ms,
        outcome,
      });
      if (open.logs) {
        console.info(`[${open.label}] ${formatRequest(open.requests.at(-1)!)}`);
      }
    },
    ended() {
      if (done) return;
      done = true;
      open.inFlight -= 1;
      stillBusy(open);
    },
  };
}

/** A Gmail call's URL without the origin and the `/gmail/v1/users/me/` every call starts with. */
export function shortPath(url: string): string {
  const users = url.indexOf("/users/me/");
  if (users >= 0) return url.slice(users + "/users/me/".length);
  else {
    try {
      const parsed = new URL(url);
      return `${parsed.pathname}${parsed.search}`;
    } catch {
      return url;
    }
  }
}

/** One Gmail call try as text: method, path, ms taken, ms queued, place in line, and a failure. */
export function formatRequest(request: OpenRequest): string {
  const queued =
    request.queuedMs >= 1 ? ` q${Math.round(request.queuedMs)}` : "";
  const outcome = request.outcome === "ok" ? "" : ` ${request.outcome}`;
  return `+${Math.round(request.at)} ${request.method} ${shortPath(request.url)} ${Math.round(request.ms)}ms${queued} ${request.priority}${outcome}`;
}

/** One open as one line: the thread, where it came from, its phases, and its Gmail calls. */
export function formatOpen(record: OpenRecord): string {
  const last = Math.max(
    0,
    ...record.phases.map((phase) => phase.at + (phase.took ?? 0)),
    ...record.requests.map(
      (request) => request.at + request.queuedMs + request.ms,
    ),
  );
  const phases = record.phases
    .map(({ at, phase, took }) =>
      took === undefined
        ? `+${Math.round(at)} ${phase}`
        : `+${Math.round(at)} ${phase} (${took.toFixed(1)})`,
    )
    .join(", ");
  const head = `open ${record.id.slice(-6)} from ${record.source ?? "?"} ${Math.round(last)}ms (${record.end})`;
  const calls =
    record.requests.length === 0
      ? ""
      : ` | gmail: ${record.requests.map(formatRequest).join(", ")}`;
  return `${head} | ${phases}${calls}`;
}

function stillBusy(open: Open): void {
  clearTimeout(open.quietTimer);
  open.quietTimer =
    open.inFlight > 0
      ? undefined
      : setTimeout(() => finish(open, "quiet"), QUIET_MS);
}

function finish(open: Open, end: string): void {
  if (current !== open) return;
  current = null;
  clearTimeout(open.quietTimer);
  clearTimeout(open.capTimer);
  if (!relays) return;
  open.end = end;
  unsent.push(formatOpen(open));
  if (end === "page hidden") send();
  else if (sendTimer === undefined) {
    sendTimer = setTimeout(send, SEND_DELAY_MS);
  }
}

function send(): void {
  clearTimeout(sendTimer);
  sendTimer = undefined;
  if (unsent.length === 0) return;
  const body = JSON.stringify({ lines: unsent });
  unsent = [];
  try {
    // The service worker's typecheck reaches this file, and a worker's navigator has no sendBeacon.
    const beacon = navigator as {
      sendBeacon?(url: string, data: string): boolean;
    };
    if (!beacon.sendBeacon?.(RELAY_PATH, body)) {
      void fetch(RELAY_PATH, { method: "POST", body, keepalive: true }).catch(
        () => undefined,
      );
    }
  } catch {
    // The relay is a debugging aid; a lost line costs nothing.
  }
}

function listenForPageHide(): void {
  if (listening || !relays || typeof addEventListener !== "function") return;
  listening = true;
  addEventListener("pagehide", () => {
    if (current !== null) finish(current, "page hidden");
    send();
  });
}

// How long `afterPaint` waits for a frame that doesn't come (a hidden tab draws none).
const PAINT_WAIT_MS = 100;

/** Run `callback` once the browser has drawn what was just changed (after the next frame's paint), or soon in a tab that draws nothing. */
export function afterPaint(callback: () => void): void {
  let done = false;
  const run = () => {
    if (done) return;
    done = true;
    callback();
  };
  requestAnimationFrame(() => setTimeout(run, 0));
  setTimeout(run, PAINT_WAIT_MS);
}

/** Log a phase once the browser has drawn what was just changed. */
export function openMarkDrawn(phase: string): void {
  const open = current;
  if (open === null) return;
  afterPaint(() => {
    if (current === open) openMark(phase);
  });
}
