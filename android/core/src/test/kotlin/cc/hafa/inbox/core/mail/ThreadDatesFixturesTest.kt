package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-date-fixtures.json`, so lists place threads the same way as on web. */
class ThreadDatesFixturesTest {
    private val fixtures = Fixtures.load("thread-date-fixtures.json")

    private fun number(value: Any?): Long = (value as Number).toLong()

    @Test
    fun dates() {
        for (case in Fixtures.cases(fixtures, "dates")) {
            val messages = (case["messages"] as List<*>).map { message ->
                message as Map<*, *>
                DatedMessage(number(message["date"]), (message["labelIds"] as List<*>).map { it as String })
            }
            val expect = case["expect"] as Map<*, *>
            assertEquals(case["name"] as String, ThreadDates(number(expect["received"]), number(expect["sent"])), threadDates(messages))
        }
    }

    @Test
    fun order() {
        for (case in Fixtures.cases(fixtures, "order")) {
            fun dated(list: Any?): List<Pair<String, Long>> = (list as List<*>).map { thread ->
                thread as Map<*, *>
                thread["id"] as String to number(thread["date"])
            }
            assertEquals(case["name"] as String, dated(case["expect"]), newestThreadsFirst(dated(case["threads"]), { it.first }, { it.second }))
        }
    }
}
