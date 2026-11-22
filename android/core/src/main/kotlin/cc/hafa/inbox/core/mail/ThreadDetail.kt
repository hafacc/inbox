package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.pgp.MessageLock
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One message of an opened thread, ready to show. */
@Serializable
data class MessageView(
    /** Gmail message id */
    val id: String,
    /** sender */
    val from: Person,
    /** To recipients */
    val to: List<Person>,
    /** when Gmail received it, epoch milliseconds */
    val date: Long,
    /** Gmail's snippet, entities decoded */
    val snippet: String,
    /** whether it is unread */
    val unread: Boolean,
    /** text, HTML and attachments */
    val body: MessageBody,
    /** Cc recipients */
    val cc: List<Person> = emptyList(),
    /** Reply-To; empty when the message has none */
    val replyTo: List<Person> = emptyList(),
    /** Subject header */
    val subject: String = "",
    /** Message-ID header, as sent (angle brackets included) */
    val messageIdHeader: String? = null,
    /** References header, as sent */
    val references: String? = null,
    /** In-Reply-To header, as sent */
    val inReplyTo: String? = null,
    /** Date header, epoch milliseconds; null when missing or unreadable */
    val sentDate: Long? = null,
    /** List-Unsubscribe header, raw; null when absent */
    val listUnsubscribe: String? = null,
    /** List-Unsubscribe-Post header, raw; null when absent */
    val listUnsubscribePost: String? = null,
    /** for mail that arrived or left encrypted: whether this device could open it, and its signature */
    val lock: MessageLock? = null,
    /** Bcc as the message carries it: on sent mail, or "you" in an encrypted Bcc copy */
    val bcc: List<Person> = emptyList(),
    /** Gmail id of its own thread; empty when not known (copies saved before it was kept) */
    val threadId: String = "",
)

/** An opened thread: its subject and messages, oldest first, drafts left out. */
@Serializable
data class ThreadDetail(
    /** Gmail thread id */
    val id: String,
    /** Gmail history id when fetched */
    val historyId: String,
    /** first message's subject */
    val subject: String,
    /** sent and received messages, oldest first */
    val messages: List<MessageView>,
    /** unsent drafts in the thread, oldest first */
    val drafts: List<MessageView> = emptyList(),
) {
    /** The Gmail threads it shows: its own and, for a joined row ([mergeDetails]), each one its messages are in. */
    val threadIds: Set<String> get() = setOf(id) + (messages + drafts).mapNotNull { message -> message.threadId.ifEmpty { null } }

    /** The Gmail thread a reply to (or forward of) [message] goes in: the message's own. */
    fun threadOf(message: MessageView): String = message.threadId.ifEmpty { id }

    /**
     * This thread with Gmail thread [threadId]'s messages set to the labels history gave them
     * ([labels], message id to label ids): each one's unread flag follows, and those history deleted
     * go. Other threads' messages of a joined row stay; itself when nothing changed. The web's
     * `relabelDetail` is the same.
     */
    fun relabeled(threadId: String, labels: Map<String, List<String>>): ThreadDetail {
        var changed = false
        fun relabel(views: List<MessageView>): List<MessageView> = views.mapNotNull { message ->
            val own = labels[message.id]
            when {
                threadOf(message) != threadId -> message
                own == null -> null.also { changed = true }
                (Labels.UNREAD in own) == message.unread -> message
                else -> message.copy(unread = !message.unread).also { changed = true }
            }
        }
        val nextMessages = relabel(messages)
        val nextDrafts = relabel(drafts)
        return if (changed) copy(messages = nextMessages, drafts = nextDrafts) else this
    }
}

/**
 * [detail] with Gmail [messages] new to it added, each in date order with the rest; those it already
 * holds stay as they are. The web's `withAddedMessages` is the same.
 */
fun withAddedMessages(detail: ThreadDetail, messages: List<GmailMessage>): ThreadDetail {
    val known = (detail.messages + detail.drafts).map { it.id }.toSet()
    val fresh = messages.filter { it.id !in known }
    if (fresh.isEmpty()) return detail
    val added = threadDetail(GmailThread(id = detail.id, messages = fresh))
    return detail.copy(
        messages = (detail.messages + added.messages).sortedBy { it.date },
        drafts = (detail.drafts + added.drafts).sortedBy { it.date },
    )
}

/** One Gmail message (format `full`) as the reader shows it, encrypted or not. */
fun messageView(message: GmailMessage): MessageView {
    val headers = message.payload?.headers
    val from = headerValue(headers, "From")
    return MessageView(
        id = message.id,
        from = if (from != null) parseAddress(from) else Person("", ""),
        to = parseAddressList(headerValue(headers, "To")),
        date = message.internalDate?.toLongOrNull() ?: 0L,
        snippet = decodeEntities(message.snippet ?: ""),
        unread = message.labelIds?.contains(Labels.UNREAD) == true,
        body = message.payload?.let(::extractBody) ?: MessageBody(null, null, emptyList(), emptyList()),
        cc = parseAddressList(headerValue(headers, "Cc")),
        replyTo = parseAddressList(headerValue(headers, "Reply-To")),
        subject = headerValue(headers, "Subject")?.trim() ?: "",
        messageIdHeader = headerValue(headers, "Message-ID"),
        references = headerValue(headers, "References"),
        inReplyTo = headerValue(headers, "In-Reply-To"),
        sentDate = headerValue(headers, "Date")?.let(::parseDateHeader),
        listUnsubscribe = headerValue(headers, "List-Unsubscribe"),
        listUnsubscribePost = headerValue(headers, "List-Unsubscribe-Post"),
        bcc = parseAddressList(headerValue(headers, "Bcc")),
        threadId = message.threadId,
    )
}

/** Turn a thread fetched with `format=full` into what the reader shows. */
fun threadDetail(thread: GmailThread): ThreadDetail {
    val (drafts, messages) = thread.messages.orEmpty().partition { it.labelIds?.contains(Labels.DRAFT) == true }
    return ThreadDetail(
        id = thread.id,
        historyId = thread.historyId ?: "0",
        subject = headerValue(thread.messages?.firstOrNull()?.payload?.headers, "Subject")?.trim() ?: "",
        messages = messages.map(::messageView),
        drafts = drafts.map(::messageView),
    )
}

/** How a thread first shows when it opens: the messages that start expanded and the one scrolled to. */
data class ThreadOpenState(
    /** ids of the messages that start expanded, in thread order */
    val expanded: List<String>,
    /** id of the message the view scrolls to, or null for an empty thread */
    val scrollTo: String?,
)

/**
 * How a thread opens, matching web's `shared/src/thread-open.ts`.
 *
 * Every unread message and the last message start expanded. The view scrolls to the first unread
 * message, or to the last message when none is unread.
 */
fun threadOpenState(messages: List<MessageView>): ThreadOpenState {
    val last = messages.lastOrNull()
    val expanded = messages.filter { it.unread || it === last }.map { it.id }
    return ThreadOpenState(expanded, (messages.firstOrNull { it.unread } ?: last)?.id)
}

private val DATE_COMMENT_RE = Regex("""\([^)]*\)""")
private val DATE_SPACES_RE = Regex("""\s+""")

/** A `Date` header (RFC 5322) as epoch milliseconds, or null when it can't be read. */
fun parseDateHeader(value: String): Long? {
    val cleaned = value.replace(DATE_COMMENT_RE, " ").replace(DATE_SPACES_RE, " ").trim()
    return runCatching { ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}
