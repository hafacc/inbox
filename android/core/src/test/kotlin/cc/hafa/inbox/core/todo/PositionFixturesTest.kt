package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.floor

/** Runs `shared/testdata/position-fixtures.json`, as shared/src/todos/position.spec.ts does. */
class PositionFixturesTest {
    private val fixtures = Fixtures.load("position-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Test
    fun timeKey() {
        for (case in cases("timeKey")) {
            // Web floors a fractional time; Android's times are whole milliseconds already.
            val epoch = floor((case["epoch"] as Number).toDouble()).toLong()
            assertEquals("${case["epoch"]}", case["key"], Position.timeKey(epoch))
        }
    }

    @Test
    fun idKey() {
        for (case in cases("idKey")) assertEquals(case["key"], Position.idKey(case["id"] as String))
    }

    @Test
    fun readPosition() {
        for (case in cases("readPosition")) assertEquals("${case["in"]}", case["out"], Position.readPosition(case["in"]))
    }

    @Test
    fun between() {
        for (case in cases("between")) {
            val lo = case["lo"] as String
            val hi = case["hi"] as String
            assertEquals("$lo $hi", case["out"], Position.between(lo, hi))
        }
    }

    @Test
    fun positionBetween() {
        for (case in cases("positionBetween")) {
            val lo = case["lo"] as String
            val hi = case["hi"] as String
            assertEquals("$lo $hi", case["out"], Position.positionBetween(lo, hi, case["id"] as String))
        }
    }

    @Test
    fun positionAbove() {
        for (case in cases("positionAbove")) {
            assertEquals("${case["lo"]}", case["out"], Position.positionAbove(case["lo"] as String, case["id"] as String))
        }
    }

    @Test
    fun positionBelow() {
        for (case in cases("positionBelow")) {
            assertEquals("${case["hi"]}", case["out"], Position.positionBelow(case["hi"] as String, case["id"] as String))
        }
    }
}
