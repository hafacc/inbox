package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Runs `shared/testdata/time-format-fixtures.json`, so dates and sizes read as on web. */
class TimeFormatTest {
    private val fixtures = Fixtures.load("time-format-fixtures.json")

    private fun epoch(local: Any?): Long = LocalDateTime.parse(local as String).atZone(NEW_YORK).toInstant().toEpochMilli()

    private fun check(section: String, format: (Long, Long) -> String) {
        for (case in Fixtures.cases(fixtures, section)) {
            assertEquals("$section ${case["date"]} at ${case["now"]}", case["expect"], format(epoch(case["date"]), epoch(case["now"])))
        }
    }

    @Test
    fun `clock times are 24-hour`() {
        assertEquals("19:05", formatClock(at(2026, 11, 2, 19, 5), NEW_YORK))
        assertEquals("00:00", formatClock(at(2026, 11, 2, 0, 0), NEW_YORK))
    }

    @Test
    fun listDate() = check("listDate") { epoch, now -> formatListDate(epoch, now, NEW_YORK) }

    @Test
    fun messageDate() = check("messageDate") { epoch, now -> formatMessageDate(epoch, now, NEW_YORK) }

    @Test
    fun wakeTime() = check("wakeTime") { epoch, now -> formatWakeTime(epoch, now, NEW_YORK) }

    @Test
    fun lapsedMarker() = check("lapsedMarker") { epoch, now -> lapsedMarker(epoch, now, NEW_YORK) }

    @Test
    fun size() {
        for (case in Fixtures.cases(fixtures, "size")) {
            val bytes = (case["bytes"] as Number).toLong()
            assertEquals("size $bytes", case["expect"], formatSize(bytes))
        }
    }
}
