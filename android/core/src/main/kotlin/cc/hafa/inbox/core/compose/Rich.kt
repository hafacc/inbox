package cc.hafa.inbox.core.compose

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
private data class Literal(val start: Int, val end: Int, val code: String? = null, val linkText: String? = null, val href: String? = null)

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

private fun literals(line: String): List<Literal> {
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
            if (match != null) {
                val (text, href) = match.destructured
                found += Literal(index, index + match.value.length, linkText = text, href = href)
                index += match.value.length
                continue
            }
        }
        index++
    }
    return found
}

private val STRIKE = Regex("""~~(?=[^$SPACE~])((?:(?!~~)[^\n])*?[^$SPACE~])~~""")

private data class MarkRange(val start: Int, val end: Int, val mark: RichMark)

/** One line's runs: `` `code` ``, `[text](url)`, `~~strike~~`, then latr's `*bold*` and `_italic_`. */
fun parseRuns(line: String, links: Boolean = true): List<RichRun> {
    val found = if (links) literals(line) else literals(line).filter { it.code != null }
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

/** This app's markdown as rich text, a block per line (a code block per fenced stretch). */
fun markdownToRich(markdown: String): List<RichBlock> {
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
                heading != null -> RichBlock.Heading(heading.groupValues[1].length, parseRuns(heading.groupValues[2]))
                bullet != null -> RichBlock.Bullet(parseRuns(bullet.groupValues[1]))
                ordered != null -> RichBlock.Ordered(ordered.groupValues[1].toInt(), parseRuns(ordered.groupValues[2]))
                quote != null -> RichBlock.Quote(parseRuns(quote.groupValues[1]))
                else -> RichBlock.Paragraph(parseRuns(line))
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

/** One line's runs as markdown: a link whose text is its address stays a bare URL. */
fun runsToMarkdown(runs: List<RichRun>): String = buildString {
    var index = 0
    while (index < runs.size) {
        val href = runs[index].href
        var end = index + 1
        while (end < runs.size && runs[end].href == href) end++
        val group = runs.subList(index, end)
        val inner = styledMarkdown(group)
        if (href == null || (group.size == 1 && inner == href)) append(inner) else append("[$inner](${linkTarget(href)})")
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
private val WWW = Regex("""^www\.""", RegexOption.IGNORE_CASE)

// Plain text as HTML: escaped, bare URLs as links (`www.` ones to `http://`), `![alt](cid:id)` as an image.
private fun textHtml(text: String): String = buildString {
    var at = 0
    for ((start, end, kind) in markdownSpans(text)) {
        if ((kind != MarkdownSpanKind.URL && kind != MarkdownSpanKind.IMAGE) || start < at) continue
        append(escapeHtml(text.substring(at, start)))
        val slice = text.substring(start, end)
        if (kind == MarkdownSpanKind.URL) {
            val href = if (WWW.containsMatchIn(slice)) "http://$slice" else slice
            append("<a href=\"${escapeHtml(href)}\">${escapeHtml(slice)}</a>")
        } else {
            val (alt, contentId) = IMAGE_PARTS.find(slice)!!.destructured
            append("<img src=\"cid:${escapeHtml(contentId)}\" alt=\"${escapeHtml(alt)}\">")
        }
        at = end
    }
    append(escapeHtml(text.substring(at)))
}

/** One line's runs as HTML: `<a href>` around each link's runs, then `<b>`, `<i>`, `<s>` and `<code>` inward. */
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
        if (href == null) append(inner) else append("<a href=\"${escapeHtml(href)}\">$inner</a>")
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
