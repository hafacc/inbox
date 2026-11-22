package cc.hafa.inbox.core.mail

// Reading Gmail's MIME trees, as shared/src/mime.ts does; both run shared/testdata/mime-fixtures.json.
// Charsets follow the WHATWG Encoding Standard (see [decodeText]).

import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.pgp.fromBase64
import kotlinx.serialization.Serializable

/** A person on a message: display name (may be empty) and address. */
@Serializable
data class Person(
    /** display name; may be empty */
    val name: String,
    /** address */
    val email: String,
)

/** A downloadable attachment of a message. */
@Serializable
data class Attachment(
    /** the part's place in the message's tree, which unlike [attachmentId] stays the same on every fetch */
    val partId: String,
    /** file name */
    val filename: String,
    /** MIME type, lower-cased */
    val mimeType: String,
    /** size in bytes, as Gmail reports it */
    val size: Long,
    /** Gmail attachment id to fetch it by */
    val attachmentId: String?,
    /** base64url data when Gmail sent it inline with the message */
    val data: String?,
)

/** An inline part referenced from the HTML body by `cid:`. */
@Serializable
data class InlinePart(
    /** the part's place in the message's tree, which unlike [attachmentId] stays the same on every fetch */
    val partId: String,
    /** Content-ID without angle brackets */
    val contentId: String,
    /** MIME type, lower-cased */
    val mimeType: String,
    /** file name, or "" when it has none */
    val filename: String,
    /** size in bytes, as Gmail reports it */
    val size: Long,
    /** Gmail attachment id to fetch it by */
    val attachmentId: String?,
    /** base64url data when Gmail sent it inline with the message */
    val data: String?,
)

/** A message's iCalendar part (an invite or an answer to one), to read or fetch. */
@Serializable
data class CalendarPart(
    /** the part's place in the message's tree, which unlike [attachmentId] stays the same on every fetch */
    val partId: String,
    /** base64url data when Gmail sent it inline with the message */
    val data: String?,
    /** Gmail attachment id to fetch it by */
    val attachmentId: String?,
    /** Content-Type charset label */
    val charset: String,
)

/** The readable parts of one message. */
@Serializable
data class MessageBody(
    /** first `text/html` body */
    val html: String?,
    /** first `text/plain` body */
    val text: String?,
    /** downloadable attachments */
    val attachments: List<Attachment>,
    /** parts the HTML references by `cid:` */
    val inline: List<InlinePart>,
    /** the invite part, or null */
    val calendar: CalendarPart? = null,
)

/** Decode Gmail's base64url (with or without padding, `-` and `_`) into bytes. */
fun decodeBase64Url(data: String): ByteArray = fromBase64(data)

/** Decode base64url text in the given charset (see [decodeText]). */
fun decodeBase64UrlText(data: String, charset: String = "utf-8"): String = decodeText(decodeBase64Url(data), charset)

/** Case-insensitive header lookup; the first match wins. */
fun headerValue(headers: List<GmailHeader>?, name: String): String? =
    headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

private val CHARSET_RE = Regex("""charset\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE)

/** The `charset` parameter of a Content-Type value, lower-cased; UTF-8 when absent. */
fun charsetOf(contentType: String?): String =
    CHARSET_RE.find(contentType ?: "")?.groupValues?.get(1)?.lowercase() ?: "utf-8"

/** Visit every part of a MIME tree depth-first, parents before children. */
fun walkParts(root: GmailMessagePart, visit: (part: GmailMessagePart, ancestors: List<GmailMessagePart>) -> Unit) {
    fun walk(part: GmailMessagePart, ancestors: List<GmailMessagePart>) {
        visit(part, ancestors)
        for (child in part.parts.orEmpty()) walk(child, ancestors + part)
    }
    walk(root, emptyList())
}

private val ATTACHMENT_DISPOSITION_RE = Regex("""^\s*attachment""", RegexOption.IGNORE_CASE)

private fun isAttachmentDisposition(part: GmailMessagePart): Boolean =
    ATTACHMENT_DISPOSITION_RE.containsMatchIn(headerValue(part.headers, "Content-Disposition") ?: "")

private fun contentIdOf(part: GmailMessagePart): String? {
    val raw = headerValue(part.headers, "Content-ID") ?: return null
    return raw.trim().removePrefix("<").removeSuffix(">").ifEmpty { null }
}

private fun partText(part: GmailMessagePart): String {
    val data = part.body?.data ?: return ""
    return decodeBase64UrlText(data, charsetOf(headerValue(part.headers, "Content-Type")))
}

private val CALENDAR_TYPES = setOf("text/calendar", "application/ics")

/**
 * Pull the HTML and plain-text bodies, attachments, `cid:` parts and invite out of a message.
 *
 * The first `text/html` and first `text/plain` that aren't attachments win (so the main
 * `multipart/alternative` beats a forwarded message further down). A non-text part with a
 * Content-ID that isn't marked as an attachment is inline; any other part with a filename or an
 * attachment disposition is an attachment. The invite is the first `text/calendar` part that isn't
 * a file, else the first calendar file (`text/calendar` or `application/ics`), which also lists as
 * an attachment; a calendar part with neither data nor an attachment id is skipped.
 */
fun extractBody(payload: GmailMessagePart): MessageBody {
    var html: String? = null
    var text: String? = null
    val attachments = mutableListOf<Attachment>()
    val inline = mutableListOf<InlinePart>()
    var calendar: CalendarPart? = null
    var calendarFile: CalendarPart? = null
    walkParts(payload) { part, _ ->
        val mimeType = part.mimeType.lowercase()
        if (mimeType.startsWith("multipart/")) return@walkParts
        val partId = part.partId.orEmpty()
        val filename = part.filename.orEmpty()
        val asAttachment = isAttachmentDisposition(part)
        val data = part.body?.data
        val attachmentId = part.body?.attachmentId
        if (mimeType in CALENDAR_TYPES) {
            val named = filename.isNotEmpty() || asAttachment
            if (data != null || attachmentId != null) {
                val found = CalendarPart(partId, data, attachmentId, charsetOf(headerValue(part.headers, "Content-Type")))
                if (!named && mimeType == "text/calendar") {
                    if (calendar == null) calendar = found
                } else if (calendarFile == null) {
                    calendarFile = found
                }
            }
            if (!named) return@walkParts
        }
        val contentId = contentIdOf(part)
        val size = part.body?.size ?: 0
        if (contentId != null && !asAttachment && !mimeType.startsWith("text/")) {
            inline += InlinePart(partId, contentId, mimeType, filename, size, attachmentId, data)
        } else if (filename.isNotEmpty() || asAttachment) {
            attachments += Attachment(partId, filename.ifEmpty { "attachment" }, mimeType, size, attachmentId, data)
        } else if (mimeType == "text/html" && html == null) {
            html = partText(part)
        } else if (mimeType == "text/plain" && text == null) {
            text = partText(part)
        }
    }
    return MessageBody(html, text, attachments, inline, calendar ?: calendarFile)
}

/**
 * Split an address list into one string per mailbox, on commas outside quoted strings, comments
 * and angle brackets. A group (`Friends: a@x.example.com, b@x.example.com;`) gives its members, without its name;
 * a colon after an `@` doesn't start one.
 */
private fun splitAddresses(value: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    var comment = 0
    var angled = false
    for (character in value) {
        var keep = true
        when {
            escaped -> escaped = false
            (quoted || comment > 0) && character == '\\' -> escaped = true
            quoted -> if (character == '"') quoted = false
            comment > 0 -> if (character == '(') comment++ else if (character == ')') comment--
            angled -> if (character == '>') angled = false
            character == '"' -> quoted = true
            character == '(' -> comment = 1
            character == '<' -> angled = true
            character == ':' && '@' !in current -> {
                current.clear()
                keep = false
            }
            character == ',' || character == ';' -> {
                out += current.toString()
                current.clear()
                keep = false
            }
        }
        if (keep) current.append(character)
    }
    out += current.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

private val LINE_BREAK_RE = Regex("""\s*[\r\n]+\s*""")

/** Text with each run of whitespace that breaks a line as a plain space, and trimmed. */
private fun oneLine(text: String): String = text.replace(LINE_BREAK_RE, " ").trim()

/**
 * Parse one mailbox such as `"Chen, Maya" <maya@example.com>`, `maya@example.com` or
 * `maya@example.com (Maya Chen)`.
 *
 * The address is what the last `<…>` holds, else everything outside quotes and comments, as
 * [addressKey] gives it. The name is the text before the `<…>` with its quotes and backslash
 * escapes undone, else the first comment; line breaks in it read as spaces.
 */
fun parseAddress(value: String): Person {
    val phrase = StringBuilder()
    var angle: StringBuilder? = null
    var firstComment: String? = null
    val commentText = StringBuilder()
    var quoted = false
    var escaped = false
    var comment = 0
    var angled = false
    for (character in value) {
        when {
            escaped -> {
                escaped = false
                if (comment > 0) commentText.append(character) else phrase.append(character)
            }
            (quoted || comment > 0) && character == '\\' -> escaped = true
            quoted -> if (character == '"') quoted = false else phrase.append(character)
            comment > 0 -> {
                if (character == '(') comment++ else if (character == ')') comment--
                if (comment == 0) {
                    if (firstComment == null) firstComment = commentText.toString()
                    commentText.clear()
                } else {
                    commentText.append(character)
                }
            }
            angled -> if (character == '>') angled = false else angle?.append(character)
            character == '"' -> quoted = true
            character == '(' -> comment = 1
            character == '<' -> {
                angled = true
                angle = StringBuilder()
            }
            else -> phrase.append(character)
        }
    }
    if (comment > 0 && firstComment == null) firstComment = commentText.toString()
    val commentName = oneLine(firstComment.orEmpty())
    val address = angle
    return if (address != null) {
        Person(oneLine(phrase.toString()).ifEmpty { commentName }, addressKey(address.toString()))
    } else {
        Person(commentName, addressKey(phrase.toString()))
    }
}

/** Parse a To/Cc/From header into people. */
fun parseAddressList(value: String?): List<Person> =
    if (value.isNullOrEmpty()) {
        emptyList()
    } else {
        splitAddresses(value).map(::parseAddress).filter { it.email.isNotEmpty() || it.name.isNotEmpty() }
    }

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

private val ENTITY_RE = Regex("""&(?:#[xX]([0-9a-fA-F]+)|#(\d+)|([a-zA-Z]+));""")

// Past 8 significant digits, a reference is past U+10FFFF in either radix.
private const val MAX_REFERENCE_DIGITS = 8
private val C1_CONTROLS = 0x80L..0x9FL

private fun numericReference(digits: String, radix: Int): String {
    val significant = digits.trimStart('0')
    val code = if (significant.length > MAX_REFERENCE_DIGITS) Long.MAX_VALUE else significant.ifEmpty { "0" }.toLong(radix)
    return when {
        code == 0L || code > Character.MAX_CODE_POINT || code in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> "�"
        code in C1_CONTROLS -> windows1252High(code.toInt()).toString()
        else -> String(Character.toChars(code.toInt()))
    }
}

/**
 * Decode the HTML entities Gmail leaves in snippets: a few named ones (others stay as written) and
 * numeric ones, which read as HTML reads them: 0, surrogates and anything past U+10FFFF as U+FFFD,
 * and 0x80–0x9F as windows-1252.
 */
fun decodeEntities(value: String): String =
    ENTITY_RE.replace(value) { match ->
        val (hex, decimal, name) = match.destructured
        when {
            hex.isNotEmpty() -> numericReference(hex, 16)
            decimal.isNotEmpty() -> numericReference(decimal, 10)
            else -> NAMED_ENTITIES[name.lowercase()] ?: match.value
        }
    }
