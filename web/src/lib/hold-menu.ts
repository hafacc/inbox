// The round menu's browser side: its SVG wedges, the click a held press leaves behind, and the
// press that may become a hold. Its geometry is shared (`@inbox/shared/hold-menu`).
import {
  HOLD_MS,
  HOLD_SLOP_PX,
  type HoldWedge,
  polar,
} from "@inbox/shared/hold-menu";

/** The SVG path of a ring wedge. */
export function wedgePath(
  centerX: number,
  centerY: number,
  wedge: HoldWedge,
): string {
  const { inner, outer } = wedge.ring;
  const large = wedge.to - wedge.from > 180 ? 1 : 0;
  const [outerStartX, outerStartY] = polar(centerX, centerY, outer, wedge.from);
  const [outerEndX, outerEndY] = polar(centerX, centerY, outer, wedge.to);
  const [innerEndX, innerEndY] = polar(centerX, centerY, inner, wedge.to);
  const [innerStartX, innerStartY] = polar(centerX, centerY, inner, wedge.from);
  return `M ${outerStartX} ${outerStartY} A ${outer} ${outer} 0 ${large} 0 ${outerEndX} ${outerEndY} L ${innerEndX} ${innerEndY} A ${inner} ${inner} 0 ${large} 1 ${innerStartX} ${innerStartY} Z`;
}

/**
 * Where the pill naming the menu goes: centered over the disc's middle, kept inside the window,
 * above the disc, or below it when there is no room above (`pillTop`).
 */
export function pillLeft(centerX: number, windowWidth: number): number {
  const half = Math.min(150, windowWidth / 2 - 12);
  return Math.min(Math.max(centerX, half + 12), windowWidth - half - 12);
}

/**
 * Swallow the click a browser may send on `target` after a held press lets go, which belongs to the
 * press, not to the page: the first click within `ms`. A new press ends it at once, since the click
 * that follows is that press's own (a long touch often sends no click of its own, and a quick tap
 * on a wedge must still land). Returns what ends it early.
 */
export function swallowReleaseClick(
  target: EventTarget,
  ms: number,
): () => void {
  const swallow = (event: Event) => {
    event.preventDefault();
    event.stopPropagation();
    stop();
  };
  const stop = () => {
    clearTimeout(timer);
    target.removeEventListener("click", swallow, true);
    target.removeEventListener("pointerdown", stop, true);
  };
  const timer = setTimeout(stop, ms);
  target.addEventListener("click", swallow, true);
  target.addEventListener("pointerdown", stop, true);
  return stop;
}

/** The timers a {@link HoldPress} waits on. */
export type HoldTimers = {
  /** run `run` after `ms`, returning what cancels it */
  set: (run: () => void, ms: number) => number;
  /** cancel a timer `set` started */
  clear: (timer: number) => void;
};

const WINDOW_TIMERS: HoldTimers = {
  set: (run, ms) => window.setTimeout(run, ms),
  clear: (timer) => window.clearTimeout(timer),
};

/**
 * One press at a time that may become a hold: it runs its callback once the press rests
 * {@link HOLD_MS} without wandering more than {@link HOLD_SLOP_PX} from where it began. A move past
 * that, a lift or a cancel ends it, leaving the press to whatever else wants it (a scroll, a swipe).
 */
export class HoldPress {
  #timers: HoldTimers;
  #press: { pointerId: number; x: number; y: number; timer: number } | null =
    null;

  constructor(timers: HoldTimers = WINDOW_TIMERS) {
    this.#timers = timers;
  }

  /** Whether a press is waiting to become a hold. */
  get pending(): boolean {
    return this.#press !== null;
  }

  /** Start waiting on press `pointerId` at `x`, `y`, ending any other first; `onHold` runs once it holds. */
  start(pointerId: number, x: number, y: number, onHold: () => void): void {
    this.cancel();
    const timer = this.#timers.set(() => {
      this.#press = null;
      onHold();
    }, HOLD_MS);
    this.#press = { pointerId, x, y, timer };
  }

  /** Press `pointerId` moved to `x`, `y`: too far, and it is no longer a hold. */
  move(pointerId: number, x: number, y: number): void {
    const press = this.#press;
    if (press === null || press.pointerId !== pointerId) return;
    if (Math.hypot(x - press.x, y - press.y) > HOLD_SLOP_PX) this.cancel();
  }

  /** Stop waiting: the press lifted, was taken or the row went. */
  cancel(): void {
    if (this.#press !== null) {
      this.#timers.clear(this.#press.timer);
      this.#press = null;
    }
  }
}
