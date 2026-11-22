package cc.hafa.inbox.core.mail

import java.math.BigInteger

// How long an ended snooze still counts, and the check for newer mail on snoozed threads: where its
// history scan starts, whether a scan cut short goes on, and which of its findings still hold. A
// port of shared/src/wake.ts; both run shared/testdata/wake-fixtures.json.

/** How long after a snooze ends the thread still sorts by that end, shows in the inbox and is checked. */
const val LAPSE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** Whether a snooze (epoch ms, or null for none) is still ahead or ended within [LAPSE_WINDOW_MS]. */
fun inLapseWindow(snoozeUntil: Long?, now: Long): Boolean = snoozeUntil != null && snoozeUntil > now - LAPSE_WINDOW_MS

/** Whether a snooze ended within [LAPSE_WINDOW_MS], so the thread is back in the inbox. */
fun recentlyLapsed(snoozeUntil: Long?, now: Long): Boolean =
    snoozeUntil != null && snoozeUntil <= now && inLapseWindow(snoozeUntil, now)

/** Compare two Gmail history ids, which are decimal strings too long for a double. */
fun compareHistoryIds(left: String, right: String): Int = BigInteger(left).compareTo(BigInteger(right)).coerceIn(-1, 1)

/** The lowest of some history ids, or null when there are none. */
fun lowestHistoryId(ids: Iterable<String>): String? = ids.minWithOrNull(::compareHistoryIds)

/** Where a check's history scan starts, and whether it goes on with the scan cut short before. */
data class ScanStart(
    /** the history id the scan reads from */
    val start: String,
    /** whether it goes on from the earlier scan's next page, keeping what that one read */
    val resume: Boolean,
)

/**
 * Where the scan for newer mail on threads snoozed at [watermarks] (thread id to history id) starts,
 * given where a scan cut short by its page limit started (null when none was). That scan goes on
 * while it started at or below every watermark; otherwise a new one starts at the lowest. Null when
 * there is nothing to check.
 */
fun scanStart(watermarks: Map<String, String>, unfinishedStart: String?): ScanStart? {
    val lowest = lowestHistoryId(watermarks.values) ?: return null
    return if (unfinishedStart != null && compareHistoryIds(unfinishedStart, lowest) <= 0) {
        ScanStart(unfinishedStart, resume = true)
    } else {
        ScanStart(lowest, resume = false)
    }
}

/**
 * The threads a finished scan found newer mail on that it still speaks for: those whose watermark
 * is the same now ([current]) as when the scan read it ([scanned]). A thread snoozed again
 * meanwhile has a new watermark, so it stays snoozed until the next scan.
 */
fun stillFound(found: Set<String>, scanned: Map<String, String>, current: Map<String, String>): Set<String> =
    found.filterTo(linkedSetOf()) { id -> current[id] != null && current[id] == scanned[id] }
