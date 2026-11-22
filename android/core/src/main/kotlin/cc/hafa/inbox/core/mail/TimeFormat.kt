package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.snooze.SnoozeSuggestions.LABEL_LOCALE
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** 24-hour `HH:mm`, the one clock format mail is shown and written in. */
internal val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", LABEL_LOCALE)
private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", LABEL_LOCALE)
private val SHORT_DATE_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", LABEL_LOCALE)
private val WEEKDAY_SHORT = DateTimeFormatter.ofPattern("EEE", LABEL_LOCALE)
private val WEEKDAY_LONG = DateTimeFormatter.ofPattern("EEEE", LABEL_LOCALE)

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L
private const val WEEK_DAYS = 7

private fun zoned(epoch: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(epoch).atZone(zone)

/** Whole calendar days from [from]'s day to [to]'s day (negative when [to] is earlier). */
fun dayDistance(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    ChronoUnit.DAYS.between(zoned(from, zone).toLocalDate(), zoned(to, zone).toLocalDate())

/** 24-hour `HH:mm` in local time. */
fun formatClock(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK_FORMAT.format(zoned(epoch, zone))

/** A list row's date: `HH:mm` today, `Oct 28` this year, `Oct 28, 2025` before. */
fun formatListDate(epoch: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = zoned(epoch, zone)
    return when {
        dayDistance(epoch, now, zone) == 0L -> formatClock(epoch, zone)
        date.year == zoned(now, zone).year -> SHORT_DATE.format(date)
        else -> SHORT_DATE_YEAR.format(date)
    }
}

/** A message header's date: `Today 08:47`, `Yesterday 08:47`, `Oct 30, 08:47`, `Oct 30, 2025, 08:47`. */
fun formatMessageDate(epoch: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = zoned(epoch, zone)
    val clock = formatClock(epoch, zone)
    return when (dayDistance(epoch, now, zone)) {
        0L -> "Today $clock"
        1L -> "Yesterday $clock"
        else -> if (date.year == zoned(now, zone).year) {
            "${SHORT_DATE.format(date)}, $clock"
        } else {
            "${SHORT_DATE_YEAR.format(date)}, $clock"
        }
    }
}

/** A wake time: `20:30` today, `Tue 09:00` within a week, `Nov 19 09:00` later. */
fun formatWakeTime(epoch: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val distance = dayDistance(now, epoch, zone)
    val clock = formatClock(epoch, zone)
    return when {
        distance == 0L -> clock
        distance in 1 until WEEK_DAYS -> "${WEEKDAY_SHORT.format(zoned(epoch, zone))} $clock"
        else -> "${SHORT_DATE.format(zoned(epoch, zone))} $clock"
    }
}

/** The amber marker on a thread whose snooze ended: `Snoozed · back at 09:00`. */
fun lapsedMarker(snoozeUntil: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val distance = dayDistance(snoozeUntil, now, zone)
    val clock = formatClock(snoozeUntil, zone)
    val date = zoned(snoozeUntil, zone)
    return when {
        distance == 0L -> "Snoozed · back at $clock"
        distance == 1L -> "Snoozed · back yesterday at $clock"
        distance < WEEK_DAYS -> "Snoozed · back ${WEEKDAY_LONG.format(date)} at $clock"
        else -> "Snoozed · back ${SHORT_DATE.format(date)} at $clock"
    }
}

/** Human file size: `412 B`, `412 KB` (rounded), `1.3 MB` (one decimal). */
fun formatSize(bytes: Long): String {
    val kilobytes = (bytes.toDouble() / KILOBYTE).roundToLong()
    return when {
        bytes < KILOBYTE -> "$bytes B"
        kilobytes < KILOBYTE -> "$kilobytes KB"
        else -> String.format(LABEL_LOCALE, "%.1f MB", bytes.toDouble() / MEGABYTE)
    }
}
