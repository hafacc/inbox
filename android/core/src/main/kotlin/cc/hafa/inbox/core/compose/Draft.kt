package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person
import kotlinx.serialization.Serializable

/** A file on a draft, kept on the device by id; [contentId] marks an inline image. */
@Serializable
data class DraftFile(
    /** local id, the file's name on the device */
    val id: String,
    /** name shown to the recipient */
    val filename: String,
    /** content type */
    val mimeType: String,
    /** size in bytes */
    val size: Long,
    /** Content-ID for an image shown in the body; null for an attachment */
    val contentId: String? = null,
)

/** A send waiting for its draft to reach Gmail before the outbox doc can be written. */
@Serializable
data class PendingSend(
    /** outbox document id */
    val outboxId: String,
    /** when it goes, epoch milliseconds */
    val sendAt: Long,
    /** the unshifted suggestion picked, or null */
    val chosenAt: Long?,
    /** the user chose "Send without encryption" for this send on a device that hasn't unlocked the key */
    val unencrypted: Boolean = false,
    /** sent right away (Send, with its undo delay, or by the app itself) rather than scheduled */
    val now: Boolean = false,
)

/** Everything a message being written is made of, saved on the device as it's typed. */
@Serializable
data class DraftContent(
    /** id on this device */
    val localId: String,
    /** Gmail draft id once saved there */
    val draftId: String? = null,
    /** Gmail message id of the draft as last saved there; each save gives it a new one */
    val messageId: String? = null,
    /** Gmail thread of a reply */
    val threadId: String? = null,
    /** Gmail thread the draft is in once saved there, a new message's own one too */
    val draftThreadId: String? = null,
    /** sender */
    val from: Person,
    /** `To` */
    val to: List<Person> = emptyList(),
    /** `Cc` */
    val cc: List<Person> = emptyList(),
    /** `Bcc` */
    val bcc: List<Person> = emptyList(),
    /** subject */
    val subject: String = "",
    /** body, markdown */
    val markdown: String = "",
    /** signature, markdown; null for none */
    val signature: String? = null,
    /** `In-Reply-To` of a reply */
    val inReplyTo: String? = null,
    /** `References` of a reply */
    val references: List<String> = emptyList(),
    /** attachments and inline images */
    val files: List<DraftFile> = emptyList(),
    /** the outbox doc this draft is scheduled by, while it is */
    val outboxId: String? = null,
    /** the message a reply quotes below the signature; null for none */
    val quote: QuotedMessage? = null,
    /** an iCalendar part sent with the message (an RSVP); null for none */
    val calendar: CalendarPart? = null,
    /** body as the editor's HTML; null when it wasn't edited, so it's built from [markdown] */
    val html: String? = null,
)

/** Whether a draft holds nothing worth keeping. */
fun DraftContent.isEmpty(): Boolean =
    to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject.isBlank() && markdown.isBlank() && files.isEmpty()

/**
 * The draft's message input; [read] gives each file's bytes. Inline images neither the body nor
 * the quote shows are dropped.
 */
fun DraftContent.composeInput(boundary: String, read: (DraftFile) -> ByteArray): ComposeInput {
    val (inline, attached) = files.partition { it.contentId != null }
    val quoted = quotedContentIds(quote?.html)
    return ComposeInput(
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject,
        markdown = markdown,
        signature = signature,
        inReplyTo = inReplyTo,
        references = references,
        inlineImages = inline
            .filter { markdown.contains("cid:${it.contentId}") || it.contentId in quoted }
            .map { MimeInlineImage(it.filename, it.mimeType, read(it), it.contentId ?: "") },
        attachments = attached.map { MimeAttachment(it.filename, it.mimeType, read(it)) },
        extraHeaders = emptyList(),
        boundary = boundary,
        quote = quote,
        calendar = calendar,
        html = html,
    )
}
