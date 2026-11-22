package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.filters.GmailFilterAction
import cc.hafa.inbox.core.filters.GmailFilterCriteria
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.gmail.GmailLabelColor
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the shared fixtures (AccountQueueFixturesTest) don't cover: the queue as the device stores it. */
class AccountQueueTest {
    @Test
    fun `the queue survives a trip through the device cache`() {
        val serializer = ListSerializer(AccountChange.serializer())
        val queue = listOf(
            AccountChange.CreateLabel(1, "local:label", "Trips", GmailLabelColor("#000000", "#ffffff")),
            AccountChange.PatchLabel(2, "local:label", name = "Travel"),
            AccountChange.CreateFilter(3, "local:filter", GmailFilterCriteria(from = "air@example.com"), GmailFilterAction(listOf("local:label")), "F1"),
            AccountChange.LabelMatching(4, "from:air@example.com", listOf("local:label"), listOf("INBOX")),
            AccountChange.DeleteFilter(5, "local:filter"),
        )
        assertEquals(queue, GmailJson.decodeFromString(serializer, GmailJson.encodeToString(serializer, queue)))
    }
}
