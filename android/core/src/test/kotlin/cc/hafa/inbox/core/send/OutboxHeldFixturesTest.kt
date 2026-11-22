package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/outbox-held-fixtures.json`, so sends held offline list in the Outbox as on web. */
class OutboxHeldFixturesTest {
    @Test
    fun cases() {
        for (case in Fixtures.cases(Fixtures.outboxHeld, "cases")) {
            val sends = (case["sends"] as List<*>).map {
                val send = it as Map<*, *>
                QueuedSend(send["localId"] as String, send["outboxId"] as String?, send["docWritten"] as Boolean)
            }
            val docIds = (case["docIds"] as List<*>).map { it as String }
            assertEquals(case["name"] as String, case["expect"], heldSendIds(sends, docIds))
        }
    }

    @Test
    fun status() {
        assertEquals(Fixtures.outboxHeld["status"], HELD_SEND_STATUS)
    }
}
