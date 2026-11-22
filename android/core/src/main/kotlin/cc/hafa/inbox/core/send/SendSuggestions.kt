package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.snooze.RowIcon
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

private const val MINUTE_MS = 60_000L

/** One schedule-send suggestion. */
data class SendRow(
    /** when it sends, epoch milliseconds, before any shift */
    val time: Long,
    /** label without its time, e.g. "Tomorrow morning" */
    val text: String,
    /** icon, as for snooze rows */
    val icon: RowIcon,
    /** engine key, passed back to the stats commit as the picked key */
    val keyId: String,
    /** learned weight; 0 for a default */
    val score: Double,
    /** whether it came from the user's history rather than the defaults */
    val learned: Boolean,
)

/** A suggestion as offered: the time the user chose and the time it will actually send. */
data class ShiftedTime(
    /** the unshifted suggestion; the only time that feeds `sendStats` */
    val chosenAt: Long,
    /** what the menu shows and `outbox.sendAt` holds */
    val sendAt: Long,
)

/** "Vary scheduled send times" as stored in `users/{uid}.settings`. */
data class VarySetting(
    /** whether suggested times are shifted (`settings.varySendTimes`, on when absent) */
    val enabled: Boolean,
    /** largest shift either way, whole minutes in 1..30 (`settings.varySendMaxMinutes`, 10 when absent) */
    val maxMinutes: Int,
)

/**
 * Learned schedule-send suggestions: the snooze engine's keys, counts and ranking fed by their own
 * stats doc, with send-shaped defaults and the "Vary scheduled send times" shift. A port of
 * `shared/src/send-suggest.ts`, pinned by `shared/testdata/send-fixtures.json`.
 */
object SendSuggestions {
    /** Rows the schedule-send menu shows, above the last custom time and "Pick date & time". */
    const val SEND_SHOWN_ROWS = 4

    /** Built-in suggestions, in priority order: next 08:00, next 13:00, Monday 08:00. */
    val SEND_DEFAULT_KEYS = listOf("D0@0800", "D0@1300", "Wd1@0800")

    /** A default is left out when another row lands closer than this. */
    const val DEFAULT_GAP_MS = 30 * MINUTE_MS

    /** How long undo send waits before the message goes; never varied. */
    const val UNDO_SEND_DELAY_MS = 10_000L

    /** Default largest shift, in whole minutes either way, for "Vary scheduled send times". */
    const val DEFAULT_MAX_SHIFT_MINUTES = 10

    /** Smallest largest shift the setting's slider offers. */
    const val MIN_MAX_SHIFT_MINUTES = 1

    /** Biggest largest shift the setting's slider offers. */
    const val MAX_MAX_SHIFT_MINUTES = 30

    // A shifted time must still be at least this far ahead of now.
    private const val MIN_LEAD_MS = MINUTE_MS

    /** Label of the menu item that opens the date and time picker. */
    const val PICK_DATE_TIME_LABEL = "Pick date & time…"

    /** Firestore document name of the learned send-time counts, beside `snoozeStats` under `meta`. */
    const val STATS_DOC = "sendStats"

    /**
     * The schedule-send menu's rows: learned rows first in the engine's greedy order, then the
     * defaults in [SEND_DEFAULT_KEYS] where there's room and no row within [DEFAULT_GAP_MS]; in
     * time order.
     */
    fun sendRows(partitions: List<SnoozeStatsSnapshot>, now: Instant, zone: ZoneId, count: Int = SEND_SHOWN_ROWS): List<SendRow> {
        val rows = SnoozeSuggestions.rankDeep(partitions, now, zone, count).map {
            SendRow(
                time = it.epochMillis,
                text = SnoozeSuggestions.labelText(it.key, it.epochMillis, now, zone),
                icon = SnoozeSuggestions.rowIcon(it.key, it.epochMillis, now, zone),
                keyId = it.key,
                score = it.score,
                learned = true,
            )
        }.toMutableList()
        for (keyId in SEND_DEFAULT_KEYS) {
            if (rows.size >= count) break
            val time = SnoozeSuggestions.resolveFuture(keyId, now, zone) ?: continue
            if (rows.any { abs(it.time - time) < DEFAULT_GAP_MS }) continue
            rows += SendRow(
                time = time,
                text = SnoozeSuggestions.labelText(keyId, time, now, zone),
                icon = SnoozeSuggestions.rowIcon(keyId, time, now, zone),
                keyId = keyId,
                score = 0.0,
                learned = false,
            )
        }
        return rows.sortedBy { it.time }
    }

    /** The last custom time row's target, if it's still ahead and no row lands at exactly that time. */
    fun lastRow(partitions: List<SnoozeStatsSnapshot>, now: Instant, rows: List<SendRow>): Long? {
        val last = SnoozeSuggestions.latestLastCustom(partitions) ?: return null
        if (last.target <= now.toEpochMilli()) return null
        if (rows.any { it.time == last.target }) return null
        return last.target
    }

    /**
     * Read the vary setting from `users/{uid}.settings`: `varySendTimes` is on unless it is exactly
     * `false`; `varySendMaxMinutes` is rounded half up and clamped to 1..30, and anything that isn't
     * a finite number reads as 10.
     */
    fun varySetting(settings: Map<*, *>?): VarySetting {
        val enabled = settings?.get("varySendTimes") != false
        val stored = (settings?.get("varySendMaxMinutes") as? Number)?.toDouble()
        val maxMinutes = if (stored != null && stored.isFinite()) {
            floor(stored + 0.5).coerceIn(MIN_MAX_SHIFT_MINUTES.toDouble(), MAX_MAX_SHIFT_MINUTES.toDouble()).toInt()
        } else {
            DEFAULT_MAX_SHIFT_MINUTES
        }
        return VarySetting(enabled, maxMinutes)
    }

    /**
     * The shift for one uniform draw in [0, 1): a whole number of minutes in -max..-1 or 1..max,
     * never 0. The 2·max choices split [0, 1) evenly and map in order.
     */
    fun shiftMinutes(unit: Double, maxMinutes: Int = DEFAULT_MAX_SHIFT_MINUTES): Int {
        val choices = 2 * maxMinutes
        val index = floor(unit * choices).toInt().coerceIn(0, choices - 1)
        return if (index < maxMinutes) index - maxMinutes else index - maxMinutes + 1
    }

    /**
     * Shift each suggested time for "Vary scheduled send times" by up to [maxMinutes] either way,
     * one draw from [random] per time, in order. A backward shift that would leave a minute or less
     * before sending flips forward.
     */
    fun varySendTimes(times: List<Long>, now: Long, random: () -> Double, maxMinutes: Int = DEFAULT_MAX_SHIFT_MINUTES): List<ShiftedTime> =
        times.map { chosenAt ->
            var minutes = shiftMinutes(random(), maxMinutes)
            if (chosenAt + minutes * MINUTE_MS <= now + MIN_LEAD_MS) minutes = abs(minutes)
            ShiftedTime(chosenAt, chosenAt + minutes * MINUTE_MS)
        }

    /** The same times unshifted, for when the setting is off. */
    fun unvariedSendTimes(times: List<Long>): List<ShiftedTime> = times.map { ShiftedTime(it, it) }

    /**
     * A seeded random source (mulberry32) returning numbers in [0, 1), the same arithmetic as web's
     * `seededRandom`. Seed it once per menu opening so the shown times hold still.
     */
    fun seededRandom(seed: Long): () -> Double {
        var state = seed.toInt()
        return {
            state += 0x6D2B79F5
            var mixed = state
            mixed = (mixed xor (mixed ushr 15)) * (mixed or 1)
            mixed = mixed xor (mixed + (mixed xor (mixed ushr 7)) * (mixed or 61))
            ((mixed xor (mixed ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }
}
