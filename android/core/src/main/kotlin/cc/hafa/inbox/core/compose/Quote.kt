package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.CLOCK_FORMAT
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.parseAddress
import cc.hafa.inbox.core.mail.strictPercentDecoded
import cc.hafa.inbox.core.snooze.SnoozeSuggestions.LABEL_LOCALE
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

// Reply quoting and finding quotes to fold; a port of shared/src/compose/quote.ts.

/** The message a reply quotes. */
@Serializable
data class QuotedMessage(
    /** its sender (the first `From` mailbox) */
    val from: Person,
    /** when it was sent, epoch milliseconds (its `Date`, or Gmail's `internalDate`) */
    val date: Long,
    /** its plain-text body */
    val text: String,
    /** its HTML body, or null to quote [text] in the HTML part too */
    val html: String?,
)

/** A body split into what the sender wrote and the quoted message trailing it. */
data class QuoteSplit(
    /** everything before the quote; the whole input when there is none */
    val body: String,
    /** the trailing quote, attribution line included, or null when none was found */
    val quote: String?,
)

/** Inline style Gmail puts on its quote blockquote. */
const val QUOTE_BLOCKQUOTE_STYLE = "margin:0px 0px 0px 0.8ex;border-left:1px solid rgb(204,204,204);padding-left:1ex"

private val ATTRIBUTION_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", LABEL_LOCALE)

internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * The line above a quote, in [zone] and 24-hour time:
 * `On Mon, Nov 2, 2026 at 11:50, Maya Chen <maya.chen@gmail.example.com> wrote:`; without a name the
 * address alone in angle brackets.
 */
fun quoteAttribution(from: Person, date: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(date).atZone(zone)
    val name = from.name.trim()
    val who = if (name.isEmpty()) "<${from.email}>" else "$name <${from.email}>"
    return "On ${ATTRIBUTION_DAY.format(time)} at ${CLOCK_FORMAT.format(time)}, $who wrote:"
}

private val ATTRIBUTION_RE = Regex("""^On (\w{3}, \w{3} \d{1,2}, \d{4}) at (\d{2}:\d{2}), (.+) wrote:$""")

/**
 * The sender and time an attribution line written by [quoteAttribution] names, or null when the
 * line isn't in that form.
 */
fun parseQuoteAttribution(line: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Person, Long>? {
    val match = ATTRIBUTION_RE.matchEntire(line.trim()) ?: return null
    val (day, time, who) = match.destructured
    return runCatching {
        val date = LocalDate.parse(day, ATTRIBUTION_DAY).atTime(LocalTime.parse(time, CLOCK_FORMAT)).atZone(zone)
        parseAddress(who) to date.toInstant().toEpochMilli()
    }.getOrNull()
}

private fun textLines(text: String): List<String> {
    val trimmed = markdownToText(text)
    return if (trimmed.isEmpty()) emptyList() else trimmed.split("\n")
}

/**
 * The quote for the plain-text part: the attribution, a blank line, then each line of the
 * original's text as `> line`, a blank line as `>`.
 */
fun quoteText(quote: QuotedMessage, zone: ZoneId = ZoneId.systemDefault()): String {
    val lines = textLines(quote.text).map { if (it.isEmpty()) ">" else "> $it" }
    val attribution = quoteAttribution(quote.from, quote.date, zone)
    return if (lines.isEmpty()) attribution else "$attribution\n\n${lines.joinToString("\n")}"
}

private val BODY_RE = Regex("""<body\b[^>]*>([\s\S]*?)(</body\s*>|\z)""", RegexOption.IGNORE_CASE)
private val DOCTYPE_RE = Regex("""<!doctype[^>]*>""", RegexOption.IGNORE_CASE)
private val COMMENT_RE = Regex("""<!--[\s\S]*?-->""")
private val HEAD_RE = Regex("""<head\b[\s\S]*?</head\s*>""", RegexOption.IGNORE_CASE)
private val SCRIPT_RE = Regex("""<script\b[\s\S]*?</script\s*>""", RegexOption.IGNORE_CASE)
private val HTML_BODY_TAG_RE = Regex("""</?(html|body)\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * The original's HTML as it goes inside the quote: the inside of `<body>` when there is one, with
 * the doctype, `<head>`, comments, scripts and `<html>`/`<body>` tags removed.
 */
fun quotableHtml(html: String): String {
    val inner = BODY_RE.find(html)?.groupValues?.get(1) ?: html
    return inner
        .replace(DOCTYPE_RE, "")
        .replace(COMMENT_RE, "")
        .replace(HEAD_RE, "")
        .replace(SCRIPT_RE, "")
        .replace(HTML_BODY_TAG_RE, "")
        .trim()
}

private val CID_URL_RE = Regex("""\bcid:([^"'\s)>;]+)""", RegexOption.IGNORE_CASE)

/**
 * The Content-IDs the quoted original's HTML shows as `cid:` URLs, in order of first use, each
 * once, looking only at [quotableHtml]; percent-decoded where that works. Empty for null. A reply
 * attaches those inline parts of the original again, so the quote's images aren't broken.
 */
fun quotedContentIds(html: String?): List<String> {
    if (html == null) return emptyList()
    val ids = mutableListOf<String>()
    for (match in CID_URL_RE.findAll(quotableHtml(html))) {
        val raw = match.groupValues[1]
        val id = strictPercentDecoded(raw) ?: raw
        if (id.isNotEmpty() && id !in ids) ids += id
    }
    return ids
}

private fun textAsHtml(text: String): String = "<div dir=\"auto\">${textLines(text).joinToString("<br>", transform = ::escapeHtml)}</div>"

/** The quote for the HTML part, in Gmail's `gmail_quote` markup so mail clients fold it away. */
fun quoteHtml(quote: QuotedMessage, zone: ZoneId = ZoneId.systemDefault()): String {
    val inner = quote.html?.let(::quotableHtml) ?: textAsHtml(quote.text)
    return "<div class=\"gmail_quote gmail_quote_container\">" +
        "<div dir=\"ltr\" class=\"gmail_attr\">${escapeHtml(quoteAttribution(quote.from, quote.date, zone))}<br></div>" +
        "<blockquote class=\"gmail_quote\" style=\"$QUOTE_BLOCKQUOTE_STYLE\">$inner</blockquote>" +
        "</div>"
}

private val ORIGINAL_MESSAGE = Regex("""^-{2,}\s*Original Message\s*-{2,}$""", RegexOption.IGNORE_CASE)
private val STARTS_WITH_ON = Regex("""^On\s""")

/**
 * Find the quote trailing a plain-text body: the last run of `>` lines (blank lines allowed
 * inside, only blank lines after) under an attribution line ending in `:`, or everything from
 * Outlook's `-----Original Message-----` line. No quote when the body above would be blank.
 */
fun splitQuotedText(text: String): QuoteSplit {
    val lines = normalizeNewlines(text).split("\n")
    fun isBlank(index: Int) = lines.getOrElse(index) { "" }.isBlank()
    fun split(index: Int): QuoteSplit {
        val body = lines.subList(0, index).joinToString("\n").trimEnd()
        return if (body.isBlank()) {
            QuoteSplit(text, null)
        } else {
            QuoteSplit(body, lines.subList(index, lines.size).joinToString("\n").trimEnd())
        }
    }

    val outlook = lines.indexOfFirst { ORIGINAL_MESSAGE.matches(it.trim()) }
    if (outlook >= 0) return split(outlook)

    var end = lines.size - 1
    while (end >= 0 && isBlank(end)) end--
    var start = end + 1
    for (index in end downTo 0) {
        val line = lines[index]
        if (line.startsWith(">")) {
            start = index
        } else if (line.isNotBlank()) {
            break
        }
    }
    if (start > end) return QuoteSplit(text, null)

    var attribution = start - 1
    while (attribution >= 0 && isBlank(attribution)) attribution--
    val line = lines.getOrElse(attribution) { "" }.trim()
    if (attribution < 0 || !line.endsWith(":")) return QuoteSplit(text, null)
    val above = lines.getOrElse(attribution - 1) { "" }.trim()
    if (!STARTS_WITH_ON.containsMatchIn(line) && STARTS_WITH_ON.containsMatchIn(above)) attribution--
    return split(attribution)
}

private val HTML_QUOTE_START = Regex(
    listOf(
        """<(div|blockquote)\b[^>]*\bclass\s*=\s*["'][^"']*\bgmail_quote(?:_container)?\b[^>]*>""",
        """<(blockquote)\b[^>]*\btype\s*=\s*["']?cite\b[^>]*>""",
        """<(div)\b[^>]*\bclass\s*=\s*["'][^"']*\b(?:moz-cite-prefix|yahoo_quoted)\b[^>]*>""",
        """<(div)\b[^>]*\bid\s*=\s*["']?(?:divRplyFwdMsg|appendonsend)\b[^>]*>""",
    ).joinToString("|"),
    RegexOption.IGNORE_CASE,
)
private val OUTLOOK_MARKER = Regex("divRplyFwdMsg|appendonsend", RegexOption.IGNORE_CASE)
private val STYLE_SCRIPT_RE = Regex("""<(style|script)\b[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE)
private val TAG_RE = Regex("""<[^>]*>""")
private val NBSP_RE = Regex("&nbsp;|&#160;| ", RegexOption.IGNORE_CASE)

private fun visibleText(html: String): String =
    html.replace(STYLE_SCRIPT_RE, "").replace(TAG_RE, "").replace(NBSP_RE, " ").trim()

private val ELEMENT_TAGS = mapOf(
    "div" to Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE),
    "blockquote" to Regex("""<(/?)blockquote\b[^>]*>""", RegexOption.IGNORE_CASE),
)

// Index just past the tag closing the `name` element opened at `start`, or the end of `html`.
private fun elementEnd(html: String, name: String, start: Int): Int {
    var depth = 0
    for (match in ELEMENT_TAGS.getValue(name).findAll(html, start)) {
        depth += if (match.groupValues[1] == "/") -1 else 1
        if (depth == 0) return match.range.last + 1
    }
    return html.length
}

/** Quote markers of one body, each element's end found only when asked for. */
private class QuoteMarkers(private val html: String) {
    private val matches = HTML_QUOTE_START.findAll(html).toList()
    private val ends = IntArray(matches.size) { -1 }

    val size: Int get() = matches.size

    fun start(index: Int): Int = matches[index].range.first

    fun toEnd(index: Int): Boolean = OUTLOOK_MARKER.containsMatchIn(matches[index].value)

    fun end(index: Int): Int {
        if (ends[index] < 0) {
            val name = matches[index].groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.lowercase() ?: "div"
            ends[index] = elementEnd(html, name, start(index))
        }
        return ends[index]
    }

    // Whether nothing visible follows marker `index` outside the markers after it.
    fun onlyQuotesAfter(index: Int): Boolean {
        var cursor = end(index)
        for (later in index + 1 until size) {
            val laterStart = start(later)
            if (laterStart < cursor) continue
            if (visibleText(html.substring(cursor, laterStart)).isNotEmpty()) return false
            if (toEnd(later)) return true
            cursor = end(later)
        }
        return visibleText(html.substring(cursor)).isEmpty()
    }
}

/**
 * Find the quote trailing an HTML body by the markup mail clients put around one (Gmail, Apple
 * Mail, Outlook, Thunderbird, Yahoo): the first marker after whose element every bit of visible
 * text lies inside later markers. Plain string slicing, so either half may hold unbalanced tags;
 * sanitize each before showing it. Either way the body ends at its last visible content
 * ([trimTrailingBlankHtml]), so the line break mail puts before a quote doesn't show as a blank line.
 */
fun splitQuotedHtml(html: String): QuoteSplit {
    val markers = QuoteMarkers(html)
    var index = 0
    while (index < markers.size) {
        if (markers.toEnd(index) || markers.onlyQuotesAfter(index)) {
            val start = markers.start(index)
            val body = html.substring(0, start)
            return if (visibleText(body).isEmpty()) {
                QuoteSplit(trimTrailingBlankHtml(html), null)
            } else {
                QuoteSplit(trimTrailingBlankHtml(body), html.substring(start))
            }
        }
        // A marker nested in one with text after it has that same text after it.
        val end = markers.end(index)
        index++
        while (index < markers.size && markers.start(index) < end) index++
    }
    return QuoteSplit(trimTrailingBlankHtml(html), null)
}

// Elements that are blank when they hold nothing visible, unless sized or colored by attributes.
private val BLANK_ELEMENTS = setOf("div", "p", "span", "font", "b", "i", "u", "strong", "em")
private val OPENING_TAG = Regex("""^<([a-z]+)\b[^>]*>$""", RegexOption.IGNORE_CASE)
private val CLOSING_TAG = Regex("""^</([a-z]+)\s*>$""", RegexOption.IGNORE_CASE)
private val LINE_BREAK = Regex("""^<br\b[^>]*>$""", RegexOption.IGNORE_CASE)
private val DRAWN_ATTRIBUTE = Regex("""\s(?:style|height|bgcolor|background)\s*=""", RegexOption.IGNORE_CASE)
private val SPACE_ENTITIES = listOf("&nbsp;", "&#160;", "&#xa0;")

// Whether `tag` opens a blank element named `name` (any such name when null).
private fun opensBlank(tag: String, name: String? = null): Boolean {
    val tagName = OPENING_TAG.find(tag)?.groupValues?.get(1)?.lowercase() ?: return false
    return tagName in BLANK_ELEMENTS && (name == null || tagName == name) && !DRAWN_ATTRIBUTE.containsMatchIn(tag)
}

// Where `html` up to `end` stops once trailing blanks are cut; opening tags only with `cutOpening`.
private fun blankStart(html: String, end: Int, cutOpening: Boolean): Int {
    var cut = end
    while (true) {
        while (cut > 0 && html[cut - 1].isWhitespace()) cut--
        val entity = SPACE_ENTITIES.firstOrNull { cut >= it.length && html.regionMatches(cut - it.length, it, 0, it.length, ignoreCase = true) }
        if (entity != null) {
            cut -= entity.length
            continue
        }
        if (cut == 0 || html[cut - 1] != '>') return cut
        val tagStart = html.lastIndexOf('<', cut - 1)
        if (tagStart < 0) return cut
        val tag = html.substring(tagStart, cut)
        if (LINE_BREAK.matches(tag) || (cutOpening && opensBlank(tag))) {
            cut = tagStart
            continue
        }
        val closing = CLOSING_TAG.find(tag)?.groupValues?.get(1)?.lowercase()
        if (closing == null || closing !in BLANK_ELEMENTS) return cut
        val inner = blankStart(html, tagStart, false)
        val openStart = if (inner == 0) -1 else html.lastIndexOf('<', inner - 1)
        if (openStart < 0 || html[inner - 1] != '>' || !opensBlank(html.substring(openStart, inner), closing)) return cut
        cut = openStart
    }
}

/**
 * HTML without the blank lines at its end: trailing whitespace, `&nbsp;`, `<br>`s, and `div`, `p`,
 * `span`, `font`, `b`, `i`, `u`, `strong` and `em` elements holding only those, left open or closed.
 * An element with a `style`, `height`, `bgcolor` or `background` attribute counts as drawn and stays.
 */
fun trimTrailingBlankHtml(html: String): String = html.substring(0, blankStart(html, html.length, true))

/**
 * A quote kept only as text (a reopened reply whose original isn't at hand): the attribution's
 * sender and time, and the quoted lines without their `> `. Null when the attribution can't be read.
 */
fun quoteFromText(quote: String, zone: ZoneId = ZoneId.systemDefault()): QuotedMessage? {
    val lines = normalizeNewlines(quote).split("\n")
    val attributionEnd = lines.indexOfFirst { it.trim().endsWith(":") }
    if (attributionEnd < 0) return null
    val attribution = lines.subList(0, attributionEnd + 1).joinToString(" ") { it.trim() }
    val (from, date) = parseQuoteAttribution(attribution, zone) ?: return null
    val text = lines.drop(attributionEnd + 1).joinToString("\n") { line ->
        when {
            line.startsWith("> ") -> line.substring(2)
            line.startsWith(">") -> line.substring(1)
            else -> line
        }
    }.trim('\n')
    return QuotedMessage(from, date, text, null)
}
