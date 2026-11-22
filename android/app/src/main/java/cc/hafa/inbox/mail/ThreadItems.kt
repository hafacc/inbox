package cc.hafa.inbox.mail

import cc.hafa.inbox.core.mail.GmailThreadMessage
import cc.hafa.inbox.core.mail.LocalThreadDraft
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.ThreadEntry
import cc.hafa.inbox.core.mail.threadEntries

/** A draft this device holds, as its thread shows it. */
data class LocalDraft(
    /** where it stands, for the thread rule */
    val draft: LocalThreadDraft,
    /** the Gmail thread of a reply */
    val threadId: String?,
    /** the body's first words */
    val snippet: String,
    /** the Gmail thread it is in once saved there, a new message's own one too */
    val draftThreadId: String? = null,
    /** `To` */
    val to: List<Person> = emptyList(),
    /** `Bcc` */
    val bcc: List<Person> = emptyList(),
    /** subject */
    val subject: String = "",
)

/** One entry of an open thread as it shows. */
sealed interface ThreadItem {
    /** A sent or received message. */
    data class Message(
        /** the message */
        val message: MessageView,
    ) : ThreadItem

    /** A draft only Gmail has. */
    data class Draft(
        /** the draft's message */
        val message: MessageView,
    ) : ThreadItem

    /** A draft, or a message being sent, as this device holds it. */
    data class Local(
        /** the draft */
        val draft: LocalDraft,
        /** whether it is being sent */
        val sending: Boolean,
    ) : ThreadItem
}

/**
 * What an open thread shows, in order: its messages and drafts, with this device's drafts of the
 * thread in place of Gmail's copies, and none of the drafts the Outbox holds ([outbox], their
 * message ids) ([threadEntries]).
 */
fun threadItems(detail: ThreadDetail, local: List<LocalDraft>, outbox: Set<String> = emptySet()): List<ThreadItem> {
    val gmail = (detail.messages.map { it to false } + detail.drafts.map { it to true }).sortedBy { it.first.date }
    val byId = gmail.associate { (message, _) -> message.id to message }
    val mine = local.filter { it.threadId in detail.threadIds }
    val byLocal = mine.associateBy { it.draft.localId }
    return threadEntries(gmail.map { (message, draft) -> GmailThreadMessage(message.id, draft) }, mine.map { it.draft }, outbox).mapNotNull { entry ->
        when (entry) {
            is ThreadEntry.Message -> byId[entry.id]?.let { ThreadItem.Message(it) }
            is ThreadEntry.Draft -> byId[entry.id]?.let { ThreadItem.Draft(it) }
            is ThreadEntry.Local -> byLocal[entry.localId]?.let { ThreadItem.Local(it, entry.sending) }
        }
    }
}
