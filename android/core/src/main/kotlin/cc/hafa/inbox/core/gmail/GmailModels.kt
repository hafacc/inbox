package cc.hafa.inbox.core.gmail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One header of a Gmail message part. */
@Serializable
data class GmailHeader(
    /** header name */
    val name: String,
    /** header value, unfolded */
    val value: String,
)

/** Body of a Gmail message part: inline base64url data, or an attachment id to fetch it by. */
@Serializable
data class GmailPartBody(
    /** id to fetch the data by when it isn't inline */
    val attachmentId: String? = null,
    /** size in bytes */
    val size: Long = 0,
    /** base64url data when inline */
    val data: String? = null,
)

/** A node of a Gmail message's MIME tree. */
@Serializable
data class GmailMessagePart(
    /** position in the tree, such as `0.1` */
    val partId: String? = null,
    /** MIME type */
    val mimeType: String = "",
    /** attachment file name; empty for body parts */
    val filename: String? = null,
    /** the part's headers */
    val headers: List<GmailHeader>? = null,
    /** the part's own content */
    val body: GmailPartBody? = null,
    /** child parts of a multipart */
    val parts: List<GmailMessagePart>? = null,
)

/** A Gmail message as `messages.get` / `threads.get` return it. */
@Serializable
data class GmailMessage(
    /** message id */
    val id: String,
    /** id of its thread */
    val threadId: String = "",
    /** labels on the message */
    val labelIds: List<String>? = null,
    /** start of the text, HTML-escaped */
    val snippet: String? = null,
    /** history id of its last change */
    val historyId: String? = null,
    /** when Gmail received it, epoch milliseconds as a decimal string */
    val internalDate: String? = null,
    /** the MIME tree, for `full` and `metadata` formats */
    val payload: GmailMessagePart? = null,
    /** approximate size in bytes */
    val sizeEstimate: Long? = null,
)

/** A Gmail thread as `threads.get` returns it. */
@Serializable
data class GmailThread(
    /** thread id */
    val id: String,
    /** history id of its last change */
    val historyId: String? = null,
    /** start of the newest message's text */
    val snippet: String? = null,
    /** messages, oldest first */
    val messages: List<GmailMessage>? = null,
)

/** One entry of `threads.list`. */
@Serializable
data class GmailThreadStub(
    /** thread id */
    val id: String,
    /** start of the newest message's text */
    val snippet: String? = null,
    /** history id of its last change */
    val historyId: String? = null,
)

/** One entry of `messages.list`. */
@Serializable
data class GmailMessageStub(
    /** message id */
    val id: String,
    /** id of its thread */
    val threadId: String = "",
)

/** A page of `messages.list`. */
@Serializable
data class GmailMessageList(
    /** messages on this page */
    val messages: List<GmailMessageStub>? = null,
    /** token for the next page; null on the last */
    val nextPageToken: String? = null,
)

/** A message as `messages.get?format=raw` returns it: the whole RFC 822 text, base64url. */
@Serializable
data class GmailRawMessage(
    /** message id */
    val id: String,
    /** id of its thread */
    val threadId: String = "",
    /** the RFC 822 text, base64url */
    val raw: String = "",
    /** labels on the message */
    val labelIds: List<String>? = null,
)

/** A page of `threads.list`. */
@Serializable
data class GmailThreadList(
    /** threads on this page */
    val threads: List<GmailThreadStub>? = null,
    /** token for the next page; null on the last */
    val nextPageToken: String? = null,
    /** Gmail's estimate of the total matches */
    val resultSizeEstimate: Long? = null,
)

/** A label's colors as Gmail stores them. */
@Serializable
data class GmailLabelColor(
    /** text color, `#rrggbb` */
    val textColor: String? = null,
    /** background color, `#rrggbb` */
    val backgroundColor: String? = null,
)

/** A Gmail label from `labels.list` / `labels.get`. */
@Serializable
data class GmailLabel(
    /** label id */
    val id: String,
    /** display name; `/` separates nesting */
    val name: String = "",
    /** "system" or "user" */
    val type: String = "user",
    /** "labelShow", "labelShowIfUnread" or "labelHide" */
    val labelListVisibility: String? = null,
    /** colors, when the user set them */
    val color: GmailLabelColor? = null,
    /** threads with the label */
    val threadsTotal: Long? = null,
    /** unread threads with the label */
    val threadsUnread: Long? = null,
)

/** The `labels.list` response. */
@Serializable
data class GmailLabelList(
    /** every label */
    val labels: List<GmailLabel>? = null,
)

/** A message added to the mailbox, as a history record lists it. */
@Serializable
data class GmailMessageAdded(
    /** the message added */
    val message: GmailMessage,
)

/** Labels added to a message, as a history record lists them. */
@Serializable
data class GmailLabelsAdded(
    /** the message labeled */
    val message: GmailMessage,
    /** labels added */
    val labelIds: List<String>? = null,
)

/** One record of `history.list`, reduced to the message and label changes this app reads. */
@Serializable
data class GmailHistoryRecord(
    /** history id of the record */
    val id: String,
    /** messages added */
    val messagesAdded: List<GmailMessageAdded>? = null,
    /** label additions */
    val labelsAdded: List<GmailLabelsAdded>? = null,
    /** label removals, shaped like additions */
    val labelsRemoved: List<GmailLabelsAdded>? = null,
    /** messages deleted for good, shaped like additions */
    val messagesDeleted: List<GmailMessageAdded>? = null,
)

/** A page of `history.list`. */
@Serializable
data class GmailHistoryList(
    /** records on this page */
    val history: List<GmailHistoryRecord>? = null,
    /** token for the next page; null on the last */
    val nextPageToken: String? = null,
    /** the mailbox's current history id */
    val historyId: String? = null,
)

/** The `messages.attachments.get` response. */
@Serializable
data class GmailAttachment(
    /** content, base64url */
    val data: String = "",
    /** size in bytes */
    val size: Long = 0,
)

/** The `getProfile` response. */
@Serializable
data class GmailProfile(
    /** the account's address */
    val emailAddress: String = "",
    /** the mailbox's current history id */
    val historyId: String = "",
)

/** One send-as address from `settings.sendAs.list`. */
@Serializable
data class GmailSendAs(
    /** the address */
    val sendAsEmail: String,
    /** name sent with it */
    val displayName: String? = null,
    /** whether it is the account's own address */
    val isPrimary: Boolean = false,
    /** whether Gmail sends from it by default */
    val isDefault: Boolean = false,
    /** "accepted" once Gmail verified the alias; absent for the primary address */
    val verificationStatus: String? = null,
) {
    /** Whether Gmail lets the account send as this address (the server checks the same). */
    val usable: Boolean get() = isPrimary || verificationStatus == "accepted"
}

/** The `settings.sendAs.list` response. */
@Serializable
data class GmailSendAsList(
    /** every send-as address */
    val sendAs: List<GmailSendAs>? = null,
)

/** A Gmail draft: its id and message. */
@Serializable
data class GmailDraft(
    /** draft id */
    val id: String,
    /** the draft's message */
    val message: GmailMessage? = null,
)

/** A page of `drafts.list`. */
@Serializable
data class GmailDraftList(
    /** drafts on this page */
    val drafts: List<GmailDraft>? = null,
    /** token for the next page; null on the last */
    val nextPageToken: String? = null,
)

/** The thread a draft upload belongs to. */
@Serializable
internal data class DraftMessageRef(val threadId: String? = null)

/** The JSON part of a draft upload. */
@Serializable
internal data class DraftMetadata(val id: String? = null, val message: DraftMessageRef)

/** Gmail's JSON error body. */
@Serializable
internal data class GmailErrorBody(val error: GmailErrorDetail? = null)

/** The `error` object of Gmail's JSON error body. */
@Serializable
internal data class GmailErrorDetail(
    val message: String? = null,
    val errors: List<GmailErrorReason>? = null,
    val details: List<GmailErrorReason>? = null,
)

/** One entry of a Gmail error's `errors` or `details` list. */
@Serializable
internal data class GmailErrorReason(val reason: String? = null)

/** JSON settings for Gmail's responses and the device cache: unknown fields ignored, defaults kept. */
val GmailJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
