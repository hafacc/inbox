/** How many recent searches a device keeps. */
export const RECENT_SEARCH_LIMIT = 8;

function sameSearch(left: string, right: string): boolean {
  return left.trim().toLowerCase() === right.trim().toLowerCase();
}

/**
 * The recent searches, newest first, after running `query`: trimmed, put first in the spelling
 * just used, any earlier spelling of it (ignoring case) dropped, and the oldest past
 * {@link RECENT_SEARCH_LIMIT} let go. A blank query changes nothing.
 */
export function addRecentSearch(
  recent: readonly string[],
  query: string,
): string[] {
  const trimmed = query.trim();
  if (trimmed === "") return [...recent];
  else {
    return [
      trimmed,
      ...recent.filter((entry) => !sameSearch(entry, trimmed)),
    ].slice(0, RECENT_SEARCH_LIMIT);
  }
}

/** The recent searches without `query` (ignoring case). */
export function removeRecentSearch(
  recent: readonly string[],
  query: string,
): string[] {
  return recent.filter((entry) => !sameSearch(entry, query));
}
