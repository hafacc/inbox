package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/outbox-draft-fixtures.json`, so scheduled sends leave Drafts as on web. */
class OutboxDraftsFixturesTest {
    private fun outbox(case: Map<String, Any?>): OutboxDrafts {
        val drafts = (case["drafts"] as List<*>).map {
            val draft = it as Map<*, *>
            GmailDraftRef(draft["draftId"] as String, draft["messageId"] as String, draft["threadId"] as String)
        }
        val docs = (case["docs"] as List<*>).mapIndexed { index, it ->
            val doc = it as Map<*, *>
            OutboxDoc("o$index", doc["draftId"] as String, "me@example.com", OutboxStatus.of(doc["status"] as String), 0, null, doc["threadId"] as String?)
        }
        val sending = (case["sending"] as List<*>?).orEmpty().map {
            val send = it as Map<*, *>
            SendingDraft(send["draftId"] as String?, send["messageId"] as String?, send["threadId"] as String?)
        }
        val listedFor = (case["listedFor"] as List<*>?).orEmpty().map { it as String }.toSet()
        return outboxDrafts(drafts, docs, sending, listedFor)
    }

    @Test
    fun cases() {
        for (case in Fixtures.cases(Fixtures.outboxDrafts, "cases")) {
            val expect = case["expect"] as Map<*, *>
            val outbox = outbox(case)
            val name = case["name"] as String
            assertEquals(name, expect["messageIds"], outbox.messageIds.sorted())
            assertEquals(name, expect["threadIds"], outbox.threadIds.sorted())
            assertEquals(name, (expect["count"] as Number).toLong(), draftThreadCount((case["labelThreads"] as Number).toLong(), outbox))
        }
    }

    @Test
    fun listed() {
        val cases = Fixtures.cases(Fixtures.outboxDrafts, "listed")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val shown = onlyInOutbox(case["threadId"] as String, (case["sentOrReceived"] as Number).toInt(), outbox(case))
            assertEquals(case["name"] as String, case["expect"], shown)
        }
    }
}
