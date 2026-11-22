// Pulling an open thread past its top or end to close it, as reader does with an article (its
// web/src/lib/gestures.ts), at the same distances as Android's EdgePull.kt.
import { getContext, setContext } from "svelte";

/**
 * How far an open thread must be pulled past its top or end to close it, in pixels: far enough
 * that a scroll carrying on past the edge doesn't close it by accident.
 */
export const THREAD_PULL_DISTANCE = 160;

/** How far a pulled thread can move however far the finger goes, in pixels. */
export const THREAD_PULL_STRETCH = 200;

/** How long a haptic tick lasts, in milliseconds. */
const TICK_MS = 10;

// Touches on these scroll, select or type in place, so they never pull the thread.
const NOT_A_PULL =
  "input, textarea, select, [contenteditable=true], form, pre, dialog, [role=dialog], [role=menu]";

/**
 * How far a drag of `down` pixels on a thread that was at its top (`atTop`) or its end (`atEnd`)
 * when the touch began has gone past that edge: down from the top is positive, up from the end
 * negative, and a drag the other way, which scrolls, is zero.
 */
export function pullTravel(
  down: number,
  atTop: boolean,
  atEnd: boolean,
): number {
  if (atTop && down > 0) return down;
  else if (atEnd && down < 0) return down;
  else return 0;
}

/**
 * How far a pulled thread moves when the finger has gone `travel` pixels past its edge: at first
 * with the finger, then less and less, never quite reaching {@link THREAD_PULL_STRETCH}. Keeps
 * the sign of `travel`.
 */
export function pullStretch(travel: number): number {
  return (
    Math.sign(travel) *
    THREAD_PULL_STRETCH *
    (1 - Math.exp(-Math.abs(travel) / THREAD_PULL_STRETCH))
  );
}

/** How far along a pull of `travel` pixels is toward closing the thread, from 0 to 1. */
export function pullProgress(travel: number): number {
  return Math.min(1, Math.abs(travel) / THREAD_PULL_DISTANCE);
}

/** Whether a pull of `travel` pixels closes the thread when let go. */
export function closesByPull(travel: number): boolean {
  return Math.abs(travel) >= THREAD_PULL_DISTANCE;
}

/** Whether a touch on `target` may pull the thread; works for targets inside a message's frame. */
export function mayPull(target: EventTarget | null): boolean {
  const element = target as Element | null;
  return (
    typeof element?.closest !== "function" ||
    element.closest(NOT_A_PULL) === null
  );
}

/**
 * Give a very short vibration where the browser can (Chrome on Android), as a drag crosses the
 * point where letting go acts, or lets go there; elsewhere nothing happens.
 */
export function tick(): void {
  if (typeof navigator !== "undefined" && "vibrate" in navigator) {
    navigator.vibrate(TICK_MS);
  }
}

/** An open thread's touch handlers, which its message frames pass their own touches to. */
export interface ThreadTouches {
  /** A touch began. */
  start: (event: TouchEvent) => void;
  /** A touch moved; may cancel the scroll it would make. */
  move: (event: TouchEvent) => void;
  /** A touch ended or was canceled. */
  end: (event: TouchEvent) => void;
}

const THREAD_TOUCHES = Symbol("thread touches");

/** Hand `touches` to the message frames inside this component. */
export function setThreadTouches(touches: ThreadTouches): void {
  setContext(THREAD_TOUCHES, touches);
}

/** The enclosing open thread's touch handlers, if any. */
export function getThreadTouches(): ThreadTouches | undefined {
  return getContext<ThreadTouches | undefined>(THREAD_TOUCHES);
}
