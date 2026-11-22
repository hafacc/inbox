package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.snooze.RowIcon
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeStatsWire
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** Runs `shared/testdata/send-fixtures.json`, so the schedule-send menu matches web's. */
class SendFixturesTest {
    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun epoch(local: Any?): Long = LocalDateTime.parse(local as String).atZone(zone).toInstant().toEpochMilli()

    private fun cases(section: String) = Fixtures.cases(Fixtures.send, section)

    // Web spells icons in camel case ("todayDay"); Kotlin's enum is TODAY_DAY.
    private fun iconName(icon: RowIcon): String =
        icon.name.lowercase().split("_").mapIndexed { index, word -> if (index == 0) word else word.replaceFirstChar(Char::uppercase) }.joinToString("")

    @Test
    fun rows() {
        for (case in cases("rows")) {
            val partitions = (case["partitions"] as List<*>).map { SnoozeStatsWire.fromWire(it as Map<*, *>, zone) }
            val rows = SendSuggestions.sendRows(partitions, Instant.ofEpochMilli(epoch(case["now"])), zone)
            val expected = (case["expect"] as List<*>).map {
                val row = it as Map<*, *>
                listOf(row["keyId"], epoch(row["timeLocal"]), row["text"], row["icon"], row["learned"])
            }
            val actual = rows.map { listOf(it.keyId, it.time, it.text, iconName(it.icon), it.learned) }
            assertEquals("${case["name"]}", expected, actual)
        }
    }

    @Test
    fun shift() {
        for (case in cases("shift")) {
            assertEquals("${case["unit"]}", (case["minutes"] as Number).toInt(), SendSuggestions.shiftMinutes((case["unit"] as Number).toDouble(), (case["maxMinutes"] as Number).toInt()))
        }
    }

    @Test
    fun random() {
        for (case in cases("random")) {
            val random = SendSuggestions.seededRandom((case["seed"] as Number).toLong())
            val expected = (case["first"] as List<*>).map { (it as Number).toDouble() }
            assertEquals("seed ${case["seed"]}", expected, expected.map { random() })
        }
    }

    @Test
    fun vary() {
        for (case in cases("vary")) {
            val shifted = SendSuggestions.varySendTimes(
                (case["timesLocal"] as List<*>).map(::epoch),
                epoch(case["now"]),
                SendSuggestions.seededRandom((case["seed"] as Number).toLong()),
                (case["maxMinutes"] as Number).toInt(),
            )
            val expected = (case["expect"] as List<*>).map {
                val entry = it as Map<*, *>
                ShiftedTime(epoch(entry["chosenAtLocal"]), epoch(entry["sendAtLocal"]))
            }
            assertEquals("${case["name"]}", expected, shifted)
        }
    }

    @Test
    fun setting() {
        for (case in cases("setting")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                "${case["settings"]}",
                VarySetting(expect["enabled"] as Boolean, (expect["maxMinutes"] as Number).toInt()),
                SendSuggestions.varySetting(case["settings"] as Map<*, *>?),
            )
        }
    }

    @Test
    fun shiftsAreWholeMinutesWithinTheDefaultNeverZero() {
        val random = SendSuggestions.seededRandom(99)
        val now = epoch("2026-09-22T10:00:00")
        val time = epoch("2026-09-23T08:00:00")
        val seen = mutableSetOf<Long>()
        repeat(500) {
            val minutes = (SendSuggestions.varySendTimes(listOf(time), now, random).single().sendAt - time) / 60_000
            assertNotEquals(0L, minutes)
            assertTrue(abs(minutes) <= SendSuggestions.DEFAULT_MAX_SHIFT_MINUTES)
            seen += minutes
        }
        assertEquals(2 * SendSuggestions.DEFAULT_MAX_SHIFT_MINUTES, seen.size)
    }

    @Test
    fun learningAPickTurnsItsDefaultIntoALearnedRow() {
        val now = Instant.ofEpochMilli(epoch("2026-09-22T10:00:00"))
        val first = SendSuggestions.sendRows(emptyList(), now, zone).first()
        val next = SnoozeSuggestions.commit(SnoozeStatsSnapshot(), now.toEpochMilli(), first.time, zone, "suggestion", first.keyId).next
        val rows = SendSuggestions.sendRows(listOf(next), now, zone)
        assertTrue(rows.size <= SendSuggestions.SEND_SHOWN_ROWS)
        assertEquals(true, rows.firstOrNull { it.time == first.time }?.learned)
    }

    @Test
    fun outboxShown() {
        for (case in cases("outboxShown")) {
            val docs = (case["docs"] as List<*>).mapIndexed { index, it ->
                val doc = it as Map<*, *>
                OutboxDoc("d$index", "draft$index", "me@example.com", OutboxStatus.of(doc["status"] as String), (doc["sendAt"] as Number).toLong(), null, null)
            }
            val expected = (case["expect"] as Number?)?.toLong()
            assertEquals("${case["name"]}", expected, outboxShownAt(docs, (case["nowMs"] as Number).toLong()))
        }
    }
}
