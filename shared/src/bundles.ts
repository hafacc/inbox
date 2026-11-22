import { labelQueryValue } from "./search";

export {
  arrivalBundleLabels,
  bundledOnArrival,
  PINNED_LABEL,
} from "./arrival-bundles";

/** Threads a bundle's own inbox list asks Gmail for up front. */
export const BUNDLE_FIRST_PAGE = 10;

/** Threads each later page of a bundle's own inbox list asks Gmail for. */
export const BUNDLE_PAGE_SIZE = 50;

/** Threads the inbox's pinned list (`threads.list` of INBOX and STARRED) asks Gmail for. */
export const PINNED_PAGE = 100;

/** One bundle label's own inbox list (`threads.list` of INBOX and the label). */
export type BundleList = {
  /** the label */
  labelId: string;
  /** thread ids fetched so far, newest first */
  ids: readonly string[];
  /** whether Gmail has a next page */
  more: boolean;
};

/** A message as a history record carries it. */
type HistoryMessage = { message: { labelIds?: readonly string[] } };
/** A label change as a history record carries it. */
type HistoryLabels = HistoryMessage & { labelIds?: readonly string[] };

/** One `history.list` record, reduced to what decides which bundles changed. */
export type BundleHistoryRecord = {
  /** messages added */
  messagesAdded?: readonly HistoryMessage[];
  /** messages deleted for good */
  messagesDeleted?: readonly HistoryMessage[];
  /** labels added to messages */
  labelsAdded?: readonly HistoryLabels[];
  /** labels removed from messages */
  labelsRemoved?: readonly HistoryLabels[];
};

/**
 * The thread ids the inbox makes rows from: the inbox's own list, then each bundle list's threads
 * that still carry its label, each id once. The pinned list joins as one more list with label
 * {@link PINNED_LABEL}, since the inbox's own list leaves out pinned threads of bundled labels too.
 * Whether a thread is still in the inbox, snoozed or pinned is decided per row afterwards.
 */
export function inboxThreadIds(
  inboxIds: readonly string[],
  bundles: readonly BundleList[],
  labelsOf: (id: string) => readonly string[] | undefined,
): string[] {
  const ids = new Set(inboxIds);
  for (const bundle of bundles) {
    for (const id of bundle.ids) {
      if (labelsOf(id)?.includes(bundle.labelId)) ids.add(id);
    }
  }
  return [...ids];
}

/** A label as the inbox's own query names it. */
export type QueryLabel = {
  /** label id */
  id: string;
  /** label name */
  name: string;
};

// Characters a label name may hold and still be named in a query: Gmail's documentation doesn't say
// how `label:` reads any others (quotes, brackets, braces, punctuation).
const QUERY_SAFE_NAME = /^[\p{L}\p{N} /-]+$/u;

/**
 * The Gmail query that leaves out of the inbox's own list the threads of bundles whose own list
 * was fetched, so paging the inbox never reads them again; "" when there are none. A category is
 * left out with `category:`, any other label with `label:`. A label whose name holds other than
 * letters, digits, spaces, `/` and `-`, or whose query name (lower-case, spaces and `/` as `-`) is
 * shared with another of the `known` labels, isn't left out: its threads come in the inbox's own
 * list as well, which costs reads but can't hide a thread.
 */
export function inboxExclusion(
  labels: readonly QueryLabel[],
  known: readonly QueryLabel[] = labels,
): string {
  return labels
    .flatMap((label) => {
      const category = /^CATEGORY_(.+)$/.exec(label.id)?.[1];
      if (category === undefined) {
        const value = labelQueryValue(label.name);
        const shared = known.some(
          (other) =>
            other.id !== label.id && labelQueryValue(other.name) === value,
        );
        return QUERY_SAFE_NAME.test(label.name) && !shared
          ? [`-label:${value}`]
          : [];
      }
      const name = category.toLowerCase();
      return [`-category:${name === "personal" ? "primary" : name}`];
    })
    .join(" ");
}

/** The query and page token of the inbox's next page. */
export type InboxPageRequest = {
  /** Gmail query, "" for none */
  q: string;
  /** page token, null to start from the newest */
  pageToken: string | null;
};

/**
 * The inbox's next page. A page token belongs to the query it came from, so when more bundles
 * were left out since, paging starts over with the new query; the threads it finds again are
 * already known and cost no reads.
 */
export function inboxNextPage(
  query: string,
  pageToken: string | null,
  tokenQuery: string,
): InboxPageRequest {
  return { q: query, pageToken: query === tokenQuery ? pageToken : null };
}

/**
 * Bundle labels whose own list to fetch now: bundling on, not fetched yet, and either carried by a
 * thread of the inbox's own list or remembered from last session (see {@link bundlesToRemember}),
 * so the session's first inbox page can already leave them out. Any other label would sit below
 * the loaded inbox threads, so it waits until scrolling reaches it.
 */
export function bundlesToFetch(
  bundleLabelIds: readonly string[],
  inboxLabels: Iterable<readonly string[]>,
  fetched: ReadonlySet<string>,
  remembered: ReadonlySet<string> = new Set(),
): string[] {
  const seen = new Set<string>(remembered);
  for (const labels of inboxLabels) {
    for (const label of labels) seen.add(label);
  }
  return bundleLabelIds.filter((id) => seen.has(id) && !fetched.has(id));
}

/**
 * Bundle labels to remember on the device for the next session, which fetches them before its
 * first inbox page: those whose own list found inbox threads. A label whose list came back empty
 * is dropped, so it costs at most one empty list.
 */
export function bundlesToRemember(bundles: readonly BundleList[]): string[] {
  return bundles
    .filter((bundle) => bundle.ids.length > 0)
    .map((bundle) => bundle.labelId);
}

/** What a bundle row counts. */
export type BundleCounts = {
  /** threads, as "12" or "50+" */
  threads: string;
  /** unread threads, as "3 new" or "50+ new"; null when none */
  unread: string | null;
};

/**
 * A bundle row's counts. When Gmail has more of the label's inbox threads than were fetched,
 * both counts are lower bounds and read "N+".
 */
export function bundleCounts(
  threads: number,
  unread: number,
  more: boolean,
): BundleCounts {
  const plus = more ? "+" : "";
  return {
    threads: `${threads}${plus}`,
    unread: unread > 0 ? `${unread}${plus} new` : null,
  };
}

/** A loaded thread of a bundle, as Done all and a bundle's swipe see it. */
export type SweptThread = {
  /** its id */
  id: string;
  /** whether it is pinned */
  pinned: boolean;
};

/**
 * The threads Done all, or a swipe on a bundle, acts on: its loaded threads but pinned ones, then
 * the inbox threads of the label Gmail listed (`listed`, when Gmail has more than were fetched) but
 * those it listed as pinned (`pinned`), each once.
 */
export function bundleSweepIds(
  shown: readonly SweptThread[],
  listed: readonly string[],
  pinned: readonly string[],
): string[] {
  const skip = new Set(pinned);
  return [
    ...new Set([
      ...shown.filter((thread) => !thread.pinned).map((thread) => thread.id),
      ...listed.filter((id) => !skip.has(id)),
    ]),
  ];
}

/**
 * The bundle labels a page of history touched: a message carrying the label was added, deleted or
 * relabeled, or the label itself was added or removed. Their first page is fetched again; the
 * rest stay as they are.
 */
export function touchedBundles(
  records: readonly BundleHistoryRecord[],
  bundleLabelIds: readonly string[],
): string[] {
  const touched = new Set<string>();
  const note = (labels: readonly string[] | undefined) => {
    for (const label of labels ?? []) touched.add(label);
  };
  for (const record of records) {
    for (const entry of [
      ...(record.messagesAdded ?? []),
      ...(record.messagesDeleted ?? []),
    ]) {
      note(entry.message.labelIds);
    }
    for (const entry of [
      ...(record.labelsAdded ?? []),
      ...(record.labelsRemoved ?? []),
    ]) {
      note(entry.message.labelIds);
      note(entry.labelIds);
    }
  }
  return bundleLabelIds.filter((id) => touched.has(id));
}

/**
 * A paged list after a fresh first page: the page, then the loaded ids past the old position of
 * the page's last thread it already had. Old ids before that position that the page lacks are gone
 * (archived, say); a thread that rose onto the page leaves its old place. Null when the page shares
 * nothing with the old list, so the old pages no longer follow it.
 */
export function mergeFirstPage(
  existing: readonly string[],
  page: readonly string[],
): string[] | null {
  const position = new Map(existing.map((id, index) => [id, index]));
  const last = page.findLast((id) => position.has(id));
  if (last === undefined) return null;
  const onPage = new Set(page);
  const tail = existing
    .slice(position.get(last)! + 1)
    .filter((id) => !onPage.has(id));
  return [...page, ...tail];
}

/**
 * Whether a thread with these labels belongs in the inbox and its bundles: it carries `INBOX` and
 * neither `SPAM` nor `TRASH`. A thread's labels are its messages' together, so one message in the
 * inbox and another in spam gives both, and such a thread stays out.
 */
export function inInbox(labelIds: readonly string[]): boolean {
  return (
    labelIds.includes("INBOX") &&
    !labelIds.includes("SPAM") &&
    !labelIds.includes("TRASH")
  );
}

/** A bundle as {@link bundlesToTopUp} weighs it. */
export type BundleSupply = {
  /** the label */
  labelId: string;
  /** its threads the inbox shows now */
  shown: number;
  /** whether Gmail has a next page */
  more: boolean;
  /** whether a page is being fetched */
  loading: boolean;
  /** whether its last page failed to load */
  failed: boolean;
  /** whether it is open */
  open: boolean;
  /** whether the end of its open list is on screen */
  endShown: boolean;
  /** how many of its loaded threads follow the thread open in it, or null with none of its threads open */
  afterOpen: number | null;
};

/** Fewest loaded threads past the one open in a bundle before its next page is fetched, so swiping on finds one. */
export const PAGER_AHEAD = 3;

/**
 * Bundle labels whose next page to fetch now, as their loaded threads leave (done, snoozed, spam),
 * the end of an open one comes into view, or the thread open in one nears its end: Gmail has more,
 * no page is on its way and the last didn't fail, and the bundle has no thread left, or is open
 * with fewer than {@link BUNDLE_FIRST_PAGE}, its end on screen, or fewer than {@link PAGER_AHEAD}
 * threads past the open one.
 */
export function bundlesToTopUp(bundles: readonly BundleSupply[]): string[] {
  return bundles
    .filter(
      (bundle) =>
        bundle.more &&
        !bundle.loading &&
        !bundle.failed &&
        (bundle.shown === 0 ||
          (bundle.open &&
            (bundle.shown < BUNDLE_FIRST_PAGE ||
              bundle.endShown ||
              (bundle.afterOpen !== null && bundle.afterOpen < PAGER_AHEAD)))),
    )
    .map((bundle) => bundle.labelId);
}

/** Fewest inbox threads of a label, pinned ones aside, before they fold into one bundle. */
export const MIN_BUNDLE_SIZE = 2;
