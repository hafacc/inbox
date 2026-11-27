// Swiping an open thread sideways to the thread before or after it in the list. Web uses it
// directly; Android ports it to :core (ThreadPager.kt). Both run shared/testdata/thread-pager-fixtures.json.

/** How far (dp, or CSS px) in from either screen edge a swipe is left to the system's Back. */
export const PAGE_EDGE = 24;
/** The share of the screen's width a swipe crosses before letting go moves to the next thread. */
export const PAGE_COMMIT_FRACTION = 0.28;
/** How fast (dp, or CSS px, per second) a swipe flung in its own direction moves wherever it is let go. */
export const PAGE_FLING_SPEED = 600;
/** How far a flung swipe must have come first, so a flick on a tap doesn't move. */
export const PAGE_FLING_DISTANCE = 30;
/** How much of the finger's travel the thread follows toward a side with no thread. */
export const PAGE_RESISTANCE = 0.3;

/** How opaque a thread is a whole screen's width from where it rests open, while a swipe moves it. */
export const PAGE_FAR_OPACITY = 0.5;

/** Where a swipe goes: the thread below the open one, or the one above. */
export type PageStep = "next" | "previous";

/** Whether a swipe starting `x` across a screen `width` wide may move between threads: not from an edge. */
export function pageStarts(
  x: number,
  width: number,
  edge = PAGE_EDGE,
): boolean {
  return x >= edge && x <= width - edge;
}

/** The thread a swipe `dx` from rest (rightward positive) is moving toward: leftward is the next one. */
export function pageToward(dx: number): PageStep | null {
  if (dx < 0) return "next";
  else if (dx > 0) return "previous";
  else return null;
}

/** How far the thread follows a finger `dx` from rest, less toward a side with no thread. */
export function pageOffset(
  dx: number,
  hasPrevious: boolean,
  hasNext: boolean,
): number {
  const toward = pageToward(dx);
  const open =
    toward === "next" ? hasNext : toward === "previous" ? hasPrevious : true;
  return open ? dx : dx * PAGE_RESISTANCE;
}

/**
 * How opaque a thread is `distance` from where it rests open (either way) on a screen `width`
 * wide: whole there, fading evenly to {@link PAGE_FAR_OPACITY} a whole width away and beyond.
 */
export function pageOpacity(distance: number, width: number): number {
  const away = width > 0 ? Math.min(1, Math.abs(distance) / width) : 0;
  return 1 - (1 - PAGE_FAR_OPACITY) * away;
}

/**
 * Where a swipe let go `dx` from rest, moving at `velocity` (rightward positive), on a screen `width`
 * wide goes, or null when it springs back, toward a side that has a thread. Flung at
 * {@link PAGE_FLING_SPEED} or faster, it moves when flung its own way once past
 * {@link PAGE_FLING_DISTANCE} and springs back when flung the other way; slower, it moves once past
 * {@link PAGE_COMMIT_FRACTION} of the width.
 */
export function pageStep(
  dx: number,
  width: number,
  velocity: number,
  hasPrevious: boolean,
  hasNext: boolean,
): PageStep | null {
  const toward = pageToward(dx);
  if (toward === null || !(toward === "next" ? hasNext : hasPrevious))
    return null;
  if (Math.abs(velocity) >= PAGE_FLING_SPEED) {
    return velocity > 0 === dx > 0 && Math.abs(dx) >= PAGE_FLING_DISTANCE
      ? toward
      : null;
  } else {
    return Math.abs(dx) >= width * PAGE_COMMIT_FRACTION ? toward : null;
  }
}
