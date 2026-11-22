package cc.hafa.inbox.core.search

/** How many recent searches a device keeps. */
const val RECENT_SEARCH_LIMIT = 8

private fun sameSearch(left: String, right: String): Boolean = left.trim().lowercase() == right.trim().lowercase()

/**
 * The recent searches, newest first, after running [query]: trimmed, put first in the spelling
 * just used, any earlier spelling of it (ignoring case) dropped, and the oldest past
 * [RECENT_SEARCH_LIMIT] let go. A blank query changes nothing. As `shared/src/recent-searches.ts`.
 */
fun addRecentSearch(recent: List<String>, query: String): List<String> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty()) {
        recent
    } else {
        (listOf(trimmed) + recent.filterNot { sameSearch(it, trimmed) }).take(RECENT_SEARCH_LIMIT)
    }
}

/** The recent searches without [query] (ignoring case). */
fun removeRecentSearch(recent: List<String>, query: String): List<String> = recent.filterNot { sameSearch(it, query) }
