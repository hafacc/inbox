package cc.hafa.inbox.core.compose

/** Separator line between body and signature in the plain-text part ("dash dash space"). */
const val SIGNATURE_SEPARATOR = "-- "

/** The two readable versions of a message body. */
data class BodyParts(
    /** `text/html` part */
    val html: String,
    /** `text/plain` part, LF line breaks */
    val text: String,
)

/**
 * Markdown as an HTML fragment, rendered from [markdownToText] so both parts start from the same
 * trimmed lines: read as rich text by [markdownToRich] (bare web addresses linked with [bareLinks],
 * as for a signature; not for the composer's own markdown, which writes its links out) and written
 * by [richToHtml].
 */
fun markdownToHtml(source: String, bareLinks: Boolean = true): String =
    richToHtml(markdownToRich(markdownToText(source), bareLinks))

private val TRAILING_BLANKS = Regex("[ \t]+$")
private val EDGE_NEWLINES = Regex("^\n+|\n+$")

/** Markdown as its plain-text alternative: the source as typed, LF breaks, trailing spaces and blank lines trimmed. */
fun markdownToText(source: String): String =
    normalizeNewlines(source)
        .split("\n")
        .joinToString("\n") { it.replace(TRAILING_BLANKS, "") }
        .replace(EDGE_NEWLINES, "")

/**
 * The HTML and plain-text parts of a body with an optional signature (both markdown) and, for a
 * reply, the quoted original.
 *
 * HTML: `<div dir="auto">` + body ([bodyHtml], the editor's HTML of it, when given) + `</div>`,
 * then `<div class="signature">` + signature + `</div>`,
 * then for a reply `<br>` and [quoteHtml]. Text: body ([plainLinks]: a link whose text is its
 * address as that bare address), then a blank line, `-- `, and the signature; then for a reply a blank line and [quoteText]. An empty or blank signature adds nothing.
 */
fun bodyParts(body: String, signature: String?, quote: QuotedMessage? = null, bodyHtml: String? = null): BodyParts {
    val sign = if (signature == null) "" else markdownToText(signature)
    var html = "<div dir=\"auto\">${bodyHtml ?: markdownToHtml(body, bareLinks = false)}</div>"
    var text = plainLinks(markdownToText(body))
    if (sign.isNotEmpty()) {
        html += "<div class=\"signature\">${markdownToHtml(sign)}</div>"
        text = "${if (text.isEmpty()) "" else "$text\n\n"}$SIGNATURE_SEPARATOR\n$sign"
    }
    if (quote != null) {
        html += "<br>${quoteHtml(quote)}"
        text = "${if (text.isEmpty()) "" else "$text\n\n"}${quoteText(quote)}"
    }
    return BodyParts(html, text)
}

private val SELF_CLOSING = Regex("""\s*/>""")
private val BETWEEN_TAGS = Regex(""">\s+<""")

/**
 * HTML reduced to the form fixtures compare: self-closing slashes dropped, `&#39;` read as `'`,
 * whitespace between tags removed, ends trimmed.
 */
fun normalizeHtml(html: String): String =
    html.replace(SELF_CLOSING, ">").replace("&#39;", "'").replace(BETWEEN_TAGS, "><").trim()

/**
 * A plain-text part split back into what was typed and its signature, the reverse of [bodyParts]
 * for reopening a draft: the text after the last `-- ` line is the signature.
 */
fun splitSignature(text: String): Pair<String, String?> {
    val normalized = normalizeNewlines(text)
    val marker = "\n$SIGNATURE_SEPARATOR\n"
    val index = normalized.lastIndexOf(marker)
    return when {
        normalized.startsWith("$SIGNATURE_SEPARATOR\n") && index < 0 ->
            "" to normalized.removePrefix("$SIGNATURE_SEPARATOR\n")
        index < 0 -> normalized to null
        else -> normalized.substring(0, index).trimEnd('\n') to normalized.substring(index + marker.length)
    }
}
