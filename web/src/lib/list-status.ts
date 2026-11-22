// What a list or an open bundle shows in place of rows it doesn't have: a placeholder while a load
// can still bring them, else the error with its Retry, else the empty text. A load that ended,
// however it ended, never leaves a placeholder up.

/** The part of a list's load state these choices read. */
export type ListLoad = {
  ids: readonly string[];
  nextPageToken: string | null;
  loading: boolean;
  loaded: boolean;
  error: string | null;
  moreError?: string | null;
};

/** What a list with no rows to show draws: a loading placeholder, the error and Retry, or the empty text. */
export type EmptyListState = "loading" | "error" | "empty";

/**
 * What a list draws when it has no rows to show: the placeholder before its first load ends, while
 * a load runs with nothing saved, or while later pages may still bring rows (they load by
 * themselves); the error once a load failed; else the empty text.
 */
export function emptyListState(list: ListLoad): EmptyListState {
  if (list.error || list.moreError) return "error";
  if (
    !list.loaded ||
    (list.loading && list.ids.length === 0) ||
    list.nextPageToken !== null
  ) {
    return "loading";
  }
  return "empty";
}

/** The foot of an open bundle: show the rest of what's loaded, fetch more, a fetch running, its failure, or nothing. */
export type BundleFooter = "all" | "more" | "loading" | "retry" | null;

/**
 * The foot of an open bundle with `hidden` loaded threads not shown yet: "Show N more" first; else,
 * while Gmail has more (`more`), the fetch running, its failure with Retry, or the button to fetch.
 */
export function bundleFooter(
  hidden: number,
  more: boolean,
  loadingMore: boolean,
  moreError: string | null,
): BundleFooter {
  if (hidden > 0) return "all";
  if (!more) return null;
  if (loadingMore) return "loading";
  if (moreError) return "retry";
  return "more";
}

/**
 * Whether a list fetches its next page by itself, as its end comes into view: not while a load
 * runs, not without a next page, and not after a page failed (its thread reads included), which
 * waits for Retry rather than spend more calls.
 */
export function pagesOnItsOwn(list: ListLoad, endShown: boolean): boolean {
  return (
    endShown && !list.loading && list.nextPageToken !== null && !list.moreError
  );
}

/** The ids a Retry reads again: listed threads with no summary that Gmail hasn't said are gone. */
export function missingThreadIds(
  ids: readonly string[],
  known: (id: string) => boolean,
  gone: ReadonlySet<string>,
): string[] {
  return ids.filter((id) => !known(id) && !gone.has(id));
}
