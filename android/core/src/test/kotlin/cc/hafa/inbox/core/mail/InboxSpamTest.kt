package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/** A thread in spam or trash never lists in the inbox, though one of its messages is still there. */
class InboxSpamTest {
    private fun thread(id: String, labels: List<String>) = ThreadSummary(
        id = id,
        historyId = "1",
        subject = "Subject $id",
        snippet = "",
        senders = listOf(Person("Sender", "sender@example.com")),
        messageCount = 2,
        lastDate = 1_800_000_000_000L,
        labelIds = labels,
        unread = false,
    )

    @Test
    fun spamAndTrashStayOutOfTheInboxAndItsBundles() {
        val threads = listOf(
            thread("plain", listOf("INBOX")),
            thread("spam", listOf("INBOX", "SPAM")),
            thread("trash", listOf("INBOX", "TRASH", "Label_1")),
            thread("bundled", listOf("INBOX", "Label_1")),
        )
        val model = MailModel(
            email = "me@example.com",
            now = 1_800_000_000_000L,
            summaries = threads.associateBy { it.id },
            lists = mapOf(
                View.INBOX.key to LoadedList(ids = listOf("plain", "spam"), loaded = true),
                bundleKey("Label_1") to LoadedList(ids = listOf("trash", "bundled"), loaded = true),
            ),
        )
        assertEquals(listOf("plain"), model.inboxRows.map { it.thread.id })
    }
}
