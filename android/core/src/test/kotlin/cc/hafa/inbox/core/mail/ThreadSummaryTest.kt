package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.gmail.GmailThread
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadSummaryTest {
    private fun message(id: String, from: String, date: Long, labelIds: List<String>, snippet: String = "snippet $id") =
        GmailMessage(
            id = id,
            threadId = "t",
            labelIds = labelIds,
            internalDate = date.toString(),
            snippet = snippet,
            historyId = id,
            payload = GmailMessagePart(
                mimeType = "text/plain",
                headers = listOf(GmailHeader("From", from), GmailHeader("Subject", "  Dinner Saturday?  ")),
            ),
        )

    @Test
    fun `summarizes senders, count, date and labels, leaving drafts out`() {
        val summary = summarizeThread(
            GmailThread(
                id = "t",
                historyId = "900",
                messages = listOf(
                    message("1", "Maya Chen <maya@x.example.com>", 1000, listOf("INBOX")),
                    message("2", "test@hafa.cc", 2000, listOf("SENT")),
                    message("3", "maya@x.example.com", 3000, listOf("INBOX", "UNREAD"), "Works for me &#8212; 19:30 at Lupa?"),
                    message("4", "test@hafa.cc", 4000, listOf("DRAFT")),
                ),
            ),
        )
        assertEquals(
            ThreadSummary(
                id = "t",
                historyId = "900",
                subject = "Dinner Saturday?",
                snippet = "Works for me — 19:30 at Lupa?",
                senders = listOf(Person("Maya Chen", "maya@x.example.com"), Person("", "test@hafa.cc")),
                messageCount = 3,
                lastDate = 3000,
                labelIds = listOf("DRAFT", "INBOX", "SENT", "UNREAD"),
                unread = true,
                sentDate = 2000,
                firstDate = 1000,
            ),
            summary,
        )
    }

    @Test
    fun `a reply of the user's leaves the thread at its latest received message`() {
        val summary = summarizeThread(
            GmailThread(
                id = "t",
                messages = listOf(
                    message("1", "maya@x.example.com", 1000, listOf("INBOX")),
                    message("2", "maya@x.example.com", 2000, listOf("INBOX")),
                    message("3", "test@hafa.cc", 9000, listOf("SENT")),
                ),
            ),
        )
        assertEquals(2000L, summary.lastDate)
        assertEquals(9000L, summary.sentDate)
        assertEquals(9000L, summary.sentDated().lastDate)
    }

    @Test
    fun `the newest received message dates the thread whatever Gmail's order`() {
        val summary = summarizeThread(
            GmailThread(
                id = "t",
                messages = listOf(message("2", "maya@x.example.com", 5000, listOf("INBOX")), message("1", "maya@x.example.com", 1000, listOf("INBOX"))),
            ),
        )
        assertEquals(5000L, summary.lastDate)
    }

    @Test
    fun `keeps message ids, and what a thread with the hidden subject answers`() {
        fun headed(id: String, subject: String, vararg headers: GmailHeader) = message(id, "maya@x.example.com", 1000, listOf("INBOX")).let {
            it.copy(payload = it.payload?.copy(headers = listOf(GmailHeader("Subject", subject), GmailHeader("Message-Id", "<$id@x>")) + headers))
        }
        val replies = listOf(GmailHeader("In-Reply-To", "<a@x>"), GmailHeader("References", "<root@x>\r\n <a@x>"))
        val hidden = summarizeThread(GmailThread(id = "t", messages = listOf(headed("b", "...", *replies.toTypedArray()))))
        assertEquals(listOf("<b@x>"), hidden.messageIds)
        assertEquals(listOf("<a@x>", "<root@x>"), hidden.answers)
        val plain = summarizeThread(GmailThread(id = "t", messages = listOf(headed("c", "Re: Lease", *replies.toTypedArray()))))
        assertEquals(listOf("<c@x>"), plain.messageIds)
        assertEquals(emptyList<String>(), plain.answers)
    }

    @Test
    fun `names one sender in full and several by first name, the account as me`() {
        val maya = Person("Maya Chen", "maya@x.example.com")
        val self = Person("Jordan Lee", "test@hafa.cc")
        val bot = Person("", "noreply@github.com")
        assertEquals("Maya Chen", sendersLabel(listOf(maya), "test@hafa.cc"))
        assertEquals("me", sendersLabel(listOf(self), "Test@hafa.cc"))
        assertEquals("Maya, me", sendersLabel(listOf(maya, self), "test@hafa.cc"))
        assertEquals("noreply, Maya, me …", sendersLabel(listOf(bot, maya, self, bot), "test@hafa.cc"))
        assertEquals("(no sender)", sendersLabel(emptyList(), "test@hafa.cc"))
    }

    @Test
    fun `a saved thread with a new message read alone has the row Gmail's whole thread gives`() {
        val first = message("1", "Maya Chen <maya@x.example.com>", 1000, listOf("INBOX"))
        val mine = message("2", "test@hafa.cc", 2000, listOf("SENT"))
        val fresh = message("3", "maya@x.example.com", 3000, listOf("INBOX", "UNREAD"), "Works for me")
        val saved = threadDetail(GmailThread(id = "t", historyId = "2", messages = listOf(first, mine)))
        val before = summarizeThread(GmailThread(id = "t", historyId = "2", messages = listOf(first, mine)))
        val body = withAddedMessages(saved, listOf(fresh))
        assertEquals(listOf("1", "2", "3"), body.messages.map { it.id })
        assertEquals(body, withAddedMessages(body, listOf(fresh)))
        val labels = mapOf("1" to listOf("INBOX"), "2" to listOf("SENT"), "3" to listOf("INBOX", "UNREAD"))
        assertEquals(
            summarizeThread(GmailThread(id = "t", historyId = "3", messages = listOf(first, mine, fresh))),
            savedSummary(body, labels, before, "3"),
        )
    }
}
