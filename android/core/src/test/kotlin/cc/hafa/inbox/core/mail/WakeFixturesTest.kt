package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Runs `shared/testdata/wake-fixtures.json`, as shared/src/wake.spec.ts does. */
class WakeFixturesTest {
    private val fixtures = Fixtures.load("wake-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): Map<String, String> = raw as Map<String, String>

    @Test
    fun window() {
        for (case in cases("window")) {
            val name = case["name"] as String
            val until = (case["snoozeUntil"] as Number?)?.toLong()
            val now = (case["now"] as Number).toLong()
            assertEquals(name, case["inWindow"], inLapseWindow(until, now))
            assertEquals(name, case["recentlyLapsed"], recentlyLapsed(until, now))
        }
    }

    @Test
    fun start() {
        for (case in cases("start")) {
            val expected = (case["expect"] as Map<*, *>?)?.let { ScanStart(it["start"] as String, it["resume"] as Boolean) }
            assertEquals(case["name"] as String, expected, scanStart(strings(case["watermarks"]), case["unfinishedStart"] as String?))
        }
    }

    @Test
    fun still() {
        for (case in cases("still")) {
            val found = (case["found"] as List<*>).map { it as String }.toSet()
            assertEquals(
                case["name"] as String,
                case["expect"],
                stillFound(found, strings(case["scanned"]), strings(case["current"])).toList(),
            )
        }
    }

    @Test
    fun `history ids compare as big integers, not strings or doubles`() {
        assertEquals(-1, compareHistoryIds("9", "10"))
        assertEquals(1, compareHistoryIds("9007199254740993", "9007199254740992"))
        assertEquals(0, compareHistoryIds("42", "42"))
        assertEquals("99", lowestHistoryId(listOf("100", "99", "1000")))
        assertNull(lowestHistoryId(emptyList()))
    }
}
