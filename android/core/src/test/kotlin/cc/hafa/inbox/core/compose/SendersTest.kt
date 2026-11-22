package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.gmail.GmailSendAs
import cc.hafa.inbox.core.mail.Person
import org.junit.Assert.assertEquals
import org.junit.Test

/** The From list matches web's `fromOptions` (web/src/lib/compose/send-as.spec.ts). */
class SendersTest {
    private val sendAs = listOf(
        GmailSendAs("old@example.com", "Old", verificationStatus = "pending"),
        GmailSendAs("work@example.com", "Jordan at work", verificationStatus = "accepted"),
        GmailSendAs("jordan.lee@example.com", "Jordan Lee", isPrimary = true, isDefault = true),
    )

    @Test
    fun `lists hafa cc once active, then Gmail, then verified aliases`() {
        assertEquals(
            listOf(
                Person("Jordan Lee", "test@hafa.cc"),
                Person("Jordan Lee", "jordan.lee@example.com"),
                Person("Jordan at work", "work@example.com"),
            ),
            senderList(sendAs, "jordan.lee@example.com", "test@hafa.cc"),
        )
    }

    @Test
    fun `leaves hafa cc out until it is active, and falls back to the account address`() {
        assertEquals(listOf("jordan.lee@example.com", "work@example.com"), senderList(sendAs, "jordan.lee@example.com", null).map { it.email })
        assertEquals(listOf(Person("", "me@gmail.example.com")), senderList(emptyList(), "me@gmail.example.com", null))
    }
}
