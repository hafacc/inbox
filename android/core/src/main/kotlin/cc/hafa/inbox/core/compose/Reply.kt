package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person

/** The headers of the message being replied to that threading needs. */
data class ReplySource(
    /** its `Message-ID`, angle brackets included */
    val messageId: String,
    /** its `References` header, or null */
    val references: String?,
    /** its `In-Reply-To` header, or null */
    val inReplyTo: String?,
    /** its decoded `Subject` */
    val subject: String,
)

/** Threading headers for a reply. */
data class ReplyHeaders(
    /** the reply's subject */
    val subject: String,
    /** `In-Reply-To`: the replied-to message's id */
    val inReplyTo: String,
    /** `References`: message ids, oldest first */
    val references: List<String>,
)

/** The people on the message being replied to. */
data class ReplyParticipants(
    /** `From` */
    val from: List<Person>,
    /** `Reply-To`; empty when the header is absent */
    val replyTo: List<Person>,
    /** `To` */
    val to: List<Person>,
    /** `Cc` */
    val cc: List<Person>,
)

/** Recipients of a reply. */
data class ReplyRecipients(
    /** `To` */
    val to: List<Person>,
    /** `Cc` */
    val cc: List<Person>,
)

private val REPLY_PREFIX = Regex("""^re(\[\d+])?\s*:""", RegexOption.IGNORE_CASE)
private val FORWARD_PREFIX = Regex("""^(fwd?|fw)\s*:""", RegexOption.IGNORE_CASE)
// Control characters aren't taken inside an id: buildMime refuses them in a header.
private val MESSAGE_ID = Regex("""<[^<>\s\p{Cc}]+>""")

/** A reply's subject: kept when it already starts with `Re:` (any case, `Re[2]:` too), else `Re: ` in front. */
fun replySubject(subject: String): String {
    val trimmed = subject.trim()
    return when {
        REPLY_PREFIX.containsMatchIn(trimmed) -> trimmed
        trimmed.isEmpty() -> "Re:"
        else -> "Re: $trimmed"
    }
}

/** Whether [subject] starts with `Fwd:` or `Fw:` (any case), as a forward's does. */
fun isForwardSubject(subject: String): Boolean = FORWARD_PREFIX.containsMatchIn(subject.trim())

/** A forward's subject: kept when it already starts with `Fwd:` or `Fw:`, else `Fwd: ` in front. */
fun forwardSubject(subject: String): String {
    val trimmed = subject.trim()
    return when {
        FORWARD_PREFIX.containsMatchIn(trimmed) -> trimmed
        trimmed.isEmpty() -> "Fwd:"
        else -> "Fwd: $trimmed"
    }
}

/** The `<…>` message ids in a header value, in order. */
fun messageIds(header: String?): List<String> =
    if (header == null) emptyList() else MESSAGE_ID.findAll(header).map { it.value }.toList()

/**
 * Threading headers for replying to [source]: `In-Reply-To` is its id; `References` is its
 * `References` (or, without one, its single `In-Reply-To` id) followed by its id, each id once,
 * however long the chain. Null when its `messageId` holds no `<id>`.
 */
fun replyHeaders(source: ReplySource): ReplyHeaders? {
    val own = messageIds(source.messageId).firstOrNull() ?: return null
    var earlier = messageIds(source.references)
    if (earlier.isEmpty()) {
        val replied = messageIds(source.inReplyTo)
        if (replied.size == 1) earlier = replied
    }
    val seen = mutableSetOf(own)
    val references = mutableListOf<String>()
    for (id in earlier) {
        if (seen.add(id)) references += id
    }
    references += own
    return ReplyHeaders(replySubject(source.subject), own, references)
}

/** Subject and threading headers of a reply or forward; no threading when the source has no `<id>`. */
data class AnswerHeaders(
    /** the answer's subject */
    val subject: String,
    /** `In-Reply-To`, or null when the source's `Message-ID` holds no `<id>` */
    val inReplyTo: String?,
    /** `References`, oldest first; empty when the source's `Message-ID` holds no `<id>` */
    val references: List<String>,
)

/**
 * What keeps an answer to [source] in its thread: a reply's subject ([replySubject]), or with
 * [forward] a forward's ([forwardSubject]), and for both [replyHeaders]' `In-Reply-To` and
 * `References`. Gmail files a message sent with a thread id into that thread only when these hold
 * and the subjects match. A source whose `messageId` holds no `<id>` (blank, or anything else) gives
 * no threading headers, so nothing of it is copied into the answer's header.
 */
fun answerHeaders(source: ReplySource, forward: Boolean): AnswerHeaders {
    val subject = if (forward) forwardSubject(source.subject) else replySubject(source.subject)
    val headers = replyHeaders(source)
    return if (headers == null) {
        AnswerHeaders(subject, null, emptyList())
    } else {
        AnswerHeaders(subject, headers.inReplyTo, headers.references)
    }
}

private fun withoutAddresses(mailboxes: List<Person>, excluded: MutableSet<String>): List<Person> =
    mailboxes.filter { excluded.add(addressKey(it.email)) }

/**
 * Who a reply goes to.
 *
 * To someone else's message: `Reply-To` if present, else `From`. To one of the user's own
 * messages: the original `To`, or the sender again when that leaves no one. Reply all adds the
 * original `To` and `Cc` as `Cc`. The user's own addresses and repeats are dropped.
 */
fun replyRecipients(original: ReplyParticipants, ownAddresses: List<String>, replyAll: Boolean): ReplyRecipients {
    val own = ownAddresses.map(::addressKey).toSet()
    val fromMe = original.from.any { addressKey(it.email) in own }
    val used = own.toMutableSet()
    val to = if (fromMe) {
        withoutAddresses(original.to, used).ifEmpty { original.from }
    } else {
        withoutAddresses(original.replyTo.ifEmpty { original.from }, used)
    }
    for (mailbox in to) used += addressKey(mailbox.email)
    val cc = if (replyAll) withoutAddresses(if (fromMe) original.cc else original.to + original.cc, used) else emptyList()
    return ReplyRecipients(to, cc)
}

/** Whether answering [original] offers Reply all: it would reach more than one other person. */
fun offersReplyAll(original: ReplyParticipants, ownAddresses: List<String>): Boolean {
    val (to, cc) = replyRecipients(original, ownAddresses, true)
    return to.size + cc.size > 1
}

/** The user's address the original was sent to (first in `To`, then `Cc`), to reply from; else [fallback]. */
fun replyFromAddress(original: ReplyParticipants, ownAddresses: List<String>, fallback: String): String {
    val own = ownAddresses.associateBy(::addressKey)
    for (mailbox in original.to + original.cc) {
        own[addressKey(mailbox.email)]?.let { return it }
    }
    return fallback
}
