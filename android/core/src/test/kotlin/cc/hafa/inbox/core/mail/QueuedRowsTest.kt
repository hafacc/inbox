package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Runs `shared/testdata/queued-row-fixtures.json`, so queued changes show over a fresh row as on web. */
class QueuedRowsTest {
    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun row(value: Any?): ThreadSummary {
        val fields = value as Map<*, *>
        return ThreadSummary(
            id = fields["id"] as String,
            historyId = "1",
            subject = "",
            snippet = "",
            senders = emptyList(),
            messageCount = 1,
            lastDate = 0,
            labelIds = strings(fields["labelIds"]),
            unread = fields["unread"] as Boolean,
        )
    }

    @Test
    fun fixtures() {
        for (case in Fixtures.cases(Fixtures.load("queued-row-fixtures.json"), "cases")) {
            val queue = (case["queue"] as List<*>).map { entry ->
                val change = entry as Map<*, *>
                LabelChange(
                    (change["seq"] as Number).toLong(),
                    change["threadId"] as String,
                    strings(change["add"]),
                    strings(change["remove"]),
                )
            }
            assertEquals(case["name"] as String, row(case["expect"]), row(case["row"]).withQueued(queue))
        }
    }

    @Test
    fun unmovedRowComesBackAsItWas() {
        val summary = row(mapOf("id" to "t1", "labelIds" to listOf("INBOX"), "unread" to false))
        assertSame(summary, summary.withQueued(listOf(LabelChange(1, "t1", listOf("INBOX"), emptyList()))))
    }
}
