package cc.hafa.inbox.core.gmail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftHistoryTest {
    private fun message(labels: List<String>?) = GmailMessage("m1", "t1", labelIds = labels)

    @Test
    fun `a deleted message without labels may have been a draft`() {
        assertTrue(touchesDrafts(listOf(GmailHistoryRecord("1", messagesDeleted = listOf(GmailMessageAdded(message(null)))))))
    }

    @Test
    fun `draft messages and the draft label count, other changes don't`() {
        assertTrue(touchesDrafts(listOf(GmailHistoryRecord("1", messagesAdded = listOf(GmailMessageAdded(message(listOf("DRAFT"))))))))
        assertTrue(touchesDrafts(listOf(GmailHistoryRecord("1", labelsRemoved = listOf(GmailLabelsAdded(message(listOf("SENT")), listOf("DRAFT")))))))
        assertFalse(touchesDrafts(listOf(GmailHistoryRecord("1", messagesAdded = listOf(GmailMessageAdded(message(listOf("INBOX"))))))))
        assertFalse(touchesDrafts(listOf(GmailHistoryRecord("1", messagesDeleted = listOf(GmailMessageAdded(message(listOf("INBOX"))))))))
    }
}
