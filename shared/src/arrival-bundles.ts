// Which labels fold a thread into a bundle as new mail arrives, for the apps and the server's choice
// not to notify about bundled mail. Imported by relative path from functions/, so it imports nothing.

/** The label the apps pin a thread with (the inbox's pinned list is its own list of this label). */
export const PINNED_LABEL = "STARRED";

/** Gmail's categories the inbox bundles like user labels; Primary isn't one. */
const BUNDLED_CATEGORIES: ReadonlySet<string> = new Set([
  "CATEGORY_PROMOTIONS",
  "CATEGORY_SOCIAL",
  "CATEGORY_UPDATES",
  "CATEGORY_FORUMS",
]);

/**
 * The labels that could fold a thread with these labels into a bundle as new mail arrives: its user
 * labels (Gmail gives them ids starting `Label_`) and categories other than Primary, without those
 * whose bundling is off (`bundleOff`, from `labelPrefs`). None for a pinned (starred) thread, which
 * never sits in a bundle.
 */
export function arrivalBundleLabels(
  threadLabels: readonly string[],
  bundleOff: readonly string[],
): string[] {
  if (threadLabels.includes(PINNED_LABEL)) {
    return [];
  } else {
    return threadLabels.filter(
      (label) =>
        (label.startsWith("Label_") || BUNDLED_CATEGORIES.has(label)) &&
        !bundleOff.includes(label),
    );
  }
}

/**
 * Whether new mail in a thread with these labels counts as bundled, as the server judges it when
 * the user asks not to be notified about bundled mail: it has any of its
 * {@link arrivalBundleLabels}, however many inbox threads carry that label (even this one alone).
 * Snoozes, which each device decides, don't count.
 */
export function bundledOnArrival(
  threadLabels: readonly string[],
  bundleOff: readonly string[],
): boolean {
  return arrivalBundleLabels(threadLabels, bundleOff).length > 0;
}

/**
 * Whether a thread or todo coming back from its snooze counts as bundled, as Android judges it when
 * the user asks not to be notified about bundled mail: it isn't pinned and one of its labels (a
 * thread's own, or those a todo's tags name, see `todoLabelIds`) is one that bundles
 * (`bundleLabelIds`), however many inbox items carry that label.
 */
export function bundledOnWake(
  labelIds: readonly string[],
  pinned: boolean,
  bundleLabelIds: readonly string[],
): boolean {
  return !pinned && labelIds.some((id) => bundleLabelIds.includes(id));
}
