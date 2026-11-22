import { untrack } from "svelte";
import { goto } from "$app/navigation";
import { page } from "$app/state";
import { type Place, placeStep, placeUrl } from "./places";

/**
 * The browser history the app writes: one entry per place (view and open thread) and one per open
 * overlay (side panel, menu, sheet, dialog), so the browser's back closes the overlay or returns
 * to the place before. Steps run one at a time, as each waits for the browser to settle.
 */

/** What the app keeps in each history entry. */
export type BackState = {
  /** how many entries the app has stacked under this one */
  backDepth?: number;
  /** the place shown at this entry */
  place?: Place;
  /** true for an overlay's entry */
  overlay?: boolean;
};

type Entry = {
  place: Place | null;
  // Null for a place's entry.
  close: (() => void) | null;
  closed: boolean;
};

// How long a back step waits for the browser before going on anyway.
const SETTLE_MS = 1_000;

// Where history stands once every queued step has run, and the entries the app knows of by depth.
let depth = 0;
const entries = new Map<number, Entry>();
// The depth of the entry the browser last showed.
let shownDepth = 0;
let steps: Promise<void> = Promise.resolve();
let awaiting: { depth: number; settle: () => void } | null = null;
let placeHandler: ((place: Place) => void) | null = null;
let watching = false;

function enqueue(step: () => Promise<void> | void): void {
  steps = steps
    .then(step)
    .catch((error) => console.error("history step failed", error));
}

function goBack(count: number): Promise<void> {
  return new Promise((settle) => {
    const target = shownDepth - count;
    const timer = setTimeout(() => {
      awaiting = null;
      shownDepth = target;
      settle();
    }, SETTLE_MS);
    awaiting = {
      depth: target,
      settle: () => {
        clearTimeout(timer);
        settle();
      },
    };
    history.go(-count);
  });
}

function stateFor(at: number, entry: Entry): BackState {
  const state: BackState = { backDepth: at };
  if (entry.place !== null) state.place = entry.place;
  if (entry.close !== null) state.overlay = true;
  return state;
}

function onShown(state: BackState): void {
  const at = state.backDepth ?? 0;
  if (at === shownDepth) return;
  shownDepth = at;
  if (awaiting?.depth === at) {
    const { settle } = awaiting;
    awaiting = null;
    settle();
    // A step over a closed overlay can land on another one, which is stepped over in turn.
    const landed = entries.get(at);
    if (!state.overlay || landed === undefined || !landed.closed) return;
  }
  // The user went back or forward.
  const above = [...entries.keys()]
    .filter((key) => key > at)
    .sort((left, right) => right - left);
  for (const key of above) {
    const entry = entries.get(key)!;
    entries.delete(key);
    if (entry.close !== null && !entry.closed) {
      entry.closed = true;
      entry.close();
    }
  }
  depth = at;
  const here = entries.get(at);
  if (state.overlay && (here === undefined || here.closed)) {
    // An overlay that is no longer open (closed, or from before a reload) is stepped over, to the
    // place it opened over, which the step's own arrival doesn't show.
    entries.delete(at);
    depth = at - 1;
    if (state.place) placeHandler?.(state.place);
    enqueue(() => goBack(1));
    return;
  }
  if (here === undefined) {
    entries.set(at, { place: state.place ?? null, close: null, closed: false });
  }
  if (state.place) placeHandler?.(state.place);
}

function watch(): void {
  if (watching || typeof window === "undefined") return;
  watching = true;
  const start = (page.state as BackState).backDepth ?? 0;
  depth = start;
  shownDepth = start;
  $effect.root(() => {
    $effect(() => {
      const state = page.state as BackState;
      untrack(() => onShown(state));
    });
  });
}

/**
 * Register a history entry for an overlay that just opened; the browser's back then calls
 * `close`. Call the returned function when the UI closes the overlay itself: it takes the entry
 * back off without calling `close`.
 */
export function pushBack(close: () => void): () => void {
  watch();
  const place = entries.get(depth)?.place ?? null;
  depth += 1;
  const at = depth;
  const entry: Entry = { place, close, closed: false };
  entries.set(at, entry);
  enqueue(() => {
    shownDepth = at;
    return goto(new URL(location.href), {
      state: stateFor(at, entry),
      shallow: true,
      persistState: true,
    });
  });
  return () => {
    if (entries.get(at) !== entry || entry.closed) return;
    entry.closed = true;
    let count = 0;
    // The place shown at the newest entry taken off, which a move made while it was on top rewrote.
    let shown: Place | null = null;
    while (entries.get(depth)?.closed) {
      shown ??= entries.get(depth)?.place ?? null;
      entries.delete(depth);
      depth -= 1;
      count += 1;
    }
    if (count === 0) return;
    enqueue(() => goBack(count));
    // The entry it lands on keeps the place from before the move: follow the move there too.
    const landed = entries.get(depth)?.place ?? null;
    if (shown !== null && landed !== null) {
      const step = placeStep(previousPlace(), landed, shown);
      if (step !== "none") {
        writePlace(shown, placeUrl(shown, new URL(location.href)), step);
      }
    }
  };
}

/**
 * Keep a history entry while `isOpen()` holds, so the browser's back calls `close`; closing by
 * other means takes the entry back off. Call while a component initializes.
 */
export function followLayer(isOpen: () => boolean, close: () => void): void {
  const open = $derived(isOpen());
  $effect(() => {
    if (!open) return;
    return untrack(() => pushBack(close));
  });
}

/** Whether an overlay's entry is the newest, so a place change would land above it. */
export function overlayOnTop(): boolean {
  return entries.get(depth)?.close != null;
}

/** Call `handler` with the place of an entry the user went back or forward to. */
export function onPlaceShown(handler: (place: Place) => void): () => void {
  watch();
  placeHandler = handler;
  return () => {
    if (placeHandler === handler) placeHandler = null;
  };
}

/** Go back one entry when it holds the place before (see `previousPlace`); false when none does. */
export function backToPreviousPlace(): boolean {
  if (previousPlace() === null) return false;
  else {
    history.back();
    return true;
  }
}

/** The place at the newest entry, if the app wrote it. */
export function currentPlace(): Place | null {
  return entries.get(depth)?.place ?? null;
}

// The depth of the newest entry under the top one that isn't a closed overlay's.
function liveDepthBelow(): number {
  let at = depth - 1;
  while (entries.get(at)?.closed) at -= 1;
  return at;
}

/**
 * The place under the newest entry, if that entry is a place's and the one under it is a place
 * the app wrote or an open overlay (which shows the place it opened over). Closed overlays'
 * entries in between are passed over.
 */
export function previousPlace(): Place | null {
  const here = entries.get(depth);
  const before = entries.get(liveDepthBelow());
  if (here === undefined || here.close !== null || before === undefined) {
    return null;
  } else return before.place;
}

/**
 * Record a place change: `push` adds an entry, `replace` rewrites the newest, `back` returns to
 * the entry before (which must hold this place).
 */
export function writePlace(
  place: Place,
  url: URL,
  step: "push" | "replace" | "back",
): void {
  watch();
  if (step === "back") {
    // Closed overlays' entries under it go too, so the next Back doesn't land on one.
    const below = liveDepthBelow();
    const count = depth - below;
    for (let at = depth; at > below; at -= 1) entries.delete(at);
    depth = below;
    enqueue(() => goBack(count));
  } else if (step === "push") {
    depth += 1;
    const at = depth;
    const entry: Entry = { place, close: null, closed: false };
    entries.set(at, entry);
    enqueue(() => {
      shownDepth = at;
      return goto(url, {
        state: stateFor(at, entry),
        shallow: true,
        persistState: true,
      });
    });
  } else {
    const at = depth;
    const entry: Entry = entries.get(at) ?? {
      place,
      close: null,
      closed: false,
    };
    entry.place = place;
    entries.set(at, entry);
    enqueue(() =>
      goto(url, {
        state: stateFor(at, entry),
        shallow: true,
        persistState: true,
        replace: true,
      }),
    );
  }
}
