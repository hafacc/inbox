// latr's row swipes on touch, ported from latr's web/src/lib/utils/swipe.ts; what each swipe does and
// when letting go acts are shared with Android in @inbox/shared/swipe.

/** How far a touch moves before it counts as a swipe or a scroll. */
export const SWIPE_LOCK_SLOP_PX = 10;
/** A touch this close to the window's side is the system's back gesture, not a swipe. */
export const SWIPE_EDGE_PX = 24;

/** Whether a drag is the row's (horizontal), the page's scroll (vertical), or not known yet. */
export type SwipeLock = "pending" | "horizontal" | "vertical";

/** Decide whether a drag belongs to the row or the page's scroll. */
export function lockDirection(dx: number, dy: number): SwipeLock {
  const across = Math.abs(dx);
  const down = Math.abs(dy);
  if (across < SWIPE_LOCK_SLOP_PX && down < SWIPE_LOCK_SLOP_PX)
    return "pending";
  if (across > 2 * down) return "horizontal";
  else if (down >= SWIPE_LOCK_SLOP_PX) return "vertical";
  else return "pending";
}

/** Whether a touch starts at the window's side. */
export function startsAtEdge(x: number, viewportWidth: number): boolean {
  return x < SWIPE_EDGE_PX || x > viewportWidth - SWIPE_EDGE_PX;
}

// How far back a release's speed is measured over, and how long a still touch takes to count as
// stopped, both in milliseconds and as in Compose's velocity tracker.
const VELOCITY_WINDOW_MS = 100;
const VELOCITY_REST_MS = 40;

/** Measures how fast a swipe moves sideways when it is let go. */
export class SwipeVelocity {
  #samples: { time: number; x: number }[] = [];

  /** Start over at a new touch. */
  reset(): void {
    this.#samples = [];
  }

  /** Note the touch at `x` at `time` (milliseconds). */
  add(time: number, x: number): void {
    this.#samples.push({ time, x });
    while (
      this.#samples.length > 2 &&
      time - (this.#samples[0]?.time ?? time) > VELOCITY_WINDOW_MS
    ) {
      this.#samples.shift();
    }
  }

  /** The speed at `time` in px per second, rightward positive; 0 once the touch rested. */
  velocity(time: number): number {
    const first = this.#samples[0];
    const last = this.#samples.at(-1);
    if (!first || !last || time - last.time > VELOCITY_REST_MS) return 0;
    const elapsed = last.time - first.time;
    if (elapsed <= 0) return 0;
    else return ((last.x - first.x) / elapsed) * 1000;
  }
}

/** How long a row let go past where it acts takes to slide off, in milliseconds. */
export const SWIPE_SLIDE_OFF_MS = 200;
/** How long a slid-off row waits before it snaps back, if it is still listed; a row its action removes fades out meanwhile. */
export const SWIPE_SNAP_BACK_MS = 200;

/** Where a row let go on `side` slides to: off that side, `width` across. */
export function slideOffTo(side: "start" | "end", width: number): number {
  return side === "start" ? width : -width;
}

/** How long after a gesture ends its trailing click is still swallowed; some browsers send it late. */
export const CLICK_GUARD_MS = 700;

/** Swallows the click a browser sends after a long press or swipe, however late it comes. */
export class ClickGuard {
  #until = 0;

  /** Swallow clicks until the gesture ends. */
  hold(): void {
    this.#until = Number.POSITIVE_INFINITY;
  }

  /** The gesture ended at `now`; swallow one click for a short while longer. */
  release(now: number): void {
    if (this.#until > now) this.#until = now + CLICK_GUARD_MS;
  }

  /** Whether a click at `now` belongs to the gesture, without using the guard up. */
  active(now: number): boolean {
    return now < this.#until;
  }

  /** Whether a click at `now` belongs to the gesture; the first one found ends the guard. */
  consume(now: number): boolean {
    const swallowed = this.active(now);
    this.#until = 0;
    return swallowed;
  }
}
