package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.snooze.SnoozeSuggestions.LABEL_LOCALE
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A heading in a list and the key that identifies it. */
data class SectionHead(
    /** stable identity of the heading */
    val key: String,
    /** heading text */
    val title: String,
)

/** A run of items under one heading. */
data class Section<Item>(
    /** stable identity of the heading */
    val key: String,
    /** heading text */
    val title: String,
    /** items under it, in input order */
    val items: List<Item>,
)

private const val EVENING_HOUR = 18

private fun monthName(date: LocalDate): String = date.month.getDisplayName(TextStyle.FULL, LABEL_LOCALE)

private fun monthHead(date: LocalDate, today: LocalDate): SectionHead {
    val key = "month:${date.year}-${date.monthValue}"
    val title = if (date.year == today.year) monthName(date) else "${monthName(date)} ${date.year}"
    return SectionHead(key, title)
}

/** Sunday starting the week of [date]. */
private fun startOfWeek(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

private fun localDate(epoch: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()

/**
 * The section a message date falls in, looking back from now: Today, Yesterday, This week
 * (weeks start Sunday), This month, then one section per month for this year and last year
 * ("October", "December 2025"), then one per year before that ("2024"). Future dates count as today.
 */
fun pastSection(epoch: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): SectionHead {
    val date = localDate(epoch, zone)
    val today = localDate(now, zone)
    val distance = ChronoUnit.DAYS.between(date, today)
    return when {
        distance <= 0 -> SectionHead("today", "Today")
        distance == 1L -> SectionHead("yesterday", "Yesterday")
        !date.isBefore(startOfWeek(today)) -> SectionHead("week", "This week")
        date.year == today.year && date.month == today.month -> SectionHead("month", "This month")
        date.year < today.year - 1 -> SectionHead("year:${date.year}", "${date.year}")
        else -> monthHead(date, today)
    }
}

/**
 * The section a wake time falls in, looking ahead from now: Today (Tonight from 18:00), Tomorrow,
 * "This Saturday" for the rest of this week, Next week, then one section per month. A time already
 * past is due now, so it goes where now does: Today, or Tonight from 18:00.
 */
fun futureSection(epoch: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): SectionHead {
    val dateTime: ZonedDateTime = Instant.ofEpochMilli(epoch).atZone(zone)
    val date = dateTime.toLocalDate()
    val today = localDate(now, zone)
    val distance = ChronoUnit.DAYS.between(today, date)
    val nextWeek = startOfWeek(today).plusDays(7)
    val due = epoch <= now || distance <= 0
    val dueHour = if (epoch <= now) Instant.ofEpochMilli(now).atZone(zone).hour else dateTime.hour
    return when {
        due && dueHour >= EVENING_HOUR -> SectionHead("tonight", "Tonight")
        due -> SectionHead("today", "Today")
        distance == 1L -> SectionHead("tomorrow", "Tomorrow")
        date.isBefore(nextWeek) -> {
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, LABEL_LOCALE)
            SectionHead("day:$weekday", "This $weekday")
        }
        date.isBefore(nextWeek.plusDays(7)) -> SectionHead("next-week", "Next week")
        else -> monthHead(date, today)
    }
}

/** Group already-sorted items into consecutive sections, keeping the input order. */
fun <Item> sectionize(items: List<Item>, headOf: (Item) -> SectionHead): List<Section<Item>> {
    val heads = mutableListOf<SectionHead>()
    val runs = mutableListOf<MutableList<Item>>()
    for (item in items) {
        val head = headOf(item)
        if (heads.lastOrNull()?.key == head.key) {
            runs.last() += item
        } else {
            heads += head
            runs += mutableListOf(item)
        }
    }
    return heads.zip(runs) { head, run -> Section(head.key, head.title, run) }
}
