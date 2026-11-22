package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.gmail.GmailPartBody
import cc.hafa.inbox.core.gmail.GmailThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ThreadDetailTest {
    private fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun `an opened thread drops drafts and opens at its first unread message`() {
        val detail = threadDetail(
            GmailThread(
                id = "t",
                historyId = "7",
                messages = listOf(
                    GmailMessage(
                        id = "m1",
                        threadId = "t",
                        labelIds = listOf("UNREAD"),
                        internalDate = "1000",
                        snippet = "Draft attached &amp; signed",
                        payload = GmailMessagePart(
                            mimeType = "text/plain",
                            headers = listOf(
                                GmailHeader("From", "Sam Okafor <sam@proton.example.net>"),
                                GmailHeader("To", "test@hafa.cc"),
                                GmailHeader("Subject", "Lease paperwork"),
                            ),
                            body = GmailPartBody(size = 5, data = encode("Hello")),
                        ),
                    ),
                    GmailMessage(
                        id = "m2",
                        threadId = "t",
                        labelIds = listOf("SENT"),
                        internalDate = "2000",
                        payload = GmailMessagePart(
                            mimeType = "text/html",
                            headers = listOf(GmailHeader("From", "test@hafa.cc")),
                            body = GmailPartBody(size = 9, data = encode("<p>Thanks</p>")),
                        ),
                    ),
                    GmailMessage(id = "m3", threadId = "t", labelIds = listOf("DRAFT"), internalDate = "3000"),
                ),
            ),
        )
        assertEquals("Lease paperwork", detail.subject)
        assertEquals(listOf("m1", "m2"), detail.messages.map { it.id })
        val first = detail.messages[0]
        assertEquals(Person("Sam Okafor", "sam@proton.example.net"), first.from)
        assertEquals(listOf(Person("", "test@hafa.cc")), first.to)
        assertEquals("Draft attached & signed", first.snippet)
        assertTrue(first.unread)
        assertEquals("Hello", first.body.text)
        assertNull(first.body.html)
        assertEquals("<p>Thanks</p>", detail.messages[1].body.html)
        assertEquals(ThreadOpenState(listOf("m1", "m2"), "m1"), threadOpenState(detail.messages))
    }

    @Test
    fun `history's labels set a saved thread's unread flags and drop its deleted messages`() {
        fun message(id: String, threadId: String, labels: List<String>) =
            GmailMessage(id = id, threadId = threadId, labelIds = labels, internalDate = "1000")
        val own = threadDetail(
            GmailThread(
                id = "t",
                historyId = "7",
                messages = listOf(
                    message("m1", "t", listOf("INBOX", "UNREAD")),
                    message("m2", "t", listOf("INBOX")),
                    message("m3", "t", listOf("INBOX")),
                ),
            ),
        )
        val other = threadDetail(GmailThread(id = "u", historyId = "5", messages = listOf(message("n1", "u", listOf("UNREAD")))))
        val detail = own.copy(messages = own.messages + other.messages)
        val next = detail.relabeled("t", mapOf("m1" to listOf("INBOX"), "m2" to listOf("INBOX", "UNREAD")))
        assertEquals(listOf("m1" to false, "m2" to true, "n1" to true), next.messages.map { it.id to it.unread })
        val same = detail.relabeled("t", mapOf("m1" to listOf("UNREAD"), "m2" to emptyList(), "m3" to listOf("STARRED")))
        assertTrue(same === detail)
    }
}
