package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A file sent with a message. */
open class MimeAttachment(
    /** file name shown to the recipient */
    val filename: String,
    /** content type, e.g. `application/pdf` */
    val mimeType: String,
    /** contents */
    val data: ByteArray,
)

/** An image shown inside the HTML body, referenced there as `cid:<contentId>`. */
class MimeInlineImage(
    filename: String,
    mimeType: String,
    data: ByteArray,
    /** Content-ID without angle brackets */
    val contentId: String,
) : MimeAttachment(filename, mimeType, data)

/** Everything a message is built from; mirrors web's `ComposeInput`. */
data class ComposeInput(
    /** sender */
    val from: Person,
    /** `To` */
    val to: List<Person>,
    /** `Cc` */
    val cc: List<Person>,
    /** `Bcc`, kept in the draft; Gmail and the server drop it from what recipients get */
    val bcc: List<Person>,
    /** decoded subject */
    val subject: String,
    /** body, markdown: the plain-text part */
    val markdown: String,
    /** signature, markdown; null for none */
    val signature: String?,
    /** `In-Reply-To` id, or null when not a reply */
    val inReplyTo: String?,
    /** `References` ids, oldest first */
    val references: List<String>,
    /** images referenced from the body as `cid:` */
    val inlineImages: List<MimeInlineImage>,
    /** attachments */
    val attachments: List<MimeAttachment>,
    /** extra headers as name and value, already encoded, placed before `MIME-Version` */
    val extraHeaders: List<Pair<String, String>>,
    /** unique token for multipart boundaries; boundaries are `=_<token>_<n>` */
    val boundary: String,
    /** the message a reply quotes, after the signature; null for none */
    val quote: QuotedMessage? = null,
    /** an iCalendar part after the HTML, e.g. an RSVP; null for none */
    val calendar: CalendarPart? = null,
    /** body as the editor's HTML ([richToHtml] of what it holds); null to build it from [markdown] */
    val html: String? = null,
)

/** An iCalendar part sent beside the text, e.g. an RSVP (`buildReply`). */
@Serializable
data class CalendarPart(
    /** iTIP method, e.g. `REPLY` */
    val method: String,
    /** the iCalendar text, CRLF line ends */
    val content: String,
)

/** A header value that can't be written as it is: it holds a line break or another control character. */
class InvalidHeaderError(
    /** the header's name */
    val header: String,
) : IllegalArgumentException("The $header header holds a control character")

/** What the user is told when building a message failed on an address or a header, or null when [error] is neither. */
fun buildProblem(error: Throwable): String? =
    addressProblem(error) ?: (error as? InvalidHeaderError)?.let { "The message's ${it.header} header can't be sent safely." }

// Any control character but tab, which may stand in a header value.
private fun hasControl(text: String): Boolean = text.any { it.isISOControl() && it != '\t' }

/** [foldHeader] of [name] and [value], refusing a value with a control character other than tab. */
private fun header(name: String, value: String): String {
    if (hasControl(name) || hasControl(value)) throw InvalidHeaderError(name)
    return foldHeader(name, value)
}

private class Part(val headers: List<String>, val body: String)

private fun textPart(subtype: String, content: String) = Part(
    listOf("Content-Type: text/$subtype; charset=UTF-8", "Content-Transfer-Encoding: quoted-printable"),
    quotedPrintable(content),
)

private fun calendarPart(calendar: CalendarPart) = Part(
    listOf(header("Content-Type", "text/calendar; charset=UTF-8; method=${calendar.method}"), "Content-Transfer-Encoding: base64"),
    base64Lines(calendar.content.toByteArray(Charsets.UTF_8)),
)

private fun quoted(value: String): String = "\"${value.replace(QUOTE_ESCAPES) { "\\" + it.value }}\""

private fun nameParameter(filename: String): String =
    if (isPrintableAscii(filename)) "name=${quoted(filename)}" else "name=\"${encodedWords(filename).joinToString(" ")}\""

private fun filenameParameter(filename: String): String =
    if (isPrintableAscii(filename)) "filename=${quoted(filename)}" else "filename*=UTF-8''${percentEncoded(filename)}"

private fun filePart(file: MimeAttachment, disposition: String, contentId: String?): Part {
    val headers = mutableListOf(
        header("Content-Type", "${file.mimeType}; ${nameParameter(file.filename)}"),
        header("Content-Disposition", "$disposition; ${filenameParameter(file.filename)}"),
    )
    if (contentId != null) headers += header("Content-ID", "<$contentId>")
    headers += "Content-Transfer-Encoding: base64"
    return Part(headers, base64Lines(file.data))
}

private fun multipart(subtype: String, boundary: String, parts: List<Part>): Part {
    val related = if (subtype == "related") "; type=\"multipart/alternative\"" else ""
    val lines = mutableListOf<String>()
    for (part in parts) {
        lines += "--$boundary"
        lines += part.headers
        lines += ""
        lines += part.body
    }
    lines += "--$boundary--"
    return Part(
        listOf(header("Content-Type", "multipart/$subtype; boundary=\"$boundary\"$related")),
        lines.joinToString(CRLF),
    )
}

/**
 * Build a message as RFC 5322 text with CRLF line breaks, ready for Gmail's `drafts.create`.
 *
 * Structure, outermost first, each level only when needed: `multipart/mixed` (attachments) →
 * `multipart/related` (inline images) → `multipart/alternative` (`text/plain`, `text/html`, then
 * `text/calendar` in base64 when [ComposeInput.calendar] is set).
 * Text parts are UTF-8 quoted-printable; files are base64 in 76-character lines. No `Date` or
 * `Message-ID`: Gmail adds both, and the server sets them for mail sent through Resend. Pinned to
 * web's `buildMime` by `shared/testdata/compose-fixtures.json`.
 *
 * Throws [InvalidAddressError] for an address that can't be written, and [InvalidHeaderError] when
 * any header value (In-Reply-To, References and extra headers included) holds a line break or
 * another control character but tab.
 */
fun buildMime(input: ComposeInput): String {
    val (html, text) = bodyParts(input.markdown, input.signature, input.quote, input.html)
    var counter = 0
    fun nextBoundary() = "=_${input.boundary}_${counter++}"
    val levels = buildList {
        if (input.attachments.isNotEmpty()) add("mixed")
        if (input.inlineImages.isNotEmpty()) add("related")
    }
    val outer = levels.map { nextBoundary() }
    val alternatives = listOfNotNull(textPart("plain", text), textPart("html", html), input.calendar?.let(::calendarPart))
    var body = multipart("alternative", nextBoundary(), alternatives)
    for (level in levels.indices.reversed()) {
        body = if (levels[level] == "related") {
            multipart("related", outer[level], listOf(body) + input.inlineImages.map { filePart(it, "inline", it.contentId) })
        } else {
            multipart("mixed", outer[level], listOf(body) + input.attachments.map { filePart(it, "attachment", null) })
        }
    }

    val headers = mutableListOf(header("From", formatAddressList(listOf(input.from))))
    if (input.to.isNotEmpty()) headers += header("To", formatAddressList(input.to))
    if (input.cc.isNotEmpty()) headers += header("Cc", formatAddressList(input.cc))
    if (input.bcc.isNotEmpty()) headers += header("Bcc", formatAddressList(input.bcc))
    headers += header("Subject", encodeHeaderText(input.subject))
    input.inReplyTo?.let { headers += header("In-Reply-To", it) }
    if (input.references.isNotEmpty()) headers += header("References", input.references.joinToString(" "))
    for ((name, value) in input.extraHeaders) headers += header(name, value)
    headers += "MIME-Version: 1.0"
    headers += body.headers
    return (headers + "" + body.body).joinToString(CRLF) + CRLF
}

/** The largest message Gmail takes as a draft, in bytes of the built message. */
const val MAX_MESSAGE_BYTES = 35L * 1024 * 1024

/** Bytes a file adds to a built message once base64-encoded in lines. */
fun encodedSize(bytes: Long): Long {
    val base64Length = (bytes + 2) / 3 * 4
    return base64Length + base64Length / MAX_BODY_LINE * 2
}

/**
 * All of [input]'s bytes, read in chunks, or null as soon as they'd be more than a message can
 * carry once encoded ([encodedSize] over [maxEncodedBytes]), so a huge file is never read whole.
 */
fun readAttachment(input: InputStream, maxEncodedBytes: Long = MAX_MESSAGE_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var count = input.read(buffer)
    while (count >= 0) {
        out.write(buffer, 0, count)
        if (encodedSize(out.size().toLong()) > maxEncodedBytes) return null
        count = input.read(buffer)
    }
    return out.toByteArray()
}
