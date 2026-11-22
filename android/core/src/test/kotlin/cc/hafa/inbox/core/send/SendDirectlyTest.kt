package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.gmail.GmailException
import cc.hafa.inbox.core.gmail.GmailHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** [sendDirectly] in the same steps as web's `sendDirectly` (web/src/lib/compose/direct-send.spec.ts). */
class SendDirectlyTest {
    private class Fake : DirectSendDeps {
        val calls = mutableListOf<String>()
        val claims = mutableListOf<DeviceClaim>()
        val refusals = mutableListOf<String>()
        var clock = 1_000L
        var claimTakes = 0L
        var claimAnswer: () -> Boolean = { true }
        var readError: Exception? = null
        var sendError: Exception? = null
        var sendTakes = 0L
        var fromHeader = "Ana <Ana@gmail.example.com>"

        override suspend fun readDraft(draftId: String): Pair<String, List<GmailHeader>> {
            readError?.let { throw it }
            calls += "read $draftId"
            return "m-1" to listOf(GmailHeader("From", fromHeader), GmailHeader("Message-ID", "<a@mail.gmail.com>"))
        }

        override suspend fun claim(claim: DeviceClaim): Boolean {
            calls += "claim"
            claims += claim
            clock += claimTakes
            return claimAnswer()
        }

        override suspend fun sendDraft(draftId: String): Pair<String, String> {
            calls += "send $draftId"
            delay(sendTakes)
            sendError?.let { throw it }
            return "m-1" to "t-1"
        }

        override fun recordSent(messageId: String, threadId: String, deviceClaim: String) {
            calls += "sent $messageId $threadId $deviceClaim"
        }

        override fun recordRefused(message: String, deviceClaim: String) {
            calls += "refused $deviceClaim"
            refusals += message
        }

        override fun newClaimId(): String = "claim-0123456789abcdef"

        override fun now(): Long = clock
    }

    @Test
    fun sendsOnceAndRecordsIt() = runTest {
        val fake = Fake()
        assertEquals(DirectSendResult.SENT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("read d-1", "claim", "send d-1", "sent m-1 t-1 claim-0123456789abcdef"), fake.calls)
        assertEquals(listOf(DeviceClaim("m-1", "<a@mail.gmail.com>", "claim-0123456789abcdef")), fake.claims)
    }

    @Test
    fun leavesAnUnreadableOrForeignDraftUnclaimed() = runTest {
        val fake = Fake()
        fake.readError = GmailException(404, "gone")
        assertEquals(DirectSendResult.SERVER, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        fake.readError = null
        fake.fromHeader = "bob@example.com"
        assertEquals(DirectSendResult.SERVER, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("read d-1"), fake.calls)
    }

    @Test
    fun doesNotSendWhenTheClaimIsRefusedOrFails() = runTest {
        val fake = Fake()
        fake.claimAnswer = { false }
        assertEquals(DirectSendResult.SERVER, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        fake.claimAnswer = { throw IOException("offline") }
        assertEquals(DirectSendResult.SERVER, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("read d-1", "claim", "read d-1", "claim"), fake.calls)
    }

    @Test
    fun leavesASlowClaimToTheServer() = runTest {
        val fake = Fake()
        fake.claimTakes = DIRECT_HANDOVER_MAX_MS + 1
        assertEquals(DirectSendResult.LEFT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("read d-1", "claim"), fake.calls)
    }

    @Test
    fun recordsACleanRefusalAndLeavesAnythingElse() = runTest {
        val fake = Fake()
        fake.sendError = GmailException(400, "Invalid To header", "invalidArgument")
        assertEquals(DirectSendResult.REFUSED, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("Invalid To header"), fake.refusals)
        assertEquals("refused claim-0123456789abcdef", fake.calls.last())
        for (error in listOf(GmailException(503, "busy"), GmailException(429, "slow down"), GmailException(404, "gone"), IOException("reset"))) {
            fake.calls.clear()
            fake.sendError = error
            assertEquals(DirectSendResult.LEFT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
            assertEquals(listOf("read d-1", "claim", "send d-1"), fake.calls)
        }
    }

    @Test
    fun recordsASendGmailConfirmsAfterTheTimeoutWithoutSendingAgain() = runTest {
        val fake = Fake()
        fake.sendTakes = DIRECT_SEND_TIMEOUT_MS + 1
        assertEquals(DirectSendResult.LEFT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        assertEquals(listOf("read d-1", "claim", "send d-1"), fake.calls)
        delay(DIRECT_SEND_TIMEOUT_MS)
        assertEquals(listOf("read d-1", "claim", "send d-1", "sent m-1 t-1 claim-0123456789abcdef"), fake.calls)
    }

    @Test
    fun recordsACleanRefusalAfterTheTimeoutAndLeavesAnyOtherLateFailure() = runTest {
        val fake = Fake()
        fake.sendTakes = DIRECT_SEND_TIMEOUT_MS + 1
        fake.sendError = GmailException(400, "Invalid To header", "invalidArgument")
        assertEquals(DirectSendResult.LEFT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        delay(DIRECT_SEND_TIMEOUT_MS)
        assertEquals("refused claim-0123456789abcdef", fake.calls.last())
        fake.calls.clear()
        fake.sendError = GmailException(503, "busy")
        assertEquals(DirectSendResult.LEFT, sendDirectly(fake, "d-1", "ana@gmail.example.com", this))
        delay(DIRECT_SEND_TIMEOUT_MS)
        assertEquals(listOf("read d-1", "claim", "send d-1"), fake.calls)
    }
}
