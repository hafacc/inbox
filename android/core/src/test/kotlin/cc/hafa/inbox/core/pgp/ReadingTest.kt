package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.gmail.GmailRawMessage
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.search.searchPlan
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning decrypted mail into what the reader shows, and searching inside it. */
class ReadingTest {
    private val fixtures = Fixtures.load("pgp-fixtures.json")
    private val keys = FixtureMessages.keys

    private val outer = GmailMessage(
        id = "m1",
        threadId = "t1",
        payload = GmailMessagePart(
            mimeType = "multipart/encrypted",
            headers = listOf(
                GmailHeader("From", "Bob Test <bob@example.com>"),
                GmailHeader("To", "Alice Test <test@hafa.cc>"),
                GmailHeader("Subject", "..."),
                GmailHeader("Message-ID", "<m1@example.com>"),
            ),
        ),
    )

    @Test
    fun showsTheProtectedHeadersAndInnerBody() {
        val case = Fixtures.cases(fixtures, "decrypt").first()
        val decrypted = decryptMessage(
            FixtureMessages.message(case),
            listOf(keys.getValue("alice").privateKey),
            listOf(keys.getValue("bob").publicKey),
        )
        assertTrue(isEncryptedMessage(outer))
        val view = decryptedView(outer, decrypted)
        assertEquals("Re: Plans for Thursday — café?", view.subject)
        assertEquals(Person("Bob Test", "bob@example.com"), view.from)
        assertEquals("Noon works. ☕", view.body.text)
        assertEquals("Noon works. ☕", view.snippet)
        assertEquals("<m1@example.com>", view.messageIdHeader)
        assertEquals(MessageLock(LockState.OPEN, "valid"), view.lock)
        assertTrue(matchesDecrypted(searchPlan("noon"), view))
        assertTrue(matchesDecrypted(searchPlan("NOON café"), view))
        assertFalse(matchesDecrypted(searchPlan("noon dinner"), view))
        assertFalse(matchesDecrypted(searchPlan("noon -café"), view))
        assertTrue(matchesDecrypted(searchPlan("noon -dinner"), view))
    }

    @Test
    fun anUnvouchedInnerSenderShowsTheOuterOneAndIsFlagged() {
        val case = Fixtures.cases(fixtures, "decrypt").first { it["name"] == "unsigned: inner From is not trusted over the outer one" }
        val view = decryptedRawView(
            GmailRawMessage(id = "m2", threadId = "t2"),
            FixtureMessages.message(case),
            listOf(keys.getValue("alice").privateKey),
            listOf(keys.getValue("bob").publicKey),
        )
        assertEquals(Person("Mallory", "mallory@example.net"), view.from)
        assertEquals(MessageLock(LockState.OPEN, "unsigned", listOf("from")), view.lock)
    }

    @Test
    fun aListWrappedPgpMimeMessageIsEncrypted() {
        val wrapped = outer.copy(
            payload = GmailMessagePart(
                mimeType = "multipart/mixed",
                parts = listOf(GmailMessagePart(mimeType = "multipart/encrypted"), GmailMessagePart(mimeType = "text/plain")),
            ),
        )
        assertTrue(isEncryptedMessage(wrapped))
    }

    @Test
    fun attachmentsComeOutWhole() {
        val inner = parseMime(
            "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nSee attached.\r\n--b\r\n" +
                "Content-Type: application/pdf; name=\"x.pdf\"\r\nContent-Disposition: attachment; filename*=UTF-8''caf%C3%A9.pdf\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\nQUJD\r\nRA==\r\n--b--\r\n",
        )
        val view = decryptedView(outer, DecryptedMessage(EncryptionKind.PGP_MIME, inner, null, emptyList(), SignatureResult(SignatureStatus.UNSIGNED, null)))
        val attachment = view.body.attachments.single()
        assertEquals("café.pdf", attachment.filename)
        assertEquals("ABCD", String(cc.hafa.inbox.core.mail.decodeBase64Url(attachment.data!!)))
        assertEquals("See attached.", view.body.text)
        assertEquals("...", lockedView(outer, LockState.WRONG_KEY).subject)
        assertNull(lockedView(outer, LockState.WRONG_KEY).body.text)
    }

    @Test
    fun encryptedSearchKeepsOperatorsAndDropsWords() {
        assertEquals("from:bob $ENCRYPTED_MAIL_QUERY", encryptedSearchQuery(searchPlan("from:bob lunch")))
        assertEquals(ENCRYPTED_MAIL_QUERY, encryptedSearchQuery(searchPlan("lunch plans")))
        assertNull(encryptedSearchQuery(searchPlan("from:bob")))
    }

    @Test
    fun encryptedSearchAsksGmailForPinnedAndDoneInItsOwnWords() {
        assertEquals("is:starred -in:inbox $ENCRYPTED_MAIL_QUERY", encryptedSearchQuery(searchPlan("is:pinned is:done lease")))
    }

    @Test
    fun `warns only when inner address headers were set aside`() {
        assertNull(mismatchWarning(MessageLock(LockState.OPEN, "valid")))
        assertEquals(
            "The sender inside this encrypted message doesn't match its signature; showing the outside one.",
            mismatchWarning(MessageLock(LockState.OPEN, "unknown-key", listOf("from"))),
        )
    }
}
