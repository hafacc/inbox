package cc.hafa.inbox.mail

import cc.hafa.inbox.core.mail.LocalDraftState
import cc.hafa.inbox.core.mail.LocalThreadDraft
import cc.hafa.inbox.core.mail.MessageBody
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drafts and messages being sent in an open thread, from Gmail and this device. */
class ThreadItemsTest {
    private fun message(id: String, date: Long) =
        MessageView(id, Person("", "sam@example.com"), emptyList(), date, id, false, MessageBody(null, null, emptyList(), emptyList()))

    private fun local(localId: String, messageId: String?, state: LocalDraftState, threadId: String = "t") =
        LocalDraft(LocalThreadDraft(localId, messageId, state, 1), threadId, "local $localId")

    private fun shown(items: List<ThreadItem>): List<String> = items.map { item ->
        when (item) {
            is ThreadItem.Message -> "message:${item.message.id}"
            is ThreadItem.Draft -> "draft:${item.message.id}"
            is ThreadItem.Local -> "${if (item.sending) "sending" else "local"}:${item.draft.draft.localId}"
        }
    }

    @Test
    fun draftsShowInPlaceWithTheDevicesCopiesOverGmails() {
        val detail = ThreadDetail(
            "t",
            "1",
            "Lease",
            messages = listOf(message("m1", 1000), message("m2", 3000)),
            drafts = listOf(message("d1", 2000), message("d2", 4000), message("d3", 5000)),
        )
        val items = threadItems(
            detail,
            listOf(
                local("a", "d2", LocalDraftState.DRAFT),
                local("b", "d3", LocalDraftState.DELETED),
                local("c", null, LocalDraftState.SENDING),
                local("other", null, LocalDraftState.DRAFT, threadId = "elsewhere"),
            ),
        )
        assertEquals(listOf("message:m1", "draft:d1", "message:m2", "local:a", "sending:c"), shown(items))
    }

    @Test
    fun aSentMessageGmailHasReplacesTheOneBeingSent() {
        val detail = ThreadDetail("t", "1", "Lease", messages = listOf(message("m1", 1000), message("d1", 2000)))
        assertEquals(listOf("message:m1", "message:d1"), shown(threadItems(detail, listOf(local("a", "d1", LocalDraftState.SENDING)))))
    }
}
