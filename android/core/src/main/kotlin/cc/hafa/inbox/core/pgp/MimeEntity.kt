package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.CRLF
import cc.hafa.inbox.core.mail.decodeText
import java.util.Base64
import java.util.regex.Matcher

// A small MIME reader for decrypted and encrypted messages; a port of shared/src/pgp/mime.ts.
// Works on binary strings (one character per byte) so signed parts can be checked byte for byte.

/** One header field: name as written, value unfolded and trimmed, still encoded. */
data class MimeHeader(
    /** field name as written */
    val name: String,
    /** value, unfolded and trimmed, still encoded */
    val value: String,
)

/**
 * One MIME entity: a whole message, or a part of one.
 *
 * Every entity of a tree shares the parsed text; [raw] and [rawBody] are cut from it when read.
 */
class MimeEntity internal constructor(
    /** header fields in order */
    val headers: List<MimeHeader>,
    /** media type, lower-case (`text/plain` when absent) */
    val type: String,
    /** Content-Type parameters, names lower-case */
    val params: Map<String, String>,
    /** child parts of a multipart, else empty */
    val parts: List<MimeEntity>,
    private val source: String,
    private val start: Int,
    private val bodyStart: Int,
    private val end: Int,
) {
    /** the body as sent, a binary string, before transfer decoding */
    val rawBody: String get() = source.substring(bodyStart, end)

    /** the whole entity as sent (headers and body), a binary string */
    val raw: String get() = source.substring(start, end)
}

/** The value of the first header with this name, case-insensitive. */
fun header(entity: MimeEntity, name: String): String? =
    entity.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

/** A blank line ending a header block, CRLF or LF. */
internal val BLANK_LINE = Regex("""\r?\n\r?\n""")

/** A line break, CRLF or LF. */
internal val LINE_BREAK = Regex("""\r?\n""")
private const val MAX_DEPTH = 20

/** One header field with its lines as written (folds joined by CRLF) and its unfolded value. */
internal class HeaderField(val name: String, val text: String, val value: String)

/** Header fields of a header block, unfolded. */
internal fun parseHeaderFields(headerText: String): List<HeaderField> {
    val fields = mutableListOf<HeaderField>()
    for (line in headerText.split(LINE_BREAK)) {
        val last = fields.lastOrNull()
        if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) {
            fields[fields.lastIndex] = HeaderField(last.name, "${last.text}$CRLF$line", "${last.value} ${line.trim()}".trim())
        } else {
            val colon = line.indexOf(':')
            if (colon > 0) fields += HeaderField(line.substring(0, colon).trim(), line, line.substring(colon + 1).trim())
        }
    }
    return fields
}

/** A parsed Content-Type: media type and parameters. */
data class ContentType(
    /** media type, lower-case */
    val type: String,
    /** parameters, names lower-case */
    val params: Map<String, String>,
)

private val PARAMETER = Regex("""\s*([^=;\s]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]*))\s*;?""")
private val QUOTED_PAIR = Regex("""\\(.)""")
private val SECTION = Regex("""^([^*]+)\*(\d+)\*?$""")

/** A Content-Type value as type and parameters; quoted values unquoted, RFC 2231 `*` sections joined. */
fun parseContentType(value: String?): ContentType {
    val params = linkedMapOf<String, String>()
    if (value == null) return ContentType("text/plain", params)
    val semicolon = value.indexOf(';')
    val type = (if (semicolon < 0) value else value.substring(0, semicolon)).trim().lowercase()
    val rest = if (semicolon < 0) "" else value.substring(semicolon + 1)
    val continued = linkedMapOf<String, MutableMap<Int, String>>()
    for (match in PARAMETER.findAll(rest)) {
        val name = match.groupValues[1].lowercase()
        val quoted = match.groups[2]?.value
        val text = quoted?.replace(QUOTED_PAIR, "$1") ?: (match.groups[3]?.value ?: "").trim()
        val section = SECTION.find(name)
        if (section != null) {
            continued.getOrPut(section.groupValues[1]) { sortedMapOf() }[section.groupValues[2].toInt()] = text
        } else {
            params[name.removeSuffix("*")] = text
        }
    }
    for ((name, pieces) in continued) params[name] = pieces.toSortedMap().values.joinToString("")
    return ContentType(type.ifEmpty { "text/plain" }, params)
}

private fun matcher(regex: Regex, source: String, start: Int, end: Int): Matcher =
    regex.toPattern().matcher(source).region(start, end)

private fun parseMime(source: String, start: Int, end: Int, depth: Int): MimeEntity {
    val blank = matcher(BLANK_LINE, source, start, end)
    val found = blank.find()
    val leadingBreak = when {
        source.startsWith("\r\n", start) && start + 2 <= end -> 2
        source.startsWith("\n", start) && start < end -> 1
        else -> 0
    }
    val (headerEnd, bodyStart) = when {
        !found && start < end && source[start] in "\r\n" -> start to start + leadingBreak
        !found -> end to end
        leadingBreak > 0 -> start to start + leadingBreak
        else -> blank.start() to blank.end()
    }
    val headers = parseHeaderFields(source.substring(start, headerEnd)).map { MimeHeader(it.name, it.value) }
    val (type, params) = parseContentType(headers.firstOrNull { it.name.equals("content-type", ignoreCase = true) }?.value)
    val boundary = params["boundary"]
    val parts = if (type.startsWith("multipart/") && !boundary.isNullOrEmpty() && depth < MAX_DEPTH) {
        multipartRanges(source, bodyStart, end, boundary).map { (partStart, partEnd) -> parseMime(source, partStart, partEnd, depth + 1) }
    } else {
        emptyList()
    }
    return MimeEntity(headers, type, params, parts, source, start, bodyStart, end)
}

/** Parse a message or part (a binary string) into a tree. */
fun parseMime(raw: String): MimeEntity = parseMime(raw, 0, raw.length, 0)

/**
 * The parts of the multipart body source[start, end), each exactly as sent: the text after a
 * delimiter line's line break up to (not including) the line break before the next delimiter
 * (RFC 2046 §5.1.1).
 */
private fun multipartRanges(source: String, start: Int, end: Int, boundary: String): List<Pair<Int, Int>> {
    val delimiter = matcher(Regex("""(?:^|\r?\n)--${Regex.escape(boundary)}(--)?[ \t]*(?:\r?\n|$)"""), source, start, end)
    val parts = mutableListOf<Pair<Int, Int>>()
    var partStart: Int? = null
    while (delimiter.find()) {
        partStart?.let { parts += it to delimiter.start() }
        if (delimiter.group(1) != null) return parts
        partStart = delimiter.end()
    }
    partStart?.let { parts += it to end }
    return parts
}

private val SOFT_BREAK = Regex("""=\r?\n""")
private val QP_ESCAPE = Regex("""=([0-9A-Fa-f]{2})""")

/** A part's body with its Content-Transfer-Encoding undone, as bytes. */
fun decodedBody(entity: MimeEntity): ByteArray =
    when (header(entity, "Content-Transfer-Encoding")?.trim()?.lowercase()) {
        "base64" -> lenientBase64(entity.rawBody)
        "quoted-printable" -> binaryToBytes(
            entity.rawBody.replace(SOFT_BREAK, "").replace(QP_ESCAPE) { it.groupValues[1].toInt(16).toChar().toString() },
        )
        else -> binaryToBytes(entity.rawBody)
    }

/** A text part's body as a string, transfer encoding and charset undone. */
fun decodedText(entity: MimeEntity): String = decodeText(decodedBody(entity), entity.params["charset"] ?: "utf-8")

private val ADJACENT_WORDS = Regex("""(=\?[^?]+\?[BbQq]\?[^?]*\?=)\s+(?==\?)""")
private val ENCODED_WORD = Regex("""=\?([^?*]+)(?:\*[^?]*)?\?([BbQq])\?([^?]*)\?=""")

/** Decode RFC 2047 encoded words (`=?charset?B|Q?…?=`) in a header value; malformed ones stay as written. */
fun decodeHeaderValue(value: String): String =
    value.replace(ADJACENT_WORDS, "$1").replace(ENCODED_WORD) { match ->
        val (charset, encoding, text) = match.destructured
        val bytes = if (encoding.uppercase() == "B") {
            atobBytes(text)
        } else {
            binaryToBytes(text.replace('_', ' ').replace(QP_ESCAPE) { it.groupValues[1].toInt(16).toChar().toString() })
        }
        if (bytes == null) match.value else decodeText(bytes, charset)
    }

private val ATOB_WHITESPACE = Regex("[\t\n\u000c\r ]")
private val BASE64_ALPHABET = Regex("^[A-Za-z0-9+/]*$")

/** Base64 as JavaScript's `atob` reads it (padding optional), or null where `atob` throws. */
private fun atobBytes(text: String): ByteArray? {
    var clean = text.replace(ATOB_WHITESPACE, "")
    if (clean.length % 4 == 0) clean = clean.removeSuffix("=").removeSuffix("=")
    if (clean.length % 4 == 1 || !BASE64_ALPHABET.matches(clean)) return null
    return Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
}

/** Every entity in the tree, depth first, the root first. */
fun walk(entity: MimeEntity): List<MimeEntity> = listOf(entity) + entity.parts.flatMap(::walk)
