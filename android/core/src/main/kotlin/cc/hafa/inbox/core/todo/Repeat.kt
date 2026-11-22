package cc.hafa.inbox.core.todo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Ported from latr's android util/Repeat.kt, the same rule engine as shared/src/todos/repeat.ts;
// both run shared/testdata/repeat-fixtures.json.

/** How often a repeating todo comes back; [wire] is the stored name. */
enum class RepeatUnit(val wire: String) {
    /** every N days */
    DAY("day"),

    /** every N weeks, on one weekday */
    WEEK("week"),

    /** every N months, on one day of the month */
    MONTH("month"),
}

/** A rule as the editor makes it, before it has an anchor. */
data class RepeatDraft(
    /** 1..7: every N days, weeks or months */
    val every: Int,
    /** what N counts */
    val unit: RepeatUnit,
    /** ISO weekday (Monday = 1); used by [RepeatUnit.WEEK] */
    val weekday: Int,
    /** 1..31, or [Repeat.LAST_DAY]; used by [RepeatUnit.MONTH] */
    val monthDay: Int,
    /** `HH:mm` */
    val at: String,
) {
    /** The rule saved at [now] (local `YYYY-MM-DDTHH:mm:ss`), anchored on its first occurrence. */
    fun anchored(now: String): Repeat {
        val first = Repeat(1, unit, weekday, monthDay, at, now.take(10)).nextOccurrence(now)
        return Repeat(every, unit, weekday, monthDay, at, first.take(10))
    }
}

/** A repeating todo's rule; times are local wall-clock, like `snoozeUntil`. */
data class Repeat(
    /** 1..7: every N days, weeks or months */
    val every: Int,
    /** what N counts */
    val unit: RepeatUnit,
    /** ISO weekday (Monday = 1); used by [RepeatUnit.WEEK] */
    val weekday: Int,
    /** 1..31, or [LAST_DAY]; used by [RepeatUnit.MONTH] */
    val monthDay: Int,
    /** `HH:mm` */
    val at: String,
    /** `YYYY-MM-DD` the every-N count starts from: the first occurrence when the rule was saved */
    val anchor: String,
) {
    /** The stored map. */
    fun toMap(): Map<String, Any> = mapOf(
        "every" to every,
        "unit" to unit.wire,
        "weekday" to weekday,
        "monthDay" to monthDay,
        "at" to at,
        "anchor" to anchor,
    )

    private fun matches(date: LocalDate): Boolean {
        val anchorDate = LocalDate.parse(anchor)
        return when (unit) {
            RepeatUnit.DAY -> Math.floorMod(ChronoUnit.DAYS.between(anchorDate, date), every.toLong()) == 0L
            RepeatUnit.WEEK -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val anchorMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                date.dayOfWeek.value == weekday &&
                    Math.floorMod(ChronoUnit.WEEKS.between(anchorMonday, monday), every.toLong()) == 0L
            }
            RepeatUnit.MONTH -> {
                val length = date.lengthOfMonth()
                val target = if (monthDay == LAST_DAY) length else minOf(monthDay, length)
                val months = (date.year * 12 + date.monthValue) -
                    (anchorDate.year * 12 + anchorDate.monthValue)
                date.dayOfMonth == target && Math.floorMod(months, every) == 0
            }
        }
    }

    /** The first occurrence strictly after [after], both local `YYYY-MM-DDTHH:mm:ss`. */
    fun nextOccurrence(after: String): String {
        val afterTime = LocalDateTime.parse(after)
        val time = LocalTime.parse(at)
        var date = afterTime.toLocalDate()
        repeat(SEARCH_DAYS) {
            if (matches(date)) {
                val occurrence = LocalDateTime.of(date, time)
                if (occurrence.isAfter(afterTime)) return occurrence.format(LOCAL_ISO)
            }
            date = date.plusDays(1)
        }
        error("no occurrence of $this after $after")
    }

    /** The repeating row's second line, e.g. "Repeats every Monday at 07:00". */
    fun text(): String {
        val plural = every > 1
        val schedule = when (unit) {
            RepeatUnit.DAY -> if (plural) "every $every days" else "every day"
            RepeatUnit.WEEK -> {
                val name = WEEKDAY_NAMES[weekday - 1]
                if (plural) "every $every weeks on $name" else "every $name"
            }
            RepeatUnit.MONTH -> {
                val day = if (monthDay == LAST_DAY) "the last day" else "the ${ordinal(monthDay)}"
                if (plural) "every $every months on $day" else "every month on $day"
            }
        }
        return "Repeats $schedule at $at"
    }

    /** Reading stored rules. */
    companion object {
        /** [monthDay] for the last day of each month. */
        const val LAST_DAY = -1

        // Longest gap between two occurrences is 7 months; a date search past this has gone wrong.
        private const val SEARCH_DAYS = 400
        private val LOCAL_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
        private val WEEKDAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        private val CLOCK = Regex("""\d{2}:\d{2}""")
        private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        private fun intIn(value: Any?, range: IntRange): Int? {
            val number = value as? Number ?: return null
            val asInt = number.toInt()
            return asInt.takeIf { number.toDouble() == asInt.toDouble() && asInt in range }
        }

        private fun validDate(value: String): Boolean =
            DATE.matches(value) && try {
                LocalDate.parse(value)
                true
            } catch (_: DateTimeParseException) {
                false
            }

        private fun validClock(value: String): Boolean =
            CLOCK.matches(value) && value.take(2).toInt() < 24 && value.takeLast(2).toInt() < 60

        /** The draft fields of a stored map, or null when any is missing or malformed. */
        fun readDraft(value: Any?): RepeatDraft? {
            val raw = value as? Map<*, *> ?: return null
            val every = intIn(raw["every"], 1..7) ?: return null
            val unit = RepeatUnit.entries.firstOrNull { it.wire == raw["unit"] } ?: return null
            val weekday = intIn(raw["weekday"], 1..7) ?: return null
            val monthDay = intIn(raw["monthDay"], LAST_DAY..31)?.takeIf { it != 0 } ?: return null
            val at = (raw["at"] as? String)?.takeIf(::validClock) ?: return null
            return RepeatDraft(every, unit, weekday, monthDay, at)
        }

        /** A stored rule, or null when it is missing or malformed. */
        fun read(value: Any?): Repeat? {
            val draft = readDraft(value) ?: return null
            val anchor = ((value as Map<*, *>)["anchor"] as? String)?.takeIf(::validDate) ?: return null
            return Repeat(draft.every, draft.unit, draft.weekday, draft.monthDay, draft.at, anchor)
        }

        private fun ordinal(day: Int): String = when {
            day % 100 in 11..13 -> "${day}th"
            day % 10 == 1 -> "${day}st"
            day % 10 == 2 -> "${day}nd"
            day % 10 == 3 -> "${day}rd"
            else -> "${day}th"
        }
    }
}
