import { HOLD_MS, HOLD_SLOP_PX } from "@inbox/shared/hold-menu";
import {
  committedSide,
  type SwipeSide,
  swipeProgress,
} from "@inbox/shared/swipe";
import {
  ClickGuard,
  lockDirection,
  SWIPE_SLIDE_OFF_MS,
  SWIPE_SNAP_BACK_MS,
  type SwipeLock,
  SwipeVelocity,
  slideOffTo,
  startsAtEdge,
} from "./swipe";

/** What each side of a row's swipe does; null where that direction doesn't move. */
export type SwipeSides<Action> = {
  /** the rightward swipe's action */
  start: Action | null;
  /** the leftward swipe's action */
  end: Action | null;
};

type Drag = { pointerId: number; x: number; y: number; lock: SwipeLock };

/** What a long press on a row runs: the pointer pressing and where it went down. */
export type LongPress = (
  pointerId: number,
  press: { x: number; y: number },
) => void;

/**
 * A touch swipe on a row, as a thread's row swipes: it follows the finger sideways once the drag
 * is clearly horizontal, and let go past where it acts (or flung its way) slides off with its move
 * under it and runs it, snapping back after; short of that it springs back. A touch held still for
 * `HOLD_MS` runs the long press instead, when there is one. Wire `down`, `move`, `up`,
 * `cancel`, `clickCapture` and `contextMenu` to the row's pointer, click and context menu events.
 */
export class RowSwipe<Action> {
  /** how far the row has moved, rightward positive */
  dx = $state(0);
  /** the row's width when the swipe began */
  width = $state(0);
  /** whether the row is animating back to rest or off its side */
  settling = $state(false);
  /** the moves the swipe let go with, shown under the slid-off row until it snaps back */
  letGo = $state<SwipeSides<Action> | null>(null);

  readonly #sides: () => SwipeSides<Action>;
  readonly #run: (action: Action, side: SwipeSide) => void;
  readonly #longPress: LongPress | null;
  readonly #holds: () => boolean;
  readonly #guard = new ClickGuard();
  readonly #tracker = new SwipeVelocity();
  #drag: Drag | null = null;
  #snapBack: number | null = null;
  #pressTimer: number | null = null;
  #pressed = false;

  /**
   * A swipe whose sides do what `sides` says now, running `run` when let go past where it acts,
   * and `longPress` (if any) for a touch held still while `holds` says a hold may start. Made
   * while the row's component initializes; its timers stop when the row goes.
   */
  constructor(
    sides: () => SwipeSides<Action>,
    run: (action: Action, side: SwipeSide) => void,
    longPress: LongPress | null = null,
    holds: () => boolean = () => true,
  ) {
    this.#sides = sides;
    this.#run = run;
    this.#longPress = longPress;
    this.#holds = holds;
    // A short tick as the swipe crosses the point where letting go acts, where the device has one.
    $effect(() => {
      if (this.armed) navigator.vibrate?.(10);
    });
    $effect(() => () => this.#stop());
  }

  /** The moves shown under the row: those it let go with, else what it would do now. */
  get shown(): SwipeSides<Action> {
    return this.letGo ?? this.#sides();
  }

  /** Whether the swipe has passed the point where letting go acts. */
  get armed(): boolean {
    return this.dx !== 0 && swipeProgress(this.dx, this.width) >= 1;
  }

  /** Whether a touch is being held for a long press, or ran one and hasn't ended it. */
  get holding(): boolean {
    return this.#pressTimer !== null || this.#pressed;
  }

  /** Whether a press now belongs to a swipe or hold just ended, as its trailing click does. */
  get guarding(): boolean {
    return this.#guard.active(performance.now());
  }

  /** What the long press opened has closed. */
  endHold(): void {
    this.#pressed = false;
  }

  /** A pointer went down on the row. */
  down = (event: PointerEvent & { currentTarget: HTMLElement }): void => {
    if (event.pointerType !== "touch") return;
    if (startsAtEdge(event.clientX, window.innerWidth)) return;
    if (this.letGo !== null) return;
    this.width = event.currentTarget.offsetWidth;
    this.#drag = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      lock: "pending",
    };
    this.#tracker.reset();
    this.#clearPress();
    const longPress = this.#longPress;
    if (longPress === null || !this.#holds()) return;
    const pointerId = event.pointerId;
    const press = { x: event.clientX, y: event.clientY };
    this.#pressTimer = window.setTimeout(() => {
      this.#pressTimer = null;
      this.#drag = null;
      this.#pressed = true;
      this.#guard.hold();
      longPress(pointerId, press);
    }, HOLD_MS);
  };

  /** The pointer moved. */
  move = (event: PointerEvent & { currentTarget: HTMLElement }): void => {
    const drag = this.#drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const moveX = event.clientX - drag.x;
    const moveY = event.clientY - drag.y;
    if (Math.hypot(moveX, moveY) > HOLD_SLOP_PX) this.#clearPress();
    if (drag.lock === "pending") {
      drag.lock = lockDirection(moveX, moveY);
      if (drag.lock === "vertical") {
        this.#drag = null;
        return;
      }
      if (drag.lock === "horizontal") {
        event.currentTarget.setPointerCapture(event.pointerId);
        this.#guard.hold();
      }
    }
    if (drag.lock === "horizontal") {
      const sides = this.#sides();
      // A direction with nothing to do doesn't move.
      const allowed = moveX > 0 ? sides.start : sides.end;
      this.dx = allowed === null ? 0 : moveX;
      this.#tracker.add(event.timeStamp, this.dx);
    }
  };

  /** The pointer went up. */
  up = (event: PointerEvent): void => {
    this.#end(event, false);
  };

  /** The pointer was canceled (the page took it for a scroll). */
  cancel = (event: PointerEvent): void => {
    this.#end(event, true);
  };

  /** A click reached the row: swallowed when it ends a swipe. Returns whether it was. */
  clickCapture = (event: MouseEvent): boolean => {
    if (this.#guard.consume(performance.now())) {
      event.preventDefault();
      event.stopPropagation();
      return true;
    } else {
      return false;
    }
  };

  /** The browser asked for its context menu: not while a touch is a long press. */
  contextMenu = (event: MouseEvent): void => {
    if (this.holding) event.preventDefault();
  };

  #stop(): void {
    this.#clearPress();
    if (this.#snapBack !== null) window.clearTimeout(this.#snapBack);
  }

  #clearPress(): void {
    if (this.#pressTimer !== null) {
      window.clearTimeout(this.#pressTimer);
      this.#pressTimer = null;
    }
  }

  #end(event: PointerEvent, canceled: boolean): void {
    this.#clearPress();
    this.#pressed = false;
    this.#guard.release(performance.now());
    const ended = this.#drag;
    if (!ended || ended.pointerId !== event.pointerId) return;
    this.#drag = null;
    if (ended.lock !== "horizontal") return;
    const side = canceled
      ? null
      : committedSide(
          this.dx,
          this.width,
          this.#tracker.velocity(event.timeStamp),
        );
    this.settling = this.dx !== 0;
    const sides = this.#sides();
    const action =
      side === null ? null : side === "start" ? sides.start : sides.end;
    if (side === null || action === null) {
      this.dx = 0;
    } else {
      this.letGo = sides;
      this.dx = slideOffTo(side, this.width);
      this.#snapBack = window.setTimeout(() => {
        this.#snapBack = null;
        this.settling = false;
        this.dx = 0;
        this.letGo = null;
      }, SWIPE_SLIDE_OFF_MS + SWIPE_SNAP_BACK_MS);
      this.#run(action, side);
    }
  }
}
