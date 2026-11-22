package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.gmail.GmailMessage

// How a device brings its saved copy up to date from Gmail's history: label changes apply to saved
// messages directly, new mail on a saved thread is read message by message (a message never
// changes once sent, so the rest stays), threads entering the saved set are read whole (a new draft
// too, at any age), and threads
// whose messages were all deleted go. A port of shared/src/history-apply.ts; both run
// shared/testdata/history-apply-fixtures.json.

private const val ENTERING = "INBOX"

// Labels that bring a thread into the saved set at any age.
private val KEPT = setOf(ENTERING, "DRAFT")
private val HIDDEN = setOf("SPAM", "TRASH")

/**
 * The order to read [fetch] (threads to read whole, from [applyHistory]): those history put in the
 * inbox (new mail carrying INBOX, or INBOX added) first, then the rest, each most recently named
 * first, so the inbox's newest mail is saved before older and other mail.
 */
fun readOrder(records: List<GmailHistoryRecord>, fetch: List<String>): List<String> {
    val inbox = mutableSetOf<String>()
    val lastNamed = mutableMapOf<String, Int>()
    records.forEachIndexed { position, record ->
        for (entry in record.messagesAdded.orEmpty()) {
            lastNamed[entry.message.threadId] = position
            if (entry.message.labelIds.orEmpty().contains(ENTERING)) inbox += entry.message.threadId
        }
        for (entry in record.labelsAdded.orEmpty()) {
            lastNamed[entry.message.threadId] = position
            if (entry.labelIds.orEmpty().contains(ENTERING)) inbox += entry.message.threadId
        }
        for (entry in record.labelsRemoved.orEmpty()) lastNamed[entry.message.threadId] = position
        for (entry in record.messagesDeleted.orEmpty()) lastNamed[entry.message.threadId] = position
    }
    return fetch.sortedWith(compareBy<String> { if (it in inbox) 0 else 1 }.thenByDescending { lastNamed[it] ?: -1 })
}

/** What history means for the saved copy. */
data class HistoryChanges(
    /** saved threads whose labels or messages changed, with every message's labels after (new ones too) */
    val relabeled: Map<String, Map<String, List<String>>>,
    /** saved threads' new messages to read one by one and add to their saved bodies, in the order history added them */
    val added: Map<String, List<String>>,
    /** saved threads whose every message was deleted */
    val removed: List<String>,
    /**
     * threads to read whole: new mail on a newly kept thread, one entering the inbox, or a label
     * change on a saved thread's message the copy doesn't know
     */
    val fetch: List<String>,
    /** other threads history named, whose list rows may be out of date */
    val touched: List<String>,
    /**
     * threads among [touched] with new mail (or out of spam or trash) that no other reason keeps:
     * read only far enough to tell whether they are encrypted, and read whole when they are
     */
    val check: List<String>,
)

/**
 * Apply history [records], oldest first, to the saved threads [saved] returns (message id to label
 * ids; null for one not saved). With [days] 0 only the inbox, drafts and snoozed threads are kept,
 * so new mail elsewhere isn't read. Each id is in at most one of the lists, in the order history
 * first named it.
 */
fun applyHistory(records: List<GmailHistoryRecord>, saved: (String) -> Map<String, List<String>>?, days: Int): HistoryChanges =
    HistoryWalk(saved, days).apply { records.forEach(::walk) }.changes()

private class HistoryWalk(private val saved: (String) -> Map<String, List<String>>?, private val days: Int) {
    private val working = mutableMapOf<String, MutableMap<String, List<String>>?>()
    private val changed = mutableSetOf<String>()
    private val addedIds = mutableMapOf<String, List<String>>()
    private val fetch = mutableSetOf<String>()
    private val touched = mutableSetOf<String>()
    private val check = mutableSetOf<String>()
    private val order = linkedSetOf<String>()

    private fun messagesOf(threadId: String): MutableMap<String, List<String>>? =
        working.getOrPut(threadId) { saved(threadId)?.let { LinkedHashMap(it) } }

    private fun read(threadId: String) {
        fetch += threadId
        addedIds -= threadId
        touched -= threadId
        check -= threadId
    }

    private fun touch(threadId: String) {
        if (threadId !in fetch) touched += threadId
    }

    fun walk(record: GmailHistoryRecord) {
        for (entry in record.messagesAdded.orEmpty()) added(entry.message)
        for (entry in record.labelsAdded.orEmpty()) relabel(entry.message, entry.labelIds.orEmpty(), adding = true)
        for (entry in record.labelsRemoved.orEmpty()) relabel(entry.message, entry.labelIds.orEmpty(), adding = false)
        for (entry in record.messagesDeleted.orEmpty()) deleted(entry.message)
    }

    private fun added(message: GmailMessage) {
        val threadId = message.threadId
        order += threadId
        if (threadId in fetch) return
        val labels = message.labelIds.orEmpty()
        val messages = messagesOf(threadId)
        if (messages != null) {
            if (message.id !in messages) {
                messages[message.id] = labels
                addedIds[threadId] = addedIds[threadId].orEmpty() + message.id
                changed += threadId
            }
            return
        }
        val keeps = labels.any { it in KEPT } || (days > 0 && labels.none { it in HIDDEN })
        if (keeps) {
            read(threadId)
        } else {
            touch(threadId)
            if (labels.none { it in HIDDEN }) check += threadId
        }
    }

    private fun relabel(message: GmailMessage, labelIds: List<String>, adding: Boolean) {
        val threadId = message.threadId
        order += threadId
        if (threadId in fetch) return
        val messages = messagesOf(threadId)
        if (messages == null) {
            val entering = if (adding) labelIds.any { it in KEPT } else days > 0 && labelIds.any { it in HIDDEN }
            if (entering) read(threadId) else touch(threadId)
            if (!entering && !adding && labelIds.any { it in HIDDEN }) check += threadId
            return
        }
        val labels = messages[message.id]
        if (labels == null) {
            read(threadId)
            return
        }
        val next = if (adding) labels + labelIds.filter { it !in labels } else labels.filter { it !in labelIds }
        if (next.size != labels.size) {
            messages[message.id] = next
            changed += threadId
        }
    }

    private fun deleted(message: GmailMessage) {
        val threadId = message.threadId
        order += threadId
        if (threadId in fetch) return
        val messages = messagesOf(threadId)
        if (messages == null) {
            touch(threadId)
        } else if (messages.remove(message.id) != null) {
            changed += threadId
            val fresh = addedIds[threadId].orEmpty() - message.id
            if (fresh.isEmpty()) addedIds -= threadId else addedIds[threadId] = fresh
        }
    }

    fun changes(): HistoryChanges {
        val relabeled = linkedMapOf<String, Map<String, List<String>>>()
        val added = linkedMapOf<String, List<String>>()
        val removed = mutableListOf<String>()
        for (threadId in order) {
            if (threadId in fetch || threadId !in changed) continue
            val messages = working.getValue(threadId)!!
            if (messages.isEmpty()) {
                removed += threadId
            } else {
                relabeled[threadId] = messages.toMap()
                addedIds[threadId]?.let { added[threadId] = it }
            }
        }
        return HistoryChanges(
            relabeled,
            added,
            removed,
            order.filter { it in fetch },
            order.filter { it in touched },
            order.filter { it in check && it in touched },
        )
    }
}

/** A thread's labels: every label on any of its messages, in first-seen order. */
fun threadLabels(messages: Map<String, List<String>>): List<String> = messages.values.flatten().distinct()
