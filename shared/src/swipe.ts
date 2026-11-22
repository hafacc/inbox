/** The share of a row's width a swipe crosses before letting go acts. */
export const SWIPE_COMMIT_FRACTION = 0.4;
/** How fast (dp, or CSS px, per second) a swipe flung in its own direction acts wherever it is let go. */
export const SWIPE_FLING_SPEED = 125;

/** The side a swipe moves toward: start is rightward, end leftward. */
export type SwipeSide = "start" | "end";

/** How far a swipe on a row this wide goes before letting go acts. */
export function commitThreshold(rowWidth: number): number {
  return rowWidth * SWIPE_COMMIT_FRACTION;
}

/** How far a swipe has come toward letting go acting, from 0 to 1. */
export function swipeProgress(dx: number, rowWidth: number): number {
  const threshold = commitThreshold(rowWidth);
  if (threshold <= 0) return 0;
  else return Math.min(1, Math.abs(dx) / threshold);
}

/**
 * Which side acts when a swipe is let go `dx` from rest, moving at `velocity` (rightward positive).
 *
 * A fling at least [SWIPE_FLING_SPEED] acts when it moves the way the row already went, and puts
 * the row back when it moves the other way; slower, the row must be past [commitThreshold]. This is
 * how Android's swipe-to-dismiss settles.
 */
export function committedSide(
  dx: number,
  rowWidth: number,
  velocity: number,
): SwipeSide | null {
  if (dx === 0) return null;
  const side: SwipeSide = dx > 0 ? "start" : "end";
  if (Math.abs(velocity) >= SWIPE_FLING_SPEED) {
    return velocity > 0 === dx > 0 ? side : null;
  } else {
    return Math.abs(dx) >= commitThreshold(rowWidth) ? side : null;
  }
}

/** A thread's snooze status, as a row sees it. */
export type SwipeSnoozeStatus = "none" | "snoozed" | "lapsed" | "woken";

/** What a thread swipe looks at. */
export type ThreadSwipeRow = {
  /** the thread's snooze status */
  status: SwipeSnoozeStatus;
  /** whether the thread has the INBOX label */
  inInbox: boolean;
  /** whether the thread is in spam or trash, which have their own actions */
  spamOrTrash: boolean;
  /** whether the list is the Snoozed view */
  inSnoozedView: boolean;
};

/** What a swipe on a thread does. */
export type ThreadSwipeAction = "snooze" | "wake" | "done" | "inbox";

/**
 * What a swipe on a thread does, or null where that direction doesn't move.
 *
 * Rightward snoozes, or wakes a snoozed thread (any thread in the Snoozed view); leftward marks it
 * done where Done applies (in the inbox, snoozed, or with a lapsed snooze), and elsewhere moves it
 * to the inbox, but for a thread in spam or trash. While rows are selected it acts on this thread
 * alone, as it does otherwise.
 */
export function threadSwipeAction(
  side: SwipeSide,
  row: ThreadSwipeRow,
): ThreadSwipeAction | null {
  const snoozed = row.inSnoozedView || row.status === "snoozed";
  const canDone = snoozed || row.inInbox || row.status === "lapsed";
  if (side === "start") return snoozed ? "wake" : "snooze";
  else if (canDone) return "done";
  else return row.spamOrTrash ? null : "inbox";
}

/** What a todo swipe looks at. */
export type TodoSwipeRow = {
  /** whether the todo is done */
  done: boolean;
  /** whether the todo is snoozed now */
  snoozed: boolean;
};

/** What a swipe on a todo does. */
export type TodoSwipeAction = "snooze" | "reactivate" | "done" | "delete";

/**
 * What a swipe on a todo does.
 *
 * Rightward snoozes, or makes a done or snoozed todo active; leftward marks it done, or deletes a
 * done one. While rows are selected it acts on this todo alone, as it does otherwise.
 */
export function todoSwipeAction(
  side: SwipeSide,
  row: TodoSwipeRow,
): TodoSwipeAction {
  if (side === "start") {
    return row.done || row.snoozed ? "reactivate" : "snooze";
  } else {
    return row.done ? "delete" : "done";
  }
}

/** What a swipe on a bundle's row does. */
export type BundleSwipeAction = "snooze" | "done";

/**
 * What a swipe on a bundle's row does, or null where it doesn't move.
 *
 * Only a closed bundle swipes, as a thread's row does: rightward snoozes every thread in it,
 * leftward marks them all done (its Done all). An open bundle's header doesn't swipe. While rows
 * are selected it acts on this bundle alone, as a thread's swipe does.
 */
export function bundleSwipeAction(
  side: SwipeSide,
  open: boolean,
): BundleSwipeAction | null {
  if (open) return null;
  else return side === "start" ? "snooze" : "done";
}

/** The name shown under a swiped bundle: its action with how many threads it acts on, as "Done · 14" or "Snooze · 10+". */
export function bundleSwipeLabel(
  action: BundleSwipeAction,
  threads: string,
): string {
  return `${action === "done" ? "Done" : "Snooze"} · ${threads}`;
}
