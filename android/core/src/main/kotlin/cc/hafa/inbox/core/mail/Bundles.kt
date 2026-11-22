package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.search.labelQueryValue

/** Threads a bundle's own inbox list asks Gmail for up front. */
const val BUNDLE_FIRST_PAGE = 10

/** Threads each later page of a bundle's own inbox list asks Gmail for. */
const val BUNDLE_PAGE_SIZE = 50

/** Threads the inbox's pinned list (`threads.list` of INBOX and STARRED) asks Gmail for. */
const val PINNED_PAGE = 100

/** The label of the inbox's pinned list, passed to [inboxThreadIds] like a bundle's. */
const val PINNED_LABEL = "STARRED"

/** The list key of the inbox's pinned threads. */
const val PINNED_KEY = "pinned:inbox"

/** The list key of the inbox in focus mode, which Gmail's query narrows to pinned threads. */
const val FOCUS_KEY = "focus:inbox"

/** The list key of a bundle label's own inbox list (`threads.list` of INBOX and the label). */
fun bundleKey(labelId: String): String = "bundle:$labelId"

/** One bundle label's own inbox list. */
data class BundleList(
    /** the label */
    val labelId: String,
    /** thread ids fetched so far, newest first */
    val ids: List<String>,
    /** whether Gmail has a next page */
    val more: Boolean,
)

/**
 * The thread ids the inbox makes rows from: the inbox's own list, then each bundle list's threads
 * that still carry its label, each id once. The pinned list joins as one more list with label
 * [PINNED_LABEL], since the inbox's own list leaves out pinned threads of bundled labels too.
 * Whether a thread is still in the inbox, snoozed or pinned is decided per row afterwards.
 */
fun inboxThreadIds(inboxIds: List<String>, bundles: List<BundleList>, labelsOf: (String) -> List<String>?): List<String> {
    val ids = LinkedHashSet(inboxIds)
    for (bundle in bundles) {
        for (id in bundle.ids) if (labelsOf(id)?.contains(bundle.labelId) == true) ids += id
    }
    return ids.toList()
}

/** A label as the inbox's own query names it. */
data class QueryLabel(
    /** label id */
    val id: String,
    /** label name */
    val name: String,
)

// Characters a label name may hold and still be named in a query: Gmail's documentation doesn't say
// how `label:` reads any others (quotes, brackets, braces, punctuation).
private val QUERY_SAFE_NAME = Regex("^[\\p{L}\\p{N} /-]+$")

/**
 * The Gmail query that leaves out of the inbox's own list the threads of bundles whose own list
 * was fetched, so paging the inbox never reads them again; "" when there are none. A category is
 * left out with `category:`, any other label with `label:`. A label whose name holds other than
 * letters, digits, spaces, `/` and `-`, or whose query name (lower-case, spaces and `/` as `-`) is
 * shared with another of the [known] labels, isn't left out: its threads come in the inbox's own
 * list as well, which costs reads but can't hide a thread.
 */
fun inboxExclusion(labels: List<QueryLabel>, known: List<QueryLabel> = labels): String = labels.mapNotNull { label ->
    val category = label.id.removePrefix("CATEGORY_").takeIf { it != label.id }?.lowercase()
    when (category) {
        null -> {
            val value = labelQueryValue(label.name)
            val shared = known.any { it.id != label.id && labelQueryValue(it.name) == value }
            if (QUERY_SAFE_NAME.matches(label.name) && !shared) "-label:$value" else null
        }
        "personal" -> "-category:primary"
        else -> "-category:$category"
    }
}.joinToString(" ")

/** The query and page token of the inbox's next page. */
data class InboxPageRequest(
    /** Gmail query, "" for none */
    val q: String,
    /** page token, null to start from the newest */
    val pageToken: String?,
)

/**
 * The inbox's next page. A page token belongs to the query it came from, so when more bundles
 * were left out since, paging starts over with the new query; the threads it finds again are
 * already known and cost no reads.
 */
fun inboxNextPage(query: String, pageToken: String?, tokenQuery: String): InboxPageRequest =
    InboxPageRequest(query, if (query == tokenQuery) pageToken else null)

/**
 * Bundle labels whose own list to fetch now: bundling on, not fetched yet, and either carried by a
 * thread of the inbox's own list or remembered from last session (see [bundlesToRemember]), so the
 * session's first inbox page can already leave them out. Any other label would sit below the
 * loaded inbox threads, so it waits until scrolling reaches it.
 */
fun bundlesToFetch(
    bundleLabelIds: List<String>,
    inboxLabels: List<List<String>>,
    fetched: Set<String>,
    remembered: Set<String> = emptySet(),
): List<String> {
    val seen = inboxLabels.flatten().toSet() + remembered
    return bundleLabelIds.filter { it in seen && it !in fetched }
}

/**
 * Bundle labels to remember on the device for the next session, which fetches them before its
 * first inbox page: those whose own list found inbox threads. A label whose list came back empty
 * is dropped, so it costs at most one empty list.
 */
fun bundlesToRemember(bundles: List<BundleList>): List<String> = bundles.filter { it.ids.isNotEmpty() }.map { it.labelId }

/** What a bundle row counts. */
data class BundleCounts(
    /** threads, as "12" or "50+" */
    val threads: String,
    /** unread threads, as "3 new" or "50+ new"; null when none */
    val unread: String?,
)

/**
 * A bundle row's counts. When Gmail has more of the label's inbox threads than were fetched,
 * both counts are lower bounds and read "N+".
 */
fun bundleCounts(threads: Int, unread: Int, more: Boolean): BundleCounts {
    val plus = if (more) "+" else ""
    return BundleCounts("$threads$plus", if (unread > 0) "$unread$plus new" else null)
}

/** A loaded thread of a bundle, as Done all and a bundle's swipe see it. */
data class SweptThread(
    /** its id */
    val id: String,
    /** whether it is pinned */
    val pinned: Boolean,
)

/**
 * The threads Done all, or a swipe on a bundle, acts on: its loaded threads but pinned ones, then
 * the inbox threads of the label Gmail listed ([listed], when Gmail has more than were fetched) but
 * those it listed as [pinned], each once.
 */
fun bundleSweepIds(shown: List<SweptThread>, listed: List<String>, pinned: List<String>): List<String> {
    val skip = pinned.toSet()
    return (shown.filterNot { it.pinned }.map { it.id } + listed.filterNot { it in skip }).distinct()
}

/**
 * The bundle labels a page of history touched: a message carrying the label was added, deleted or
 * relabeled, or the label itself was added or removed. Their first page is fetched again; the
 * rest stay as they are.
 */
fun touchedBundles(records: List<GmailHistoryRecord>, bundleLabelIds: List<String>): List<String> {
    val touched = mutableSetOf<String>()
    for (record in records) {
        for (entry in record.messagesAdded.orEmpty() + record.messagesDeleted.orEmpty()) {
            touched += entry.message.labelIds.orEmpty()
        }
        for (entry in record.labelsAdded.orEmpty() + record.labelsRemoved.orEmpty()) {
            touched += entry.message.labelIds.orEmpty()
            touched += entry.labelIds.orEmpty()
        }
    }
    return bundleLabelIds.filter { it in touched }
}

/**
 * A paged list after a fresh first page: the page, then the loaded ids past the old position of
 * the page's last thread it already had. Old ids before that position that the page lacks are gone
 * (archived, say); a thread that rose onto the page leaves its old place. Null when the page shares
 * nothing with the old list, so the old pages no longer follow it.
 */
fun mergeFirstPage(existing: List<String>, page: List<String>): List<String>? {
    val position = existing.withIndex().associate { (index, id) -> id to index }
    val last = page.lastOrNull { it in position } ?: return null
    val onPage = page.toSet()
    val tail = existing.drop(position.getValue(last) + 1).filterNot { it in onPage }
    return page + tail
}

/**
 * Whether a thread with these labels belongs in the inbox and its bundles: it carries `INBOX` and
 * neither `SPAM` nor `TRASH`. A thread's labels are its messages' together, so one message in the
 * inbox and another in spam gives both, and such a thread stays out.
 */
fun inInbox(labelIds: List<String>): Boolean =
    Labels.INBOX in labelIds && Labels.SPAM !in labelIds && Labels.TRASH !in labelIds

/** A bundle as [bundlesToTopUp] weighs it. */
data class BundleSupply(
    /** the label */
    val labelId: String,
    /** its threads the inbox shows now */
    val shown: Int,
    /** whether Gmail has a next page */
    val more: Boolean,
    /** whether a page is being fetched */
    val loading: Boolean,
    /** whether its last page failed to load */
    val failed: Boolean,
    /** whether it is open */
    val open: Boolean,
    /** whether the end of its open list is on screen */
    val endShown: Boolean,
    /** how many of its loaded threads follow the thread open in it, or null with none of its threads open */
    val afterOpen: Int?,
)

/** Fewest loaded threads past the one open in a bundle before its next page is fetched, so swiping on finds one. */
const val PAGER_AHEAD = 3

/**
 * Bundle labels whose next page to fetch now, as their loaded threads leave (done, snoozed, spam),
 * the end of an open one comes into view, or the thread open in one nears its end: Gmail has more,
 * no page is on its way and the last didn't fail, and the bundle has no thread left, or is open
 * with fewer than [BUNDLE_FIRST_PAGE], its end on screen, or fewer than [PAGER_AHEAD] threads past
 * the open one.
 */
fun bundlesToTopUp(bundles: List<BundleSupply>): List<String> =
    bundles.filter { bundle ->
        val nearOpenEnd = bundle.afterOpen != null && bundle.afterOpen < PAGER_AHEAD
        val openWants = bundle.open && (bundle.shown < BUNDLE_FIRST_PAGE || bundle.endShown || nearOpenEnd)
        bundle.more && !bundle.loading && !bundle.failed && (bundle.shown == 0 || openWants)
    }.map { it.labelId }
