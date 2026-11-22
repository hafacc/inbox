package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.contacts.Contact
import cc.hafa.inbox.core.contacts.suggestContacts
import cc.hafa.inbox.core.mail.MessageBody
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.parseDateHeader
import cc.hafa.inbox.core.send.BlockedOn
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.SendRoute
import cc.hafa.inbox.core.send.SENT_KEEP_MS
import cc.hafa.inbox.core.send.blockedText
import cc.hafa.inbox.core.send.canSendAgain
import cc.hafa.inbox.core.send.inFlight
import cc.hafa.inbox.core.send.readyToDelete
import cc.hafa.inbox.core.send.failureText
import cc.hafa.inbox.core.send.retryingDetail
import cc.hafa.inbox.core.send.retryingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ComposeHelpersTest {
    @Test
    fun `image references use the name without its extension as alt`() {
        assertEquals("![photo 1](cid:abc@inbox)", imageMarkdown("photo [1].jpg", "abc@inbox"))
    }

    @Test
    fun `contacts match names and addresses, saved before other, skipping added`() {
        val contacts = listOf(
            Contact("otherContacts/1", "", listOf("sam@proton.example.net"), other = true),
            Contact("people/1", "Sam Okafor", listOf("sam@okafor.example.net"), other = false),
            Contact("people/2", "Ana Samuels", listOf("ana@example.com"), other = false),
            Contact("people/3", "Bob", listOf("bob@example.com"), other = false),
        )
        assertEquals(
            listOf("sam@okafor.example.net", "ana@example.com", "sam@proton.example.net"),
            suggestContacts(contacts, "sam").map { it.email },
        )
        assertEquals(listOf("sam@proton.example.net"), suggestContacts(contacts, "sam", exclude = setOf("sam@okafor.example.net", "ana@example.com")).map { it.email })
        assertTrue(suggestContacts(contacts, " ").isEmpty())
    }

    private fun message(from: Person, to: List<Person>, cc: List<Person> = emptyList()) = MessageView(
        id = "m",
        from = from,
        to = to,
        date = 0,
        snippet = "",
        unread = false,
        body = MessageBody(null, "Hello\nthere", emptyList(), emptyList()),
        cc = cc,
        subject = "Lease",
        messageIdHeader = "<m2@x>",
        references = "<m1@x>",
    )

    @Test
    fun `reply all answers from the address it was sent to and threads it`() {
        val me = Person("Jordan", "jordan@example.com")
        val hafa = Person("Jordan", "test@hafa.cc")
        val sam = Person("Sam", "sam@proton.example.net")
        val cat = Person("Cat", "cat@example.com")
        val draft = replyDraft("l", "t", message(sam, listOf(hafa), listOf(cat)), listOf(me, hafa), me, replyAll = true, signature = "E")
        assertEquals(hafa, draft.from)
        assertEquals(listOf(sam), draft.to)
        assertEquals(listOf(cat), draft.cc)
        assertEquals("Re: Lease", draft.subject)
        assertEquals("<m2@x>", draft.inReplyTo)
        assertEquals(listOf("<m1@x>", "<m2@x>"), draft.references)
        assertEquals("t", draft.threadId)
        assertEquals(QuotedMessage(sam, 0, "Hello\nthere", null), draft.quote)
    }

    @Test
    fun `forward quotes the original under a header and stays in its thread`() {
        val draft = forwardDraft("l", "t", message(Person("Sam", "sam@proton.example.net"), emptyList()), Person("", "me@x.example.com"), null, ZoneId.of("UTC"))
        assertEquals("Fwd: Lease", draft.subject)
        assertTrue(draft.markdown.contains("---------- Forwarded message ---------"))
        assertTrue(draft.markdown.endsWith("> Hello\n> there"))
        assertEquals("t", draft.threadId)
        assertEquals("<m2@x>", draft.inReplyTo)
        assertEquals(listOf("<m1@x>", "<m2@x>"), draft.references)
    }

    @Test
    fun `an answer to a message without a Message-ID has no threading headers`() {
        val sam = Person("Sam", "sam@proton.example.net")
        val bare = message(sam, emptyList()).copy(messageIdHeader = " ")
        val draft = replyDraft("l", "t", bare, emptyList(), Person("", "me@x.example.com"), replyAll = false, signature = null)
        assertEquals("Re: Lease", draft.subject)
        assertNull(draft.inReplyTo)
        assertEquals(emptyList<String>(), draft.references)
    }

    @Test
    fun `failed sends read as the reason plus advice`() {
        assertEquals("Gmail said no. It kept failing, so it stopped trying.", failureText("unavailable", "Gmail said no."))
        assertEquals("Address bounced", failureText("rejected", "Address bounced"))
        assertEquals("It didn't send.", failureText(null, null))
        assertFalse(canSendAgain("draft-missing"))
        assertTrue(canSendAgain("unavailable"))
    }

    @Test
    fun `blocked sends say what they wait for and can be sent again`() {
        assertEquals("Waiting for you to sign in again", blockedText(BlockedOn.SIGN_IN))
        assertEquals("Sending from @hafa.cc isn't set up yet", blockedText(BlockedOn.RESEND))
        assertEquals("Waiting until it can be sent", blockedText(null))
        val blocked = OutboxDoc("o", "d", "test@hafa.cc", OutboxStatus.BLOCKED, 0, null, null, blockedOn = BlockedOn.RESEND)
        assertTrue(blocked.canSendAgain)
        assertFalse(blocked.inFlight)
        assertEquals(OutboxStatus.BLOCKED, OutboxStatus.of("blocked"))
        assertFalse(blocked.copy(status = OutboxStatus.FAILED, errorCode = "not-owned").canSendAgain)
        assertTrue(blocked.copy(status = OutboxStatus.FAILED, errorCode = "unavailable").canSendAgain)
        assertFalse(blocked.copy(status = OutboxStatus.RETRYING).canSendAgain)
    }

    @Test
    fun `the Date header is read, comments and all`() {
        assertEquals(1793638200000, parseDateHeader("Mon, 2 Nov 2026 11:50:00 -0500 (EST)"))
        assertEquals(null, parseDateHeader("yesterday"))
    }

    @Test
    fun `retrying sends say when they try next`() {
        val zone = ZoneId.of("America/New_York")
        val next = LocalDateTime.parse("2026-11-04T14:05:00").atZone(zone).toInstant().toEpochMilli()
        assertEquals("Not sent yet · trying again at 14:05", retryingText(next, zone))
        assertEquals("Couldn't reach Gmail.", retryingDetail("network", "Couldn't reach Gmail."))
        assertEquals("Sending limit reached.", retryingDetail("rate-limited", null))
        assertEquals(null, retryingDetail("network", " "))
    }

    @Test
    fun `inline images the body dropped are left out of the message`() {
        val draft = DraftContent(
            localId = "l",
            from = Person("", "a@x.example.com"),
            markdown = "![a](cid:keep)",
            files = listOf(
                DraftFile("1", "a.png", "image/png", 1, "keep"),
                DraftFile("2", "b.png", "image/png", 1, "gone"),
                DraftFile("3", "c.pdf", "application/pdf", 1),
            ),
        )
        val input = draft.composeInput("b") { ByteArray(1) }
        assertEquals(listOf("keep"), input.inlineImages.map { it.contentId })
        assertEquals(listOf("c.pdf"), input.attachments.map { it.filename })
    }

    @Test
    fun `sent docs go two minutes after sending, Resend ones once filed`() {
        val sent = OutboxDoc("o", "d", "a@x.example.com", OutboxStatus.SENT, 0, null, null, sentAt = 1_000, via = SendRoute.GMAIL)
        assertFalse(readyToDelete(sent, 1_000 + SENT_KEEP_MS - 1))
        assertTrue(readyToDelete(sent, 1_000 + SENT_KEEP_MS))
        assertFalse(readyToDelete(sent.copy(via = SendRoute.RESEND, filed = false), 1_000 + SENT_KEEP_MS))
        assertTrue(readyToDelete(sent.copy(via = SendRoute.RESEND, filed = true), 1_000 + SENT_KEEP_MS))
        assertFalse(readyToDelete(sent.copy(status = OutboxStatus.WAITING), 1_000 + SENT_KEEP_MS))
    }

    @Test
    fun `says which address can't be sent to`() {
        assertNull(addressProblem(listOf(Person("Ana", " ana@example.com "))))
        assertEquals(
            "ana@ isn't a valid email address. Fix or remove it.",
            addressProblem(listOf(Person("", "bo@example.com"), Person("Ana", "ana@"), Person("", ""))),
        )
        assertEquals("One of the addresses is empty. Remove it and try again.", addressProblem(listOf(Person("", " "))))
        assertEquals("a,b@c isn't a valid email address. Fix or remove it.", addressProblem(InvalidAddressError("a,b@c")))
        assertNull(addressProblem(IllegalStateException("other")))
    }
}
