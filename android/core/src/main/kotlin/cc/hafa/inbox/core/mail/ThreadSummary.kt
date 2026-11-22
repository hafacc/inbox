package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.pgp.OBSCURED_SUBJECT
import kotlinx.serialization.Serializable

/** Gmail's system labels this app reads. */
object Labels {
    /** the inbox */
    const val INBOX = "INBOX"

    /** a star, shown as a pin */
    const val STARRED = "STARRED"

    /** unread mail */
    const val UNREAD = "UNREAD"

    /** drafts */
    const val DRAFT = "DRAFT"

    /** sent mail */
    const val SENT = "SENT"

    /** spam */
    const val SPAM = "SPAM"

    /** trash */
    const val TRASH = "TRASH"

    /** Gmail's important mark */
    const val IMPORTANT = "IMPORTANT"
}

/** What a list row needs to know about a thread. */
@Serializable
data class ThreadSummary(
    /** Gmail thread id */
    val id: String,
    /** Gmail history id when fetched */
    val historyId: String,
    /** first message's subject */
    val subject: String,
    /** newest message's snippet, entities decoded */
    val snippet: String,
    /** distinct senders, oldest first */
    val senders: List<Person>,
    /** messages, not counting drafts */
    val messageCount: Int,
    /** epoch milliseconds lists place, sort and date it by: its latest received message (see [threadDates]) */
    val lastDate: Long,
    /** every label on any message of the thread, sorted */
    val labelIds: List<String>,
    /** whether any message is unread */
    val unread: Boolean,
    /** whether any message arrived or left encrypted (OpenPGP); its subject and snippet then come from the device */
    val encrypted: Boolean = false,
    /** epoch milliseconds of its latest message the user sent, for Sent */
    val sentDate: Long = lastDate,
    /** each message's `Message-ID`, drafts included, for joining split encrypted replies (see [joinKeys]) */
    val messageIds: List<String> = emptyList(),
    /** the ids its messages' `In-Reply-To` and `References` name; only kept when its subject is [OBSCURED_SUBJECT] */
    val answers: List<String> = emptyList(),
    /** epoch milliseconds of its earliest message, which orders a joined row's threads */
    val firstDate: Long = 0,
) {
    /** The thread as Sent lists it: placed, sorted and dated by its latest message the user sent. */
    fun sentDated(): ThreadSummary = copy(lastDate = sentDate)

    /** Whether the thread is pinned (Gmail's star). */
    val isPinned: Boolean get() = Labels.STARRED in labelIds

    /** Whether the thread is in the inbox: there, and not in spam or trash. */
    val isInInbox: Boolean get() = inInbox(labelIds)

    /** Whether Gmail marks the thread important. */
    val isImportant: Boolean get() = isImportant(labelIds)
}

private fun GmailMessage.isDraft(): Boolean = labelIds?.contains(Labels.DRAFT) == true

/** Summarize a thread fetched with `format=metadata` (From, Subject) or `format=full`. */
fun summarizeThread(thread: GmailThread): ThreadSummary {
    val messages = thread.messages.orEmpty()
    val sent = messages.filterNot { it.isDraft() }
    val counted = sent.ifEmpty { messages }
    val labels = sortedSetOf<String>()
    for (message in messages) labels += message.labelIds.orEmpty()
    val senders = mutableListOf<Person>()
    var unread = false
    for (message in counted) {
        if (message.labelIds?.contains(Labels.UNREAD) == true) unread = true
        val from = headerValue(message.payload?.headers, "From") ?: continue
        val person = parseAddress(from)
        val index = senders.indexOfFirst { it.email == person.email }
        if (index == -1) {
            senders += person
        } else if (person.name.isNotEmpty() && senders[index].name.isEmpty()) {
            senders[index] = senders[index].copy(name = person.name)
        }
    }
    val last = counted.lastOrNull()
    val dates = threadDates(messages.map { DatedMessage(it.internalDate?.toLongOrNull() ?: 0L, it.labelIds.orEmpty()) })
    val subject = headerValue(messages.firstOrNull()?.payload?.headers, "Subject")?.trim() ?: ""
    val keys = joinKeys(
        messages.map { message ->
            val headers = message.payload?.headers
            JoinKeyMessage(
                messageId = headerValue(headers, "Message-ID"),
                inReplyTo = headerValue(headers, "In-Reply-To"),
                references = headerValue(headers, "References"),
                date = message.internalDate?.toLongOrNull() ?: 0L,
            )
        },
        hiddenSubject = subject == OBSCURED_SUBJECT,
    )
    return ThreadSummary(
        id = thread.id,
        historyId = thread.historyId ?: last?.historyId ?: "0",
        subject = subject,
        snippet = decodeEntities(last?.snippet ?: thread.snippet ?: ""),
        senders = senders,
        messageCount = sent.size,
        lastDate = dates.received,
        labelIds = labels.toList(),
        unread = unread,
        encrypted = counted.any(::looksEncrypted),
        sentDate = dates.sent,
        messageIds = keys.messageIds,
        answers = keys.answers,
        firstDate = keys.firstDate,
    )
}

/**
 * A saved thread's row from its saved body and every message's labels ([labels]), as
 * [summarizeThread] makes it from Gmail's copy; [base] gives what the body can't (an encrypted
 * message's snippet, and the join keys when the saved messages carry no headers). The web's
 * `savedSummary` is the same.
 */
fun savedSummary(detail: ThreadDetail, labels: Map<String, List<String>>, base: ThreadSummary?, historyId: String): ThreadSummary {
    fun labelsOf(message: MessageView): List<String> = labels[message.id] ?: if (message.unread) listOf(Labels.UNREAD) else emptyList()
    val all = (detail.messages + detail.drafts).sortedBy { it.date }
    val counted = detail.messages.ifEmpty { all }
    val senders = mutableListOf<Person>()
    for (message in counted) {
        val person = message.from
        if (person.email.isEmpty() && person.name.isEmpty()) continue
        val index = senders.indexOfFirst { it.email == person.email }
        if (index == -1) {
            senders += person
        } else if (person.name.isNotEmpty() && senders[index].name.isEmpty()) {
            senders[index] = senders[index].copy(name = person.name)
        }
    }
    val dates = threadDates(all.map { DatedMessage(it.date, labelsOf(it)) })
    val keys = joinKeys(
        all.map { JoinKeyMessage(it.messageIdHeader, it.inReplyTo, it.references, it.date) },
        hiddenSubject = detail.subject == OBSCURED_SUBJECT,
    )
    // A saved copy with no headers to read joins as Gmail's copy did.
    val joinBase = base?.takeIf { keys.messageIds.isEmpty() }
    return ThreadSummary(
        id = detail.id,
        historyId = historyId,
        subject = detail.subject,
        snippet = counted.lastOrNull()?.snippet?.ifEmpty { null } ?: base?.snippet.orEmpty(),
        senders = senders,
        messageCount = detail.messages.size,
        lastDate = dates.received,
        labelIds = all.flatMap(::labelsOf).toSortedSet().toList(),
        unread = counted.any { Labels.UNREAD in labelsOf(it) },
        encrypted = base?.encrypted == true || counted.any { it.lock != null },
        sentDate = dates.sent,
        messageIds = joinBase?.messageIds ?: keys.messageIds,
        answers = joinBase?.answers ?: keys.answers,
        firstDate = keys.firstDate,
    )
}

private val MESSAGE_ID_RE = Regex("""<[^<>\s]+>""")

/** The `<…>` message ids a `Message-ID`, `In-Reply-To` or `References` header names, in order. */
fun messageIdsIn(header: String?): List<String> = header?.let { value -> MESSAGE_ID_RE.findAll(value).map { it.value }.toList() }.orEmpty()

/** Whether a message fetched as `metadata` or `full` is OpenPGP-encrypted, by its type or snippet. */
fun looksEncrypted(message: GmailMessage): Boolean {
    val type = message.payload?.mimeType?.takeIf { it.isNotEmpty() } ?: headerValue(message.payload?.headers, "Content-Type").orEmpty()
    return type.trim().startsWith("multipart/encrypted", ignoreCase = true) || message.snippet.orEmpty().contains("BEGIN PGP MESSAGE")
}

private val WHITESPACE_RE = Regex("""\s+""")

private fun firstName(person: Person): String {
    val name = person.name.trim()
    return if (name.isEmpty()) {
        person.email.substringBefore('@')
    } else {
        name.split(WHITESPACE_RE).first()
    }
}

/** Name for a sender: their display name, else the address's local part; "me" for the account itself. */
fun displayName(person: Person, selfEmail: String): String = when {
    person.email == selfEmail.lowercase() -> "me"
    person.name.isNotBlank() -> person.name.trim()
    else -> person.email.substringBefore('@')
}

/** The sender column: one sender by full name, several by first names (up to three). */
fun sendersLabel(senders: List<Person>, selfEmail: String): String {
    if (senders.isEmpty()) return "(no sender)"
    if (senders.size == 1) return displayName(senders.first(), selfEmail)
    val self = selfEmail.lowercase()
    val names = senders.map { if (it.email == self) "me" else firstName(it) }
    val shown = names.take(3).joinToString(", ")
    return if (names.size > 3) "$shown …" else shown
}

/** Gmail's category labels that the inbox treats like user labels, with the names it shows. */
val CATEGORY_NAMES: Map<String, String> = mapOf(
    "CATEGORY_PROMOTIONS" to "Promotions",
    "CATEGORY_SOCIAL" to "Social",
    "CATEGORY_UPDATES" to "Updates",
    "CATEGORY_FORUMS" to "Forums",
)

/** The thread's user labels, in the given label order. */
fun userLabelsOf(thread: ThreadSummary, userLabelIds: List<String>): List<String> =
    userLabelIds.filter { it in thread.labelIds }

/** Recipients of a message header, for the "to …" line. */
fun recipientsOf(message: GmailMessage): List<Person> {
    val headers = message.payload?.headers
    return parseAddressList(headerValue(headers, "To")) + parseAddressList(headerValue(headers, "Cc"))
}
