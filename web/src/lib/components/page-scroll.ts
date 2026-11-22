// Where the app last scrolled the page itself, until the scroll event for it arrives.
let appScrolledTo: number | null = null;

/** Scroll the page by `top` pixels at once, as the app rather than the user (see {@link isAppScroll}). */
export function scrollPageBy(top: number): void {
  window.scrollBy({ top, behavior: "instant" });
  appScrolledTo = window.scrollY;
}

/** Scroll the page to `top` at once, as the app rather than the user (see {@link isAppScroll}). */
export function scrollPageTo(top: number): void {
  window.scrollTo({ top, left: 0, behavior: "instant" });
  appScrolledTo = window.scrollY;
}

/**
 * Whether the scroll that put the page at `y` was the app's own (holding a row in place,
 * restoring a view's place) rather than the user's. Each scroll event asks once.
 */
export function isAppScroll(y: number): boolean {
  const own = appScrolledTo !== null && Math.abs(y - appScrolledTo) < 1;
  appScrolledTo = null;
  return own;
}
