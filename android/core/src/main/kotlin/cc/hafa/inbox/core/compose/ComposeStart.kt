package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.mail.MessageBody
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.extractBody
import cc.hafa.inbox.core.mail.headerValue
import cc.hafa.inbox.core.mail.parseAddressList
import cc.hafa.inbox.core.snooze.SnoozeSuggestions.LABEL_LOCALE
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jsoup.Jsoup

/** How a composer starts. */
enum class ComposeKind {
    /** a new message */
    NEW,

    /** a reply to the sender */
    REPLY,

    /** a reply to everyone */
    REPLY_ALL,

    /** a forward */
    FORWARD,
}

private fun participantsOf(message: MessageView) =
    ReplyParticipants(listOf(message.from), message.replyTo, message.to, message.cc)

/** The sender to use for [email], with its display name when it's one of [senders]. */
fun senderFor(email: String, senders: List<Person>, fallback: Person): Person =
    senders.firstOrNull { addressKey(it.email) == addressKey(email) } ?: fallback

/** [message] as a reply quotes it: its sender, its `Date` (else Gmail's date), its text and HTML as received. */
fun quotedMessage(message: MessageView): QuotedMessage =
    QuotedMessage(message.from, message.sentDate ?: message.date, messageText(message), message.body.html)

/**
 * A reply (or reply all) to [message] in [threadId]: recipients, From and threading headers as
 * docs/compose-contract.md describes; the body starts empty and [message] is quoted below it.
 */
fun replyDraft(
    localId: String,
    threadId: String,
    message: MessageView,
    senders: List<Person>,
    defaultFrom: Person,
    replyAll: Boolean,
    signature: String?,
): DraftContent {
    val participants = participantsOf(message)
    val own = senders.map { it.email }
    val recipients = replyRecipients(participants, own, replyAll)
    val from = senderFor(replyFromAddress(participants, own, defaultFrom.email), senders, defaultFrom)
    val headers = answerOf(message, forward = false)
    return DraftContent(
        localId = localId,
        threadId = threadId,
        from = from,
        to = recipients.to,
        cc = recipients.cc,
        subject = headers.subject,
        signature = signature,
        inReplyTo = headers.inReplyTo,
        references = headers.references,
        quote = quotedMessage(message),
    )
}

private fun answerOf(message: MessageView, forward: Boolean): AnswerHeaders =
    answerHeaders(ReplySource(message.messageIdHeader.orEmpty(), message.references, message.inReplyTo, message.subject), forward)

private val FORWARD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm", LABEL_LOCALE)

private fun named(person: Person): String = if (person.name.isEmpty()) person.email else "${person.name} <${person.email}>"

/** The original message's text for a forward: its plain part, or its HTML's text. */
fun messageText(message: MessageView): String =
    message.body.text ?: message.body.html?.let { Jsoup.parse(it).wholeText() } ?: message.snippet

/**
 * A forward of [message] in [threadId]: its body quotes the original under a "Forwarded message"
 * header, and it carries the same threading headers as a reply, so it stays in the thread as
 * Gmail keeps its own forwards. The caller adds the original's attachments.
 */
fun forwardDraft(localId: String, threadId: String, message: MessageView, from: Person, signature: String?, zone: ZoneId): DraftContent {
    val header = buildList {
        add("---------- Forwarded message ---------")
        add("From: ${named(message.from)}")
        add("Date: ${FORWARD_DATE.format(Instant.ofEpochMilli(message.date).atZone(zone))}")
        add("Subject: ${message.subject}")
        if (message.to.isNotEmpty()) add("To: ${message.to.joinToString(", ", transform = ::named)}")
        if (message.cc.isNotEmpty()) add("Cc: ${message.cc.joinToString(", ", transform = ::named)}")
    }
    val quoted = normalizeNewlines(messageText(message)).trimEnd().lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    val headers = answerOf(message, forward = true)
    return DraftContent(
        localId = localId,
        threadId = threadId,
        from = from,
        subject = headers.subject,
        markdown = "\n\n" + header.joinToString("\n") + "\n\n" + quoted,
        signature = signature,
        inReplyTo = headers.inReplyTo,
        references = headers.references,
    )
}

/** A file of a Gmail draft still to be fetched: attachment id or inline data, and how it's shown. */
data class RemoteDraftFile(
    /** the file as the composer shows it */
    val file: DraftFile,
    /** its part's place in the draft's tree, which the fetched bytes are kept by */
    val partId: String,
    /** Gmail attachment id to fetch it by */
    val attachmentId: String?,
    /** its inline data, when the draft carries it */
    val data: String?,
)

/** The `In-Reply-To` id of a Gmail draft, or null when it isn't a reply. */
fun draftInReplyTo(message: GmailMessage): String? = messageIds(headerValue(message.payload?.headers, "In-Reply-To")).firstOrNull()

/**
 * A Gmail draft read back into a composer: headers, the typed markdown with its signature and
 * any reply quote split off, and the files to fetch. A reply's quote is rebuilt from [repliedTo]
 * (the message its `In-Reply-To` names) when given, else from the quote's own text. [localId]
 * names it on this device; each file gets an id from [fileId].
 */
fun draftFromMessage(
    localId: String,
    draftId: String,
    message: GmailMessage,
    fileId: () -> String,
    repliedTo: MessageView? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<DraftContent, List<RemoteDraftFile>> {
    val headers = message.payload?.headers
    val fields = DraftFields(
        draftId = draftId,
        messageId = message.id,
        threadId = message.threadId,
        from = parseAddressList(headerValue(headers, "From")).firstOrNull() ?: Person("", ""),
        to = parseAddressList(headerValue(headers, "To")),
        cc = parseAddressList(headerValue(headers, "Cc")),
        bcc = parseAddressList(headerValue(headers, "Bcc")),
        subject = headerValue(headers, "Subject")?.trim() ?: "",
        inReplyTo = draftInReplyTo(message),
        references = messageIds(headerValue(headers, "References")),
        body = message.payload?.let(::extractBody),
    )
    return draftContent(localId, fields, fileId, repliedTo, zone)
}

/**
 * A draft the saved copy holds ([message], Gmail draft [draftId], in Gmail thread [threadId]) read
 * back into a composer, as [draftFromMessage] reads Gmail's.
 */
fun draftFromView(
    localId: String,
    draftId: String,
    threadId: String,
    message: MessageView,
    fileId: () -> String,
    repliedTo: MessageView? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<DraftContent, List<RemoteDraftFile>> {
    val fields = DraftFields(
        draftId = draftId,
        messageId = message.id,
        threadId = message.threadId.ifEmpty { threadId },
        from = message.from,
        to = message.to,
        cc = message.cc,
        bcc = message.bcc,
        subject = message.subject,
        inReplyTo = messageIds(message.inReplyTo).firstOrNull(),
        references = messageIds(message.references),
        body = message.body,
    )
    return draftContent(localId, fields, fileId, repliedTo, zone)
}

// A draft's fields, as read from Gmail or the saved copy.
private class DraftFields(
    val draftId: String,
    val messageId: String,
    val threadId: String,
    val from: Person,
    val to: List<Person>,
    val cc: List<Person>,
    val bcc: List<Person>,
    val subject: String,
    val inReplyTo: String?,
    val references: List<String>,
    val body: MessageBody?,
)

private fun draftContent(
    localId: String,
    fields: DraftFields,
    fileId: () -> String,
    repliedTo: MessageView?,
    zone: ZoneId,
): Pair<DraftContent, List<RemoteDraftFile>> {
    val body = fields.body
    val fullText = body?.text ?: body?.html?.let { Jsoup.parse(it).wholeText() } ?: ""
    // A forward's original is part of what was typed, not a quote below it.
    val split = if (fields.inReplyTo == null || isForwardSubject(fields.subject)) QuoteSplit(fullText, null) else splitQuotedText(fullText)
    val quote = split.quote?.let { repliedTo?.let(::quotedMessage) ?: quoteFromText(it, zone) }
    val (markdown, signature) = splitSignature(split.body)
    val files = buildList {
        for (part in body?.inline.orEmpty()) {
            val extension = part.mimeType.substringAfter('/', "img")
            add(RemoteDraftFile(DraftFile(fileId(), "image.$extension", part.mimeType, 0, part.contentId), part.partId, part.attachmentId, part.data))
        }
        for (attachment in body?.attachments.orEmpty()) {
            add(RemoteDraftFile(DraftFile(fileId(), attachment.filename, attachment.mimeType, attachment.size), attachment.partId, attachment.attachmentId, attachment.data))
        }
    }
    val content = DraftContent(
        localId = localId,
        draftId = fields.draftId,
        messageId = fields.messageId.ifEmpty { null },
        threadId = fields.threadId.ifEmpty { null },
        from = fields.from,
        to = fields.to,
        cc = fields.cc,
        bcc = fields.bcc,
        subject = fields.subject,
        markdown = draftLinks(markdown, body?.html),
        signature = signature,
        inReplyTo = fields.inReplyTo,
        references = fields.references,
        quote = quote,
    )
    return content to files
}
