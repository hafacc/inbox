package cc.hafa.inbox.core.notify

import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CLEAR_KIND = "clear"

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", SnoozeSuggestions.LABEL_LOCALE)

/** The server's ask to clear notifications about threads read or taken out of the inbox elsewhere. */
data class ClearPush(
    /** the account it is for */
    val account: String,
    /** Gmail thread ids */
    val threadIds: List<String>,
)

/** Read a clear push's FCM data map; see `functions/src/lib/notification.ts`. Null when it isn't one. */
fun parseClear(data: Map<String, String>): ClearPush? {
    if (data["kind"] != CLEAR_KIND) return null
    val threadIds = data["threadIds"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    return if (threadIds.isEmpty()) null else ClearPush(data["account"].orEmpty(), threadIds)
}

/**
 * Whether a new-mail notification posted for mail that arrived at [arrivedAt] (epoch ms) no longer
 * applies, by what the app knows of its thread: read, out of the inbox, or snoozed. A [summary]
 * older than that mail (from before it came) says nothing either way.
 */
fun mailNotificationStale(arrivedAt: Long, summary: ThreadSummary?, snoozed: Boolean): Boolean =
    summary != null && summary.lastDate >= arrivedAt && (snoozed || !summary.isInInbox || !summary.unread)

/**
 * The time a notification's Snooze picks: the first time the snooze sheet shows from [stats] — its
 * first suggestion, else its last custom time, else the time its picker opens on (the strongest
 * quick time on the picker's first day when still ahead, else the next quarter hour).
 */
fun notificationSnoozeTime(stats: SnoozeStatsSnapshot, now: Instant, zone: ZoneId): Long {
    val partitions = listOf(stats)
    val suggestions = SnoozeSuggestions.shownRows(SnoozeSuggestions.rankDeep(partitions, now, zone))
    return suggestions.firstOrNull()?.epochMillis
        ?: SnoozeSuggestions.lastRow(partitions, now, suggestions)
        ?: pickerStart(stats, now, zone)
}

// The day and time the snooze sheet's date and time picker opens on, as epoch milliseconds.
private fun pickerStart(stats: SnoozeStatsSnapshot, now: Instant, zone: ZoneId): Long {
    val date = SnoozeSuggestions.defaultCustomDate(LocalDateTime.ofInstant(now, zone))
    val best = SnoozeSuggestions.quickTimes(listOf(stats), now).maxByOrNull { it.weight }
    val bestEpoch = best?.let { SnoozeSuggestions.quickTimeEpoch(date, it.clockMinutes, zone) }
    return if (bestEpoch != null && bestEpoch > now.toEpochMilli()) {
        bestEpoch
    } else {
        LocalDateTime.of(date, SnoozeSuggestions.nextQuarterHour(LocalTime.ofInstant(now, zone))).atZone(zone).toInstant().toEpochMilli()
    }
}

/** The Snooze button's caption for [epochMillis]: the time for today, else the weekday and time. */
fun snoozeActionLabel(epochMillis: Long, now: Instant, zone: ZoneId): String {
    val target = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val clock = SnoozeSuggestions.formatClock(epochMillis, zone)
    return if (target.toLocalDate() == LocalDate.ofInstant(now, zone)) {
        "Snooze until $clock"
    } else {
        "Snooze until ${target.format(DAY_FORMAT)} $clock"
    }
}
