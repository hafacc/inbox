package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.gmail.GmailPartBody
import cc.hafa.inbox.core.gmail.GmailRawMessage
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.decodeBase64UrlText
import cc.hafa.inbox.core.mail.headerValue
import cc.hafa.inbox.core.mail.messageView
import cc.hafa.inbox.core.mail.parseAddress
import cc.hafa.inbox.core.mail.walkParts
import cc.hafa.inbox.core.search.SearchPlan
import cc.hafa.inbox.core.search.gmailQueryText
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.Base64

// Showing encrypted mail: spotting it, turning what decryptMessage gives into what the reader
// shows, and searching inside it (docs/pgp-contract.md "Reading", "Offline cache and search").

private const val PGP_BLOCK_START = "-----BEGIN PGP MESSAGE-----"
private const val SNIPPET_LENGTH = 200

/**
 * Whether a Gmail message is encrypted: PGP/MIME by its top type or one of its direct parts' (a
 * mailing list's footer wrapper), or inline PGP in a text part.
 */
fun isEncryptedMessage(message: GmailMessage): Boolean {
    val payload = message.payload ?: return false
    val encryptedType = { part: GmailMessagePart -> part.mimeType.equals("multipart/encrypted", ignoreCase = true) }
    if (encryptedType(payload) || payload.parts.orEmpty().any(encryptedType)) return true
    var inline = false
    walkParts(payload) { part, _ ->
        val data = part.body?.data
        if (!inline && part.mimeType.equals("text/plain", ignoreCase = true) && data != null) {
            inline = runCatching { decodeBase64UrlText(data) }.getOrDefault("").contains(PGP_BLOCK_START)
        }
    }
    return inline || message.snippet.orEmpty().contains(PGP_BLOCK_START)
}

/** How a message that arrived or left encrypted stands on this device. */
@Serializable
enum class LockState {
    /** decrypted */
    OPEN,

    /** this device has no unlocked key yet ("Unlock encrypted mail") */
    LOCKED,

    /** encrypted to a key this account doesn't have ("Can't open: encrypted to another key") */
    WRONG_KEY,

    /** damaged ("Can't open this encrypted message") */
    MALFORMED,
}

/** What the thread list shows for an encrypted thread once decrypted: its subject and the start of its newest text. */
@Serializable
data class DecryptedPreview(
    /** decrypted subject */
    val subject: String,
    /** start of the newest decrypted text */
    val snippet: String,
    /** when the newest message arrived, epoch milliseconds */
    val date: Long = 0,
)

/** The lock and signature badge of an encrypted message. */
@Serializable
data class MessageLock(
    /** whether it could be read */
    val state: LockState,
    /** the signature result once open: `valid`, `invalid`, `unknown-key` or `unsigned` */
    val signature: String? = null,
    /** [DecryptedMessage.mismatchedHeaders]: address headers shown from outside because the signature doesn't vouch for the inside */
    val mismatched: List<String> = emptyList(),
)

/**
 * The warning shown under the sender when the message's inner address headers were set aside
 * because its signature doesn't vouch for them ([MessageLock.mismatched]), or null.
 */
fun mismatchWarning(lock: MessageLock): String? =
    if (lock.mismatched.isEmpty()) null else "The sender inside this encrypted message doesn't match its signature; showing the outside one."

private val RFC2231_VALUE = Regex("^([^']*)'[^']*'(.*)$")

private fun partFilename(entity: MimeEntity): String? {
    val disposition = parseContentType(header(entity, "Content-Disposition"))
    val raw = disposition.params["filename"] ?: entity.params["name"] ?: return null
    val extended = RFC2231_VALUE.find(raw)
    return if (extended != null) {
        val (charset, text) = extended.destructured
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), charset.ifEmpty { "UTF-8" }) }.getOrDefault(text)
    } else {
        decodeHeaderValue(raw)
    }
}

private val DROPPED_HEADERS = setOf("content-transfer-encoding")

/** A decrypted MIME entity as Gmail's part tree, bodies decoded and inline, so the reader shows it like any message. */
fun decryptedPayload(entity: MimeEntity, partId: String = ""): GmailMessagePart {
    val multipart = entity.type.startsWith("multipart/")
    val data = if (multipart) null else decodedBody(entity)
    return GmailMessagePart(
        partId = partId,
        mimeType = entity.type,
        filename = if (multipart) "" else partFilename(entity).orEmpty(),
        headers = entity.headers.filter { it.name.lowercase() !in DROPPED_HEADERS }.map { GmailHeader(it.name, decodeHeaderValue(it.value)) },
        body = GmailPartBody(
            size = data?.size?.toLong() ?: 0,
            data = data?.let { Base64.getUrlEncoder().encodeToString(it) },
        ),
        parts = entity.parts.mapIndexed { index, part -> decryptedPayload(part, if (partId.isEmpty()) "$index" else "$partId.$index") },
    )
}

private val WHITESPACE = Regex("""\s+""")

/** The start of a message's text, for the collapsed header, the thread list and notifications. */
fun snippetOf(view: MessageView): String {
    val text = view.body.text ?: view.body.html?.let { Jsoup.parse(it).text() } ?: ""
    return text.replace(WHITESPACE, " ").trim().take(SNIPPET_LENGTH)
}

private val PROTECTED_TO_HEADER = mapOf(
    "from" to "From",
    "to" to "To",
    "cc" to "Cc",
    "bcc" to "Bcc",
    "reply-to" to "Reply-To",
    "subject" to "Subject",
    "date" to "Date",
    "message-id" to "Message-ID",
    "in-reply-to" to "In-Reply-To",
    "references" to "References",
)

/**
 * A message with its decrypted content in place of the encrypted body, and the protected headers
 * (subject, people, threading) in place of the outer ones; Bcc and the rest stay as sent.
 */
fun decryptedMessage(message: GmailMessage, decrypted: DecryptedMessage): GmailMessage {
    val outerHeaders = message.payload?.headers.orEmpty()
    val inner = decryptedPayload(decrypted.entity)
    val protected = decrypted.protectedHeaders.orEmpty()
    val replaced = protected.keys.mapNotNull { PROTECTED_TO_HEADER[it]?.lowercase() }.toSet()
    val headers = outerHeaders.filter { it.name.lowercase() !in replaced && !it.name.lowercase().startsWith("content-") } +
        protected.mapNotNull { (name, value) -> PROTECTED_TO_HEADER[name]?.let { GmailHeader(it, value) } } +
        inner.headers.orEmpty().filter { it.name.lowercase().startsWith("content-") }
    return message.copy(payload = inner.copy(headers = headers))
}

/** A message as the reader shows it once decrypted ([decryptedMessage]), with its lock badge. */
fun decryptedView(message: GmailMessage, decrypted: DecryptedMessage): MessageView {
    val view = messageView(decryptedMessage(message, decrypted))
    return view.copy(snippet = snippetOf(view), lock = MessageLock(LockState.OPEN, decrypted.signature.status.wire, decrypted.mismatchedHeaders))
}

private fun messageFromRaw(raw: GmailRawMessage, outer: MimeEntity): GmailMessage =
    GmailMessage(
        id = raw.id,
        threadId = raw.threadId,
        labelIds = raw.labelIds,
        payload = GmailMessagePart(
            mimeType = outer.type,
            headers = outer.headers.map { GmailHeader(it.name, decodeHeaderValue(it.value)) },
        ),
    )

/**
 * A message fetched as `raw` ([bytes] its decoded text), decrypted and as the reader shows it,
 * parsing it once for both its outer headers and its content. Throws [PgpException].
 */
fun decryptedRawView(raw: GmailRawMessage, bytes: ByteArray, decryptionKeys: List<String>, verificationKeys: List<String>): MessageView {
    val outer = parseMime(bytesToBinary(bytes))
    return decryptedView(messageFromRaw(raw, outer), decryptMessage(outer, decryptionKeys, verificationKeys))
}

/** A message that couldn't be decrypted here, shown with its reason instead of the ciphertext. */
fun lockedView(message: GmailMessage, state: LockState): MessageView {
    val view = messageView(message)
    return view.copy(
        snippet = "",
        body = view.body.copy(html = null, text = null, attachments = emptyList(), inline = emptyList(), calendar = null),
        lock = MessageLock(state),
    )
}

/** The Gmail search that finds encrypted mail: PGP/MIME's `encrypted.asc` or an inline block. */
const val ENCRYPTED_MAIL_QUERY = "{filename:encrypted.asc \"BEGIN PGP MESSAGE\"}"

/**
 * The second query an online search runs for encrypted mail: the user's operators without the
 * free-text words, plus [ENCRYPTED_MAIL_QUERY]. Null when the search has no free text (Gmail's
 * own search already answers it).
 */
fun encryptedSearchQuery(plan: SearchPlan): String? {
    if (plan.terms.include.isEmpty()) return null
    val operators = gmailQueryText(plan.chips, "")
    return listOf(operators, ENCRYPTED_MAIL_QUERY).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Whether a decrypted message holds every free-text term of a search and none it excludes,
 * case-insensitive, in its subject, text or addresses.
 */
fun matchesDecrypted(plan: SearchPlan, view: MessageView): Boolean {
    val terms = plan.terms
    if (terms.include.isEmpty()) return false
    val haystack = buildString {
        append(view.subject).append('\n')
        append(view.body.text.orEmpty()).append('\n')
        view.body.html?.let { append(Jsoup.parse(it).text()) }
        for (person in view.to + view.cc + view.from) append('\n').append(person.name).append(' ').append(person.email)
    }.replace(WHITESPACE, " ").lowercase()
    return terms.include.all { it in haystack } && terms.exclude.none { it in haystack }
}

/** The values of every `Autocrypt` header of a message (format `full`). */
fun autocryptValues(message: GmailMessage): List<String> =
    message.payload?.headers.orEmpty().filter { it.name.equals("Autocrypt", ignoreCase = true) }.map { it.value }

/** The sender's address of a message, lower-cased, or null. */
fun senderAddress(message: GmailMessage): String? =
    headerValue(message.payload?.headers, "From")?.let { parseAddress(it).email }?.takeIf { it.isNotEmpty() }
