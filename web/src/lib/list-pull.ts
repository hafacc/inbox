// Pulling the list down from its top to refresh it, at the distances of Android's PullToRefreshBox
// (Material 3's defaults).

/** How far the spinner comes down before letting go refreshes, in pixels (Material's 80 dp). */
export const LIST_PULL_THRESHOLD = 80;

/** How much of the finger's travel the spinner follows until the threshold. */
export const LIST_PULL_DRAG_RATIO = 0.5;

/** Where the spinner sits when the finger has gone `travel` pixels down past the list's top. */
export function listPullOffset(travel: number): number {
  const distance = Math.max(0, travel) * LIST_PULL_DRAG_RATIO;
  if (distance <= LIST_PULL_THRESHOLD) {
    return distance;
  } else {
    // Material's tension: past the threshold the spinner eases toward twice it and stops there.
    const tension = Math.min(2, distance / LIST_PULL_THRESHOLD - 1);
    return LIST_PULL_THRESHOLD * (1 + tension - tension ** 2 / 4);
  }
}

/** How far along a pull of `travel` pixels is toward refreshing, from 0 to 1. */
export function listPullProgress(travel: number): number {
  return Math.min(1, listPullOffset(travel) / LIST_PULL_THRESHOLD);
}

/** Whether letting go of a pull of `travel` pixels refreshes the list. */
export function refreshesByPull(travel: number): boolean {
  return travel * LIST_PULL_DRAG_RATIO >= LIST_PULL_THRESHOLD;
}
