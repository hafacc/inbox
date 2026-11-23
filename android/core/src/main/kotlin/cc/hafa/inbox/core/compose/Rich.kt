package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.findWebLinks
import cc.hafa.inbox.core.mail.linkAddress
import cc.hafa.inbox.core.mail.safeHref

// Port of shared/src/compose/rich.ts; both run shared/testdata/rich-fixtures.json.

/** An inline style, in the order runs list them and HTML nests them, outermost first. */
enum class RichMark(
    /** the name web and the fixtures use */
    val wire: String,
    /** its HTML tag */
    val tag: String,
) {
    /** bold */
    BOLD("bold", "b"),

    /** italic */
    ITALIC("italic", "i"),

    /** strikethrough */
    STRIKE("strike", "s"),

    /** inline code */
    CODE("code", "code"),
}

/** A stretch of text with one set of styles. */
data class RichRun(
    /** the text */
    val text: String,
    /** its styles in [RichMark] order; code text has no other style */
    val marks: List<RichMark> = emptyList(),
    /** the link it is part of */
    val href: String? = null,
)

/** One line of the composer: a paragraph, heading, list item or quote line, or a whole code block. */
sealed interface RichBlock {
    /** plain paragraph */
    data class Paragraph(
        /** its text */
        val runs: List<RichRun>,
    ) : RichBlock

    /** heading */
    data class Heading(
        /** 1 to 6 */
        val level: Int,
        /** its text */
        val runs: List<RichRun>,
    ) : RichBlock

    /** bulleted list item */
    data class Bullet(
        /** its text */
        val runs: List<RichRun>,
    ) : RichBlock

    /** numbered list item */
    data class Ordered(
        /** its number */
        val number: Int,
        /** its text */
        val runs: List<RichRun>,
    ) : RichBlock

    /** quoted line */
    data class Quote(
        /** its text */
        val runs: List<RichRun>,
    ) : RichBlock

    /** code block */
    data class Code(
        /** its lines, joined with LF */
        val text: String,
    ) : RichBlock
}

/** The block's runs; none for a code block. */
val RichBlock.runs: List<RichRun>
    get() = when (this) {
        is RichBlock.Paragraph -> runs
        is RichBlock.Heading -> runs
        is RichBlock.Bullet -> runs
        is RichBlock.Ordered -> runs
        is RichBlock.Quote -> runs
        is RichBlock.Code -> emptyList()
    }

private fun isJsSpace(char: Char): Boolean = char in "\t\n\u000B\u000C\r       　﻿" ||
    char in ' '..' '

/** Runs with empty ones dropped, neighbors of the same style joined, marks in order, code alone. */
fun normalizeRuns(runs: List<RichRun>): List<RichRun> {
    val out = mutableListOf<RichRun>()
    for (run in runs) {
        if (run.text.isEmpty()) continue
        val code = RichMark.CODE in run.marks
        val marks = if (code) listOf(RichMark.CODE) else RichMark.entries.filter { it in run.marks }
        val next = RichRun(run.text, marks, if (code) null else run.href)
        val last = out.lastOrNull()
        if (last != null && last.href == next.href && last.marks == next.marks) {
            out[out.lastIndex] = last.copy(text = last.text + next.text)
        } else {
            out += next
        }
    }
    return out
}

// A stretch of a line read before the style markers: inline code or a `[text](url)` link.
// A bare web address is [bare]: its markers style nothing.
private data class Literal(
    val start: Int,
    val end: Int,
    val code: String? = null,
    val linkText: String? = null,
    val href: String? = null,
    val bare: Boolean = false,
)

// A link target: no spaces, parentheses only in balanced pairs one deep, as [runsToMarkdown] writes it.
private const val LINK_TARGET = """(?:[^$SPACE()]|\([^$SPACE()]*\))+"""
private val LINK = Regex("""^\[([^\]\n]+)]\(($LINK_TARGET)\)""")
private val WHOLE_LINK_TARGET = Regex("""^$LINK_TARGET$""")
private val LINK_SPACE = Regex("[$SPACE]")
private val PAREN = Regex("[()]")

// A link's address as a markdown link target: whitespace percent-encoded, and parentheses too unless balanced one deep.
private fun linkTarget(href: String): String {
    val spaced = href.replace(LINK_SPACE) { match ->
        match.value.toByteArray(Charsets.UTF_8).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }
    }
    return if (WHOLE_LINK_TARGET.matches(spaced)) spaced else spaced.replace(PAREN) { if (it.value == "(") "%28" else "%29" }
}

// A line's literals; [bare] adds its bare web addresses as links to themselves.
private fun literals(line: String, bare: Boolean): List<Literal> {
    val found = mutableListOf<Literal>()
    var index = 0
    while (index < line.length) {
        val char = line[index]
        if (char == '`') {
            val close = line.indexOf('`', index + 1)
            if (close > index + 1) {
                found += Literal(index, close + 1, code = line.substring(index + 1, close))
                index = close + 1
                continue
            }
        } else if (char == '[' && line.getOrNull(index - 1) != '!') {
            val match = LINK.find(line.substring(index))
            // A link to anything but a web or mail address stays as typed.
            val href = match?.groupValues?.get(2)?.let(::safeHref)
            if (match != null && href != null) {
                found += Literal(index, index + match.value.length, linkText = match.groupValues[1], href = href)
                index += match.value.length
                continue
            }
        }
        index++
    }
    // Bare web addresses outside those (and outside inline images) are links to themselves.
    for (span in if (bare) markdownSpans(line) else emptyList()) {
        if (span.kind != MarkdownSpanKind.URL || found.any { it.start < span.end && span.start < it.end }) continue
        val text = line.substring(span.start, span.end)
        val href = linkAddress(text) ?: continue
        found += Literal(span.start, span.end, linkText = text, href = href, bare = true)
    }
    return found.sortedBy { it.start }
}

private val STRIKE = Regex("""~~(?=[^$SPACE~])((?:(?!~~)[^\n])*?[^$SPACE~])~~""")

private data class MarkRange(val start: Int, val end: Int, val mark: RichMark)

/**
 * One line's runs: `` `code` ``, `[text](url)` (only to an http, https or mailto address), and
 * with [bare] its bare web addresses as links, `~~strike~~`, then latr's `*bold*` and `_italic_`.
 */
fun parseRuns(line: String, links: Boolean = true, bare: Boolean = true): List<RichRun> {
    val found = if (links) literals(line, bare) else literals(line, false).filter { it.code != null }
    val masked = StringBuilder(line)
    for (literal in found) {
        for (at in literal.start until literal.end) masked.setCharAt(at, 'a')
    }
    val markers = mutableSetOf<Int>()
    val ranges = mutableListOf<MarkRange>()
    for (match in STRIKE.findAll(masked)) {
        val start = match.range.first
        val end = match.range.last + 1
        ranges += MarkRange(start, end, RichMark.STRIKE)
        markers += listOf(start, start + 1, end - 2, end - 1)
    }
    for (span in markdownSpans(masked.toString())) {
        when (span.kind) {
            MarkdownSpanKind.BOLD -> ranges += MarkRange(span.start, span.end, RichMark.BOLD)
            MarkdownSpanKind.ITALIC -> ranges += MarkRange(span.start, span.end, RichMark.ITALIC)
            MarkdownSpanKind.MARKER -> markers += span.start
            else -> Unit
        }
    }
    fun marksAt(at: Int): List<RichMark> = ranges.filter { at >= it.start && at < it.end }.map { it.mark }
    val runs = mutableListOf<RichRun>()
    var index = 0
    while (index < line.length) {
        val literal = found.firstOrNull { it.start == index }
        if (literal?.code != null) {
            runs += RichRun(literal.code, listOf(RichMark.CODE))
            index = literal.end
        } else if (literal?.linkText != null && literal.bare) {
            runs += RichRun(literal.linkText, marksAt(index), literal.href)
            index = literal.end
        } else if (literal?.linkText != null) {
            val outer = marksAt(index)
            for (run in parseRuns(literal.linkText, links = false)) {
                runs += RichRun(run.text, outer + run.marks, literal.href)
            }
            index = literal.end
        } else {
            if (index !in markers) runs += RichRun(line[index].toString(), marksAt(index))
            index++
        }
    }
    return normalizeRuns(runs)
}

private val HEADING = Regex("""^(#{1,6}) (.*)$""")
private val BULLET = Regex("""^[-*+] (.*)$""")
private val ORDERED = Regex("""^(\d{1,9})\. (.*)$""")
private val QUOTE = Regex("""^>(?: (.*))?$""")

/**
 * This app's markdown as rich text, a block per line (a code block per fenced stretch). With
 * [bareLinks] (markdown typed elsewhere: a signature, a shared text) a bare web address is a link
 * to itself; without it (markdown the composer wrote, which writes every link out) only a
 * `[text](url)` is, so an address the user took a link off stays plain.
 */
fun markdownToRich(markdown: String, bareLinks: Boolean = true): List<RichBlock> {
    val lines = normalizeNewlines(markdown).split("\n")
    val blocks = mutableListOf<RichBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("```")) {
            val body = mutableListOf<String>()
            index++
            while (index < lines.size && lines[index].trim() != "```") {
                body += lines[index]
                index++
            }
            blocks += RichBlock.Code(body.joinToString("\n"))
        } else {
            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val ordered = ORDERED.find(line)
            val quote = QUOTE.find(line)
            blocks += when {
                heading != null -> RichBlock.Heading(heading.groupValues[1].length, parseRuns(heading.groupValues[2], bare = bareLinks))
                bullet != null -> RichBlock.Bullet(parseRuns(bullet.groupValues[1], bare = bareLinks))
                ordered != null -> RichBlock.Ordered(ordered.groupValues[1].toInt(), parseRuns(ordered.groupValues[2], bare = bareLinks))
                quote != null -> RichBlock.Quote(parseRuns(quote.groupValues[1], bare = bareLinks))
                else -> RichBlock.Paragraph(parseRuns(line, bare = bareLinks))
            }
        }
        index++
    }
    return blocks
}

private val MARKERS = mapOf(RichMark.BOLD to "*", RichMark.ITALIC to "_", RichMark.STRIKE to "~~")

// Runs without links as markdown: each style's stretches wrapped in its markers, spaces at a
// stretch's ends left outside them.
private fun styledMarkdown(runs: List<RichRun>): String {
    val pieces = runs.map { if (RichMark.CODE in it.marks) "`${it.text}`" else it.text }
    val starts = pieces.runningFold(0) { at, piece -> at + piece.length }
    val text = pieces.joinToString("")
    val opens = HashMap<Int, MutableList<String>>()
    val closes = HashMap<Int, MutableList<String>>()
    for ((mark, marker) in MARKERS) {
        var rangeStart: Int? = null
        fun finish(end: Int) {
            var start = rangeStart ?: return
            var stop = end
            while (start < stop && isJsSpace(text[start])) start++
            while (stop > start && isJsSpace(text[stop - 1])) stop--
            if (stop > start) {
                opens.getOrPut(start) { mutableListOf() }.add(marker)
                closes.getOrPut(stop) { mutableListOf() }.add(0, marker)
            }
            rangeStart = null
        }
        runs.forEachIndexed { index, run ->
            if (mark in run.marks) {
                if (rangeStart == null) rangeStart = starts[index]
            } else {
                finish(starts[index])
            }
        }
        finish(text.length)
    }
    return buildString {
        for (index in 0..text.length) {
            closes[index]?.forEach { append(it) }
            opens[index]?.forEach { append(it) }
            if (index < text.length) append(text[index])
        }
    }
}

/**
 * One line's runs as markdown, every link written out as `[text](url)`, so the composer reading
 * it back ([markdownToRich] without bare links) has the same links and no others.
 */
fun runsToMarkdown(runs: List<RichRun>): String = buildString {
    var index = 0
    while (index < runs.size) {
        val href = runs[index].href
        var end = index + 1
        while (end < runs.size && runs[end].href == href) end++
        val group = runs.subList(index, end)
        val inner = styledMarkdown(group)
        if (href == null) append(inner) else append("[$inner](${linkTarget(href)})")
        index = end
    }
}

/** Rich text as this app's markdown, the message's plain-text part: a line per block. */
fun richToMarkdown(blocks: List<RichBlock>): String = blocks.joinToString("\n") { block ->
    when (block) {
        is RichBlock.Paragraph -> runsToMarkdown(block.runs)
        is RichBlock.Heading -> "${"#".repeat(block.level)} ${runsToMarkdown(block.runs)}"
        is RichBlock.Bullet -> "- ${runsToMarkdown(block.runs)}"
        is RichBlock.Ordered -> "${block.number}. ${runsToMarkdown(block.runs)}"
        is RichBlock.Quote -> runsToMarkdown(block.runs).let { if (it.isEmpty()) ">" else "> $it" }
        is RichBlock.Code -> "```\n${block.text}\n```"
    }
}

private val IMAGE_PARTS = Regex("""^!\[([^\]]*)]\(cid:([^)]*)\)$""", RegexOption.IGNORE_CASE)
// Plain text as HTML: escaped, `![alt](cid:id)` as an image. A web address in it is no link: the
// runs' links are already marked.
private fun textHtml(text: String): String = buildString {
    var at = 0
    for ((start, end, kind) in markdownSpans(text)) {
        if (kind != MarkdownSpanKind.IMAGE || start < at) continue
        append(escapeHtml(text.substring(at, start)))
        val (alt, contentId) = IMAGE_PARTS.find(text.substring(start, end))!!.destructured
        append("<img src=\"cid:${escapeHtml(contentId)}\" alt=\"${escapeHtml(alt)}\">")
        at = end
    }
    append(escapeHtml(text.substring(at)))
}

/**
 * One line's runs as HTML: `<a href>` around each link's runs (only to an http, https or mailto
 * address), then `<b>`, `<i>`, `<s>` and `<code>` inward.
 */
fun runsToHtml(runs: List<RichRun>): String = buildString {
    var index = 0
    while (index < runs.size) {
        val href = runs[index].href
        val inner = StringBuilder()
        while (index < runs.size && runs[index].href == href) {
            val run = runs[index]
            var piece = if (href == null && run.marks.isEmpty()) textHtml(run.text) else escapeHtml(run.text)
            for (mark in run.marks.reversed()) piece = "<${mark.tag}>$piece</${mark.tag}>"
            inner.append(piece)
            index++
        }
        val safe = href?.let(::safeHref)
        if (safe == null) append(inner) else append("<a href=\"${escapeHtml(safe)}\">$inner</a>")
    }
}

/**
 * Rich text as the message's HTML. Paragraphs are lines joined with `<br>`, as typed; headings are
 * `<h1>`–`<h6>`; neighboring list items share one `<ul>` or `<ol>` (with `start` when the first
 * isn't 1); neighboring quote lines one `<blockquote>`, joined with `<br>`; a code block is
 * `<pre><code>`. Blank lines at the start and end are left out, as the plain-text part leaves them.
 */
fun richToHtml(all: List<RichBlock>): String = buildString {
    fun blank(block: RichBlock) = block is RichBlock.Paragraph && block.runs.all { run -> run.text.all { it == ' ' || it == '\t' } }
    val blocks = all.dropWhile(::blank).dropLastWhile(::blank)
    var afterLine = false
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        if (block is RichBlock.Paragraph) {
            if (afterLine) append("<br>")
            append(runsToHtml(block.runs))
            afterLine = true
            index++
            continue
        }
        afterLine = false
        when (block) {
            is RichBlock.Heading -> {
                append("<h${block.level}>${runsToHtml(block.runs)}</h${block.level}>")
                index++
            }
            is RichBlock.Code -> {
                append("<pre><code>${escapeHtml(block.text)}</code></pre>")
                index++
            }
            else -> {
                val kind = block::class
                val group = mutableListOf<RichBlock>()
                while (index < blocks.size && blocks[index]::class == kind) {
                    group += blocks[index]
                    index++
                }
                val lines = group.map { runsToHtml(it.runs) }
                when (block) {
                    is RichBlock.Quote -> append("<blockquote>${lines.joinToString("<br>")}</blockquote>")
                    is RichBlock.Bullet -> append("<ul>${lines.joinToString("") { "<li>$it</li>" }}</ul>")
                    else -> {
                        val first = block as RichBlock.Ordered
                        val start = if (first.number != 1) " start=\"${first.number}\"" else ""
                        append("<ol$start>${lines.joinToString("") { "<li>$it</li>" }}</ol>")
                    }
                }
            }
        }
    }
}

private val SCHEME_PREFIX = Regex("""^(?:https?://|mailto:)""", RegexOption.IGNORE_CASE)

// An address as a link's text is compared with it: no http, https or mailto scheme, no trailing slash, lower case.
private fun linkKey(address: String): String = address.replace(SCHEME_PREFIX, "").removeSuffix("/").lowercase()

/** Whether a link's [text] is just its address [href]: equal once a scheme (http, https, mailto), a trailing slash and case are ignored. */
fun selfNamedLink(text: String, href: String): Boolean = linkKey(text) == linkKey(href)

private val EMAIL = Regex("""(?<![\w.%+-])[\w.%+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+(?![\w-])""", RegexOption.IGNORE_CASE)
private val IMAGE = Regex("""!\[[^\]\n]*]\(cid:[^$SPACE()]+\)""", RegexOption.IGNORE_CASE)

private fun IntRange.overlapsAny(others: List<IntRange>): Boolean = others.any { it.first < last + 1 && first < it.last + 1 }

// The web and email addresses in plain text, in order, as ranges.
private fun addressSpans(text: String): List<IntRange> {
    val spans = findWebLinks(text).map { it.start until it.end }.toMutableList()
    for (match in EMAIL.findAll(text)) {
        if (!match.range.overlapsAny(spans)) spans += match.range
    }
    return spans.sortedBy { it.first }
}

// An address in a markdown line: a `[text](url)` link ([href] set) or a bare one ([href] null).
private data class LineAddress(val start: Int, val end: Int, val text: String, val href: String?)

// A markdown line's links and its bare addresses outside them, inline code and images, in order.
private fun lineAddresses(line: String): List<LineAddress> {
    val found = literals(line, bare = false)
    val hidden = found.map { it.start until it.end } + IMAGE.findAll(line).map { it.range }
    val masked = StringBuilder(line)
    for (range in hidden) for (index in range) masked.setCharAt(index, 'a')
    val addresses = found.mapNotNull { literal ->
        literal.href?.let { LineAddress(literal.start, literal.end, literal.linkText.orEmpty(), it) }
    }.toMutableList()
    for (span in addressSpans(masked.toString())) {
        if (span.overlapsAny(hidden)) continue
        addresses += LineAddress(span.first, span.last + 1, line.substring(span.first, span.last + 1), null)
    }
    return addresses.sortedBy { it.start }
}

// Markdown with [change] applied to each line outside fenced code blocks.
private fun mapProseLines(markdown: String, change: (String) -> String): String {
    var fenced = false
    return markdown.split("\n").joinToString("\n") { line ->
        if (fenced) {
            fenced = line.trim() != "```"
            line
        } else if (line.startsWith("```")) {
            fenced = true
            line
        } else {
            change(line)
        }
    }
}

/**
 * The composer's markdown as the message's plain-text part: each link whose text is just its
 * address ([selfNamedLink]) written as that bare address, unless the line read back wouldn't find
 * the address there (as when it runs into the next word); every other link stays `[text](url)`.
 * [draftLinks] takes the links back from the HTML part.
 */
fun plainLinks(markdown: String): String = mapProseLines(markdown, ::plainLine)

// One line of [plainLinks].
private fun plainLine(line: String): String {
    var bare = lineAddresses(line).filter { it.href != null && selfNamedLink(it.text, it.href) }
    while (true) {
        val spans = mutableListOf<IntRange>()
        val text = buildString {
            var at = 0
            for (address in bare) {
                append(line, at, address.start)
                spans += length until length + address.text.length
                append(address.text)
                at = address.end
            }
            append(line, at, line.length)
        }
        val found = lineAddresses(text).filter { it.href == null }.map { it.start until it.end }
        val kept = bare.filterIndexed { index, _ -> spans[index] in found }
        if (kept.size == bare.size) return text
        bare = kept
    }
}

private val ENTITY = Regex("""&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));""", RegexOption.IGNORE_CASE)
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")
private val HTML_PIECE = Regex("""<[^>]*>|[^<]+""")
private val TAG_NAME = Regex("""^</?\s*([a-z][a-z0-9]*)""", RegexOption.IGNORE_CASE)
private val HREF = Regex("""\shref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
private val LINE_TAGS = setOf("br", "p", "div", "li", "ul", "ol", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "tr")

// HTML text with its character references read.
private fun unescapeHtml(text: String): String = text.replace(ENTITY) { match ->
    val (decimal, hex, name) = match.destructured
    when {
        decimal.isNotEmpty() -> String(Character.toChars(decimal.toInt()))
        hex.isNotEmpty() -> String(Character.toChars(hex.toInt(16)))
        else -> NAMED_ENTITIES[name.lowercase()] ?: match.value
    }
}

private class Appearance(val start: Int, val key: String, val href: String?)

private class OpenAnchor(val start: Int, val href: String?, var plain: Boolean = true)

// Each address's appearances in HTML's text, in order, by [linkKey]: the link's address where a
// link of plain text that is just its address shows it, null where it shows unlinked. Text in other
// links and in code doesn't count.
private fun htmlAddresses(html: String): Map<String, List<String?>> {
    val visible = StringBuilder()
    val hidden = mutableListOf<IntRange>()
    val appearances = mutableListOf<Appearance>()
    var anchor: OpenAnchor? = null
    var codeStart = 0
    var codeDepth = 0
    for (match in HTML_PIECE.findAll(html)) {
        val piece = match.value
        if (!piece.startsWith("<")) {
            visible.append(unescapeHtml(piece))
            continue
        }
        val name = TAG_NAME.find(piece)?.groupValues?.get(1)?.lowercase() ?: continue
        val closing = piece.startsWith("</")
        if (name == "a") {
            if (!closing) {
                val href = HREF.find(piece)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::unescapeHtml)
                anchor = OpenAnchor(visible.length, href)
            } else if (anchor != null) {
                val span = anchor.start until visible.length
                hidden += span
                val text = visible.substring(anchor.start, visible.length)
                val safe = anchor.href?.let(::safeHref)
                if (anchor.plain && safe != null && selfNamedLink(text, safe)) appearances += Appearance(anchor.start, linkKey(text), safe)
                anchor = null
            }
            continue
        }
        anchor?.plain = false
        if (name == "code" || name == "pre") {
            if (!closing) {
                if (codeDepth == 0) codeStart = visible.length
                codeDepth++
            } else if (codeDepth > 0) {
                codeDepth--
                if (codeDepth == 0) hidden += codeStart until visible.length
            }
        }
        if (name in LINE_TAGS) visible.append('\n')
    }
    val text = visible.toString()
    for (span in addressSpans(text)) {
        if (span.overlapsAny(hidden)) continue
        appearances += Appearance(span.first, linkKey(text.substring(span.first, span.last + 1)), null)
    }
    return appearances.sortedBy { it.start }.groupBy({ it.key }, { it.href })
}

/**
 * A reopened draft's [markdown] (read from its plain-text part, see [plainLinks]) with its links
 * back from its [html] part: a bare address becomes `[address](url)` only where the HTML links it,
 * matched by order among that address's appearances in each, so an address whose link was taken
 * off stays plain. Links already written out stay as they are.
 */
fun draftLinks(markdown: String, html: String?): String {
    if (html == null) return markdown
    val inHtml = htmlAddresses(html)
    val seen = mutableMapOf<String, Int>()
    return mapProseLines(markdown) { line ->
        buildString {
            var at = 0
            for (address in lineAddresses(line)) {
                if (address.href != null && !selfNamedLink(address.text, address.href)) continue
                val key = linkKey(address.text)
                val index = seen[key] ?: 0
                seen[key] = index + 1
                val href = inHtml[key]?.getOrNull(index)
                if (address.href == null && href != null) {
                    append(line, at, address.start)
                    append("[${address.text}](${linkTarget(href)})")
                    at = address.end
                }
            }
            append(line, at, line.length)
        }
    }
}
