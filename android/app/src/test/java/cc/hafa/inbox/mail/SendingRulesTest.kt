package cc.hafa.inbox.mail

import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.PendingSend
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.data.StoredDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendingRulesTest {
    private val pending = PendingSend("s1", 1_000, null)

    private fun stored(pendingSend: PendingSend?, outboxId: String? = null, deleted: Boolean = false) = StoredDraft(
        DraftContent(localId = "d1", from = Person("", "me@example.com"), outboxId = outboxId),
        dirty = false,
        deleted = deleted,
        pendingSend = pendingSend,
        updatedAt = 0,
    )

    private fun doc(id: String, status: OutboxStatus) = OutboxDoc(id, "draft-$id", "me@example.com", status, 0, null, null)

    @Test
    fun `a send stands only while the draft still asks for it`() {
        assertTrue(sendStands(stored(pending), pending, rewrite = false, waiting = false))
        // Undone: the draft no longer waits on the send.
        assertFalse(sendStands(stored(null), pending, rewrite = false, waiting = false))
        // Sent again with another time: this send's docs would be stale.
        assertFalse(sendStands(stored(pending.copy(sendAt = 2_000)), pending, rewrite = false, waiting = false))
        // Discarded, or gone.
        assertFalse(sendStands(stored(pending, deleted = true), pending, rewrite = false, waiting = false))
        assertFalse(sendStands(null, pending, rewrite = false, waiting = false))
    }

    @Test
    fun `an edited scheduled send stands while the draft is tied to its waiting doc`() {
        assertTrue(sendStands(stored(null, outboxId = "s1"), pending, rewrite = true, waiting = true))
        // Canceled: the draft is let go of the doc, or the doc is gone.
        assertFalse(sendStands(stored(null, outboxId = null), pending, rewrite = true, waiting = true))
        assertFalse(sendStands(stored(null, outboxId = "s1"), pending, rewrite = true, waiting = false))
    }

    @Test
    fun `a send is finished only when its Bcc copies have gone too`() {
        val main = doc("s1", OutboxStatus.SENT)
        assertFalse(sendFinished(listOf(main, doc("s1-bcc-1", OutboxStatus.WAITING)), "s1"))
        assertFalse(sendFinished(listOf(main, doc("s1-bcc-1", OutboxStatus.FAILED)), "s1"))
        assertTrue(sendFinished(listOf(main, doc("s1-bcc-1", OutboxStatus.SENT), doc("s2", OutboxStatus.WAITING)), "s1"))
        // The main doc deleted after its two minutes: the copies decide.
        assertTrue(sendFinished(listOf(doc("s1-bcc-2", OutboxStatus.SENT)), "s1"))
        assertFalse(sendFinished(emptyList(), "s1"))
    }
}
