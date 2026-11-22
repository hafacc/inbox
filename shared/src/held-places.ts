/**
 * Places an open thread or bundle keeps in the list until it closes, so acting on it (or on the
 * threads of an open bundle) doesn't move it under the user, as latr keeps a todo being edited in
 * place. A list sorts and sections a held thread or bundle by its held place instead of its live
 * one; once it closes it lets go and moves to where it belongs. The focused thread or todo holds
 * only its pinning, so pinning or unpinning it moves it once focus leaves, while anything else moves
 * it at once.
 */

/** Where an open thread sat when it opened. */
export type ThreadPlace = {
  /** the date it sorted by, epoch milliseconds */
  date: number;
  /** whether it sat with the pinned threads */
  pinned: boolean;
  /** whether it sat on its own row rather than in a bundle */
  single: boolean;
};

/** Places held for open threads and bundles. */
export type HeldPlaces = {
  /** open threads, by thread id */
  threads: ReadonlyMap<string, ThreadPlace>;
  /** open bundles by label id: the date of their newest thread when they opened, epoch milliseconds */
  bundles: ReadonlyMap<string, number>;
  /** focused threads not open, by thread id, and focused todos, by row key: whether they were pinned when focus came to them */
  pins: ReadonlyMap<string, boolean>;
};

/** No places held. */
export const NO_HELD_PLACES: HeldPlaces = {
  threads: new Map(),
  bundles: new Map(),
  pins: new Map(),
};

function holdEach<Place>(
  held: ReadonlyMap<string, Place>,
  open: ReadonlyMap<string, Place>,
  release: boolean,
): ReadonlyMap<string, Place> {
  const next = new Map<string, Place>();
  for (const [key, place] of held) {
    if (!release || open.has(key)) next.set(key, place);
  }
  for (const [key, place] of open) {
    if (!next.has(key)) next.set(key, place);
  }
  const same =
    next.size === held.size && [...next.keys()].every((key) => held.has(key));
  return same ? held : next;
}

/**
 * The places to hold while `open` is open, each open thread and bundle at the place the list shows
 * it: one held before keeps its held place, one newly open holds the place it shows at, and one no
 * longer open lets go, unless `release` is false (while its close still plays). A focused thread's
 * pinning is held the same way, but lets go as soon as focus leaves, since nothing plays then.
 * Returns `held` itself when nothing changed.
 */
export function holdPlaces(
  held: HeldPlaces,
  open: HeldPlaces,
  release = true,
): HeldPlaces {
  const threads = holdEach(held.threads, open.threads, release);
  const bundles = holdEach(held.bundles, open.bundles, release);
  const pins = holdEach(held.pins, open.pins, true);
  return threads === held.threads &&
    bundles === held.bundles &&
    pins === held.pins
    ? held
    : { threads, bundles, pins };
}
