package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-open-fixtures.json`, so threads open the same way as on web. */
class ThreadOpenFixturesTest {
    @Test
    fun open() {
        for (case in Fixtures.cases(Fixtures.threadOpen, "open")) {
            val messages = (case["messages"] as List<*>).map { message ->
                message as Map<*, *>
                val unread = message["unread"] as Boolean
                GmailMessage(id = message["id"] as String, threadId = "t", labelIds = if (unread) listOf("UNREAD") else emptyList())
            }
            val state = threadOpenState(threadDetail(GmailThread(id = "t", messages = messages)).messages)
            val expect = case["expect"] as Map<*, *>
            assertEquals(case["name"] as String, expect["expanded"], state.expanded)
            assertEquals(case["name"] as String, expect["scrollTo"], state.scrollTo)
        }
    }
}
