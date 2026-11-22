package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-pager-fixtures.json`, as `shared/src/thread-pager.spec.ts` does on web. */
class ThreadPagerFixturesTest {
    private val fixtures = Fixtures.load("thread-pager-fixtures.json")

    private fun number(raw: Any?): Float = (raw as Number).toFloat()

    @Test
    fun starts() {
        for (case in Fixtures.cases(fixtures, "starts")) {
            assertEquals(case["name"] as String, case["expect"], pageStarts(number(case["x"]), number(case["width"])))
        }
    }

    @Test
    fun offset() {
        for (case in Fixtures.cases(fixtures, "offset")) {
            val offset = pageOffset(number(case["dx"]), case["hasPrevious"] as Boolean, case["hasNext"] as Boolean)
            assertEquals(case["name"] as String, number(case["expect"]), offset, 0.001f)
        }
    }

    @Test
    fun step() {
        for (case in Fixtures.cases(fixtures, "step")) {
            val step = pageStep(
                number(case["dx"]),
                number(case["width"]),
                number(case["velocity"]),
                case["hasPrevious"] as Boolean,
                case["hasNext"] as Boolean,
            )
            assertEquals(case["name"] as String, case["expect"], step?.name?.lowercase())
        }
    }
}
