import { type ViewStep, viewStep } from "@inbox/shared/back-stack";
import {
  SETTINGS_SECTIONS,
  sameView,
  VIEW_TITLES,
  type View,
  viewKey,
} from "./views";

/** Where the app is: the view shown and the thread open in it. */
export type Place = { view: View; thread: string | null };

// The address's query parameters, one per kind of view, and the open thread's.
const VIEW_PARAM = "view";
const LABEL_PARAM = "label";
const SEARCH_PARAM = "q";
const SETTINGS_PARAM = "settings";
const THREAD_PARAM = "thread";
const PARAMS = [
  VIEW_PARAM,
  LABEL_PARAM,
  SEARCH_PARAM,
  SETTINGS_PARAM,
  THREAD_PARAM,
];

/** Whether two places show the same view with the same thread open. */
export function samePlace(left: Place, right: Place): boolean {
  return sameView(left.view, right.view) && left.thread === right.thread;
}

/** The address for a place, keeping `base`'s path and any parameters that aren't the app's. */
export function placeUrl(place: Place, base: URL): URL {
  const url = new URL(base);
  url.hash = "";
  for (const name of PARAMS) url.searchParams.delete(name);
  const view = place.view;
  if (view.kind === "label") url.searchParams.set(LABEL_PARAM, view.labelId);
  else if (view.kind === "search") {
    url.searchParams.set(SEARCH_PARAM, view.query);
  } else if (view.kind === "settings") {
    url.searchParams.set(SETTINGS_PARAM, view.section);
  } else if (view.kind !== "inbox") {
    url.searchParams.set(VIEW_PARAM, view.kind);
  }
  if (place.thread !== null) url.searchParams.set(THREAD_PARAM, place.thread);
  return url;
}

/** The place an address names, or null when it names none (the plain inbox). */
export function placeFromUrl(url: URL): Place | null {
  const params = url.searchParams;
  const thread = params.get(THREAD_PARAM) || null;
  const label = params.get(LABEL_PARAM);
  const query = params.get(SEARCH_PARAM);
  const section = params.get(SETTINGS_PARAM);
  const kind = params.get(VIEW_PARAM);
  let view: View | null = null;
  if (label) view = { kind: "label", labelId: label };
  else if (query) view = { kind: "search", query };
  else if (section) {
    const known = SETTINGS_SECTIONS.find((entry) => entry.section === section);
    view = known ? { kind: "settings", section: known.section } : null;
  } else if (kind && kind in VIEW_TITLES) {
    view = { kind: kind as keyof typeof VIEW_TITLES };
  }
  if (view === null && thread === null) return null;
  return { view: view ?? { kind: "inbox" }, thread };
}

/**
 * How history follows a move from `current` to `next`, given the entry before `current` (null
 * when it isn't the app's place): going back to that entry, rewriting the current one (another
 * thread in the same view, closing a thread opened straight from the address), or a new entry.
 * A move to another view follows `viewStep`, as Android's views do.
 */
export function placeStep(
  previous: Place | null,
  current: Place,
  next: Place,
): ViewStep {
  if (samePlace(current, next)) return "none";
  else if (previous !== null && samePlace(previous, next)) return "back";
  else if (sameView(current.view, next.view)) {
    return current.thread !== null ? "replace" : "push";
  } else {
    const step = viewStep(null, viewKey(current.view), viewKey(next.view));
    return step === "none" ? "push" : step;
  }
}

/**
 * How history first closes the open thread on a move from `current` to another view, as the move
 * closes it and Android's views don't keep it: back to the entry before when it is the view without
 * the thread, else a rewrite to it; null when no thread is open or the view stays.
 */
export function threadCloseStep(
  previous: Place | null,
  current: Place,
  next: Place,
): "back" | "replace" | null {
  if (current.thread === null || sameView(current.view, next.view)) {
    return null;
  } else if (
    previous !== null &&
    samePlace(previous, { view: current.view, thread: null })
  ) {
    return "back";
  } else return "replace";
}
