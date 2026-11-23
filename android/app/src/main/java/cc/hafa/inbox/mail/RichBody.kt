package cc.hafa.inbox.mail

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.compose.LinkMode
import cc.hafa.inbox.core.compose.LinkRequest
import cc.hafa.inbox.core.compose.RichBlock
import cc.hafa.inbox.core.compose.RichMark
import cc.hafa.inbox.core.compose.RichRun
import cc.hafa.inbox.core.compose.RunLink
import cc.hafa.inbox.core.compose.linkRequest
import cc.hafa.inbox.core.compose.removeLink
import cc.hafa.inbox.core.compose.runLinkAround
import cc.hafa.inbox.core.compose.runLinks
import cc.hafa.inbox.core.compose.saveLink
import cc.hafa.inbox.core.mail.finishedWebLink
import cc.hafa.inbox.core.compose.SPACE
import cc.hafa.inbox.core.compose.markdownToRich
import cc.hafa.inbox.core.compose.normalizeRuns
import cc.hafa.inbox.core.compose.richToHtml
import cc.hafa.inbox.core.compose.richToMarkdown
import cc.hafa.inbox.core.compose.runs
import cc.hafa.inbox.ui.theme.GeistMono
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextBlockType
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextConfig
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The editor has no quotes or code blocks; this app marks their lines with a paragraph indent of
// its own, and draws the quote bar and code background itself (RichBodyField).

/** indent of a quoted line */
val QUOTE_INDENT = TextIndent(firstLine = 14.sp, restLine = 14.sp)

/** indent of a code block's line */
val CODE_INDENT = TextIndent(firstLine = 12.sp, restLine = 12.sp)

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)

// Code is 13px in the web composer's 14px text.
private val CODE_FONT = SpanStyle(fontFamily = GeistMono, fontSize = 0.93.em)

/** A code block line's text: monospace. */
@OptIn(ExperimentalRichTextApi::class)
object CodeLine : RichSpanStyle {
    override fun getSpanStyle(config: RichTextConfig): SpanStyle = CODE_FONT
}

/** Inline code: the library's code span, in monospace. */
@OptIn(ExperimentalRichTextApi::class)
object InlineCode : RichSpanStyle {
    private val chip = RichSpanStyle.Code()

    override fun getSpanStyle(config: RichTextConfig): SpanStyle = chip.getSpanStyle(config).merge(CODE_FONT)

    override fun DrawScope.drawCustomStyle(
        layoutResult: TextLayoutResult,
        textRange: TextRange,
        config: RichTextConfig,
        topPadding: Float,
        startPadding: Float,
    ) {
        with(chip) { drawCustomStyle(layoutResult, textRange, config, topPadding, startPadding) }
    }
}

// Tiptap's markdown shortcuts, as the web composer has them (web/src/lib/compose/rich-doc.ts).
private val HEADING_START = Regex("^(#{1,6}) $")
private val QUOTE_START = Regex("^> $")
private val CODE_START = Regex("^(?:```|~~~)([a-z]+)? $")
private val PLUS_START = Regex("^\\+ $")
private val CODE_INPUT = Regex("(?:^|[^`])(`([^`]+)`)$")
private val STRIKE_INPUT = Regex("(?:^|\\s)(~~(?!\\s+~~)([^~]+)~~(?!\\s+~~))$")
// Bold and italic by latr's rules, as markdownToRich reads them: no letter, digit, backtick or
// second marker just outside the markers, and no space just inside.
private const val WORD = """\p{L}\p{Nd}\p{M}"""
private val STAR_INPUT = Regex("""(?<![$WORD*`])(\*([^*$SPACE](?:[^*]*[^*$SPACE])?)\*)$""")
private val UNDERSCORE_INPUT = Regex("""(?<![${WORD}_`])(_([^_$SPACE](?:[^_]*[^_$SPACE])?)_)$""")
private val WORD_CHARACTER = Regex("[$WORD]")
private val LINK_INPUT = Regex("""\[([^\[\]]+)]\(((?:[^\s()]|\([^\s()]*\))+)\)$""")

/** Whether an opening backtick before [index] is still unclosed, as CommonMark pairs them. */
private fun insideCodeSpan(text: String, index: Int): Boolean {
    var open = 0
    var position = 0
    while (position < index) {
        if (text[position] != '`') {
            position++
        } else {
            var run = 0
            while (position < index && text[position] == '`') {
                run++
                position++
            }
            open = if (open == 0) run else if (run == open) 0 else open
        }
    }
    return open > 0
}

@OptIn(ExperimentalRichTextApi::class)
private fun RichTextBlock.isCode() = type == RichTextBlockType.Paragraph && textIndent == CODE_INDENT

@OptIn(ExperimentalRichTextApi::class)
private fun RichTextBlock.isQuote() = type == RichTextBlockType.Paragraph && textIndent == QUOTE_INDENT

@OptIn(ExperimentalRichTextApi::class)
private fun RichTextSpanMark.isInlineCode() =
    this is RichTextSpanMark.CodeSpan || (this is RichTextSpanMark.Custom && style == InlineCode)

@OptIn(ExperimentalRichTextApi::class)
private fun RichTextBlock.isPlain() = type == RichTextBlockType.Paragraph && textIndent == null && headingLevel == 0

@OptIn(ExperimentalRichTextApi::class)
private fun runsOf(block: RichTextBlock): List<RichRun> {
    val cuts = sortedSetOf(0, block.text.length)
    for (mark in block.spans) {
        cuts += mark.range.first
        cuts += mark.range.last + 1
    }
    val bounds = cuts.toList()
    return normalizeRuns(
        bounds.zipWithNext { start, end ->
            val covering = block.spans.filter { start >= it.range.first && end - 1 <= it.range.last }
            val marks = covering.mapNotNull { mark ->
                when (mark) {
                    is RichTextSpanMark.Bold -> RichMark.BOLD
                    is RichTextSpanMark.Italic -> RichMark.ITALIC
                    is RichTextSpanMark.Strikethrough -> RichMark.STRIKE
                    is RichTextSpanMark.CodeSpan -> RichMark.CODE
                    is RichTextSpanMark.Custom -> RichMark.CODE.takeIf { mark.style == InlineCode }
                    else -> null
                }
            }
            val href = covering.firstNotNullOfOrNull { (it as? RichTextSpanMark.Link)?.url }
            RichRun(block.text.substring(start, end), marks, href)
        },
    )
}

/** The editor's document as rich text, a block per line: neighboring code lines are one code block. */
@OptIn(ExperimentalRichTextApi::class)
fun documentToRich(document: RichTextDocument): List<RichBlock> {
    val blocks = mutableListOf<RichBlock>()
    val counters = mutableMapOf<Int, Int>()
    var codeBefore = false
    for (block in document.blocks) {
        val type = block.type
        if (type !is RichTextBlockType.ListItem) counters.clear()
        val code = block.isCode()
        val last = blocks.lastOrNull()
        blocks += when {
            type is RichTextBlockType.ListItem && type.ordered -> {
                counters.keys.filter { it > type.indent }.forEach(counters::remove)
                val number = type.startNumber ?: ((counters[type.indent] ?: 0) + 1)
                counters[type.indent] = number
                RichBlock.Ordered(number, runsOf(block))
            }
            type is RichTextBlockType.ListItem -> {
                counters.keys.filter { it >= type.indent }.forEach(counters::remove)
                RichBlock.Bullet(runsOf(block))
            }
            code && codeBefore && last is RichBlock.Code -> {
                blocks.removeAt(blocks.lastIndex)
                RichBlock.Code(last.text + "\n" + block.text)
            }
            code -> RichBlock.Code(block.text)
            block.isQuote() -> RichBlock.Quote(runsOf(block))
            block.headingLevel > 0 -> RichBlock.Heading(block.headingLevel, runsOf(block))
            else -> RichBlock.Paragraph(runsOf(block))
        }
        codeBefore = code
    }
    return blocks
}

@OptIn(ExperimentalRichTextApi::class)
private fun marksOf(runs: List<RichRun>): List<RichTextSpanMark> {
    val marks = mutableListOf<RichTextSpanMark>()
    var offset = 0
    for (run in runs) {
        val range = offset until offset + run.text.length
        if (!range.isEmpty()) {
            for (mark in run.marks) {
                marks += when (mark) {
                    RichMark.BOLD -> RichTextSpanMark.Bold(range)
                    RichMark.ITALIC -> RichTextSpanMark.Italic(range)
                    RichMark.STRIKE -> RichTextSpanMark.Strikethrough(range)
                    RichMark.CODE -> RichTextSpanMark.Custom(range, InlineCode)
                }
            }
            run.href?.let { marks += RichTextSpanMark.Link(range, it) }
        }
        offset += run.text.length
    }
    return marks
}

@OptIn(ExperimentalRichTextApi::class)
private fun codeLine(text: String) = RichTextBlock(
    text = text,
    spans = if (text.isEmpty()) emptyList() else listOf(RichTextSpanMark.Custom(text.indices, CodeLine)),
    textIndent = CODE_INDENT,
)

/** Rich text as the editor's document: a code block is a line apiece. */
@OptIn(ExperimentalRichTextApi::class)
fun richToDocument(blocks: List<RichBlock>): RichTextDocument {
    val out = mutableListOf<RichTextBlock>()
    var expected = 1
    for (block in blocks) {
        val text = block.runs.joinToString("") { it.text }
        val marks = marksOf(block.runs)
        out += when (block) {
            is RichBlock.Paragraph -> listOf(RichTextBlock(text, spans = marks))
            is RichBlock.Heading -> listOf(RichTextBlock(text, spans = marks, headingLevel = block.level))
            is RichBlock.Bullet -> listOf(RichTextBlock(text, RichTextBlockType.ListItem(ordered = false), marks))
            is RichBlock.Ordered -> listOf(
                RichTextBlock(
                    text,
                    RichTextBlockType.ListItem(ordered = true, startNumber = block.number.takeIf { it != expected }),
                    marks,
                ),
            )
            is RichBlock.Quote -> listOf(RichTextBlock(text, spans = marks, textIndent = QUOTE_INDENT))
            is RichBlock.Code -> block.text.split("\n").map(::codeLine)
        }
        expected = if (block is RichBlock.Ordered) block.number + 1 else 1
    }
    return if (out.isEmpty()) RichTextDocument.empty() else RichTextDocument(out)
}

/** [html] as this app's markdown, read as the editor reads pasted HTML, keeping the formatting it has. */
@OptIn(ExperimentalRichTextApi::class)
fun htmlToMarkdown(html: String): String =
    richToMarkdown(documentToRich(RichTextState().setHtml(html).toRichTextDocument()))

/**
 * The composer's body: rich text that markdown typed into it turns into, as the web composer's
 * does, held in a Compose Rich Editor state and handed out as this app's markdown and HTML.
 *
 * The library turns `- `, `* ` and `1. ` into lists itself; the rest of the web composer's
 * shortcuts run here as each change lands (see [watch]). It starts from [markdown], read as
 * [load] reads it with [bareLinks].
 */
@OptIn(ExperimentalRichTextApi::class)
class RichBody(markdown: String, bareLinks: Boolean = false) {
    /** the editor's state */
    val state = RichTextState()

    private var lastText = ""
    private var lastSelection = TextRange(0)
    private var lastBlocks = 1
    private var lastCodeLines: List<String?> = emptyList()
    private var lastCaretLine = 0 to ""

    // Where a shortcut just ended a style, and how to take it off what is typed there next.
    private var ending: Pair<Int, (TextRange) -> Unit>? = null

    // The document and caret line as of the text they were built from; the library builds a new
    // annotated string on every change.
    private var documentOf: AnnotatedString? = null
    private var cachedDocument = RichTextDocument.empty()
    private var caretLineOf: Pair<AnnotatedString, TextRange>? = null
    private var cachedCaretLine = 0 to ""

    init {
        load(markdown, bareLinks)
    }

    private val richBlocks: List<RichBlock> by derivedStateOf {
        state.annotatedString
        documentToRich(currentDocument())
    }

    /** the text as this app's markdown, the message's plain-text part */
    val markdown: String by derivedStateOf { richToMarkdown(richBlocks) }

    /** the text as the message's HTML */
    val html: String by derivedStateOf { richToHtml(richBlocks) }

    /** whether nothing is typed */
    val isEmpty: Boolean by derivedStateOf { state.annotatedString.text.isEmpty() }

    /**
     * Replace the text with [markdown], the caret at its start: the composer's own markdown, whose
     * links are written out, or with [bareLinks] markdown from elsewhere, whose bare web addresses
     * are links too.
     */
    fun load(markdown: String, bareLinks: Boolean = false) {
        state.setRichTextDocument(richToDocument(markdownToRich(markdown, bareLinks)), TextRange(0))
        record()
    }

    /** The text as rich text. */
    fun blocks(): List<RichBlock> = documentToRich(currentDocument())

    /** The text as this app's markdown and as HTML, worked out off the main thread. */
    suspend fun rendered(): Pair<String, String> {
        val document = currentDocument()
        return withContext(Dispatchers.Default) {
            val blocks = documentToRich(document)
            richToMarkdown(blocks) to richToHtml(blocks)
        }
    }

    private fun currentDocument(): RichTextDocument {
        val text = state.annotatedString
        if (text !== documentOf) {
            cachedDocument = state.toRichTextDocument()
            documentOf = text
        }
        return cachedDocument
    }

    /** The link dialog showing: what it is for, on which line of the document; null when closed. */
    var linkDialog by mutableStateOf<BodyLinkRequest?>(null)

    /** The links in the text, in order, each with the line it is on. */
    val links: List<BodyLink> by derivedStateOf {
        state.annotatedString
        currentDocument().blocks.flatMapIndexed { index, block ->
            if (block.isCode()) emptyList() else runLinks(runsOf(block)).map { BodyLink(index, it) }
        }
    }

    /**
     * The link around [offset] of the text, from just before its first letter to just after its
     * last, or (with [letter]) the link whose letter is at [offset]; null if none.
     */
    fun linkAt(offset: Int, letter: Boolean = false): BodyLink? {
        val (index, inLine) = lineAt(offset)
        val block = currentDocument().blocks.getOrNull(index)?.takeUnless { it.isCode() } ?: return null
        val runs = runsOf(block)
        val link = if (letter) runLinks(runs).firstOrNull { inLine >= it.start && inLine < it.end } else runLinkAround(runs, inLine)
        return link?.let { BodyLink(index, it, offset - inLine) }
    }

    /**
     * Open the link dialog as the link button does: to edit the link the selection starts in, make
     * the selected text (on its first line) a link, or add one at the caret.
     */
    fun requestLink() {
        val selection = state.selection
        val (index, start) = lineAt(selection.min)
        val block = currentDocument().blocks.getOrNull(index)?.takeUnless { it.isCode() } ?: return
        val end = minOf(start + selection.length, block.text.length)
        linkDialog = BodyLinkRequest(index, linkRequest(runsOf(block), start, end), selection.min - start)
    }

    /** Open the link dialog to edit [link]. */
    fun editLink(link: BodyLink) {
        val found = link.link
        linkDialog = BodyLinkRequest(link.line, LinkRequest(LinkMode.EDIT, found.start, found.end, found.text, found.href), link.lineStart)
    }

    /** Save the link dialog with [text] and [href], the caret after the link. */
    fun saveLink(text: String, href: String) {
        val dialog = linkDialog ?: return
        linkDialog = null
        changeLine(dialog.line, dialog.lineStart?.let { it + dialog.request.start + text.length }) { runs ->
            saveLink(runs, dialog.request, text, href)
        }
    }

    /** Turn [link] back into plain text, its words kept. */
    fun removeLink(link: BodyLink) {
        changeLine(link.line, null) { runs -> removeLink(runs, link.link) }
    }

    // Replace line [index]'s runs with [change]'s, the caret at [caret] (where it was when null).
    private fun changeLine(index: Int, caret: Int?, change: (List<RichRun>) -> List<RichRun>) {
        val document = currentDocument()
        val block = document.blocks.getOrNull(index)?.takeUnless { it.isCode() } ?: return
        val runs = change(runsOf(block))
        val blocks = document.blocks.toMutableList()
        blocks[index] = block.copy(text = runs.joinToString("") { it.text }, spans = marksOf(runs))
        val at = (caret ?: state.selection.min).coerceAtLeast(0)
        state.setRichTextDocument(RichTextDocument(blocks), TextRange(at))
        record()
    }

    // The line of the document [offset] of the text is on, and where on it. A range's document
    // leaves out a line that starts where the range ends, so the text after the offset tells.
    private fun lineAt(offset: Int): Pair<Int, Int> {
        val length = state.annotatedString.text.length
        val at = offset.coerceIn(0, length)
        val before = state.toRichTextDocument(TextRange(0, at)).blocks
        val after = state.toRichTextDocument(TextRange(at, length)).blocks
        val total = currentDocument().blocks.size
        return if (before.isNotEmpty() && before.size + after.size > total) before.lastIndex to before.last().text.length else before.size to 0
    }

    /** Toggle bold on the selection, or for what is typed next. */
    fun toggleBold() = state.toggleSpanStyle(BOLD)

    /** Toggle italic on the selection, or for what is typed next. */
    fun toggleItalic() = state.toggleSpanStyle(ITALIC)

    /** Insert [line] of markdown text, such as an image reference, as a line of its own at the caret. */
    fun insertLine(line: String) {
        val lead = if (caretLine().second.isEmpty()) "" else "\n"
        state.addTextAfterSelection("$lead$line\n")
    }

    /** Remove every match of [text], and lines left empty by it. */
    fun removeText(text: Regex) {
        val blocks = blocks()
        val kept = blocks.mapNotNull { block ->
            when (block) {
                is RichBlock.Code -> block.copy(text = block.text.replace(text, "")).takeIf { it.text.isNotEmpty() || block.text.isEmpty() }
                else -> {
                    val runs = block.runs.map { it.copy(text = it.text.replace(text, "")) }
                    val before = block.runs.sumOf { it.text.length }
                    val after = runs.sumOf { it.text.length }
                    if (after == 0 && before > 0) null else block.withRuns(normalizeRuns(runs))
                }
            }
        }
        if (kept != blocks) {
            val caret = state.selection.min
            state.setRichTextDocument(richToDocument(kept), TextRange(caret))
            record()
        }
    }

    /** Run the markdown shortcuts on each change, while the field shows. */
    suspend fun watch() {
        snapshotFlow { state.annotatedString.text to state.selection }.collect { changed() }
    }

    private fun record() {
        lastText = state.annotatedString.text
        lastSelection = state.selection
        val blocks = currentDocument().blocks
        lastBlocks = blocks.size
        lastCodeLines = blocks.map { if (it.isCode()) it.text else null }
        lastCaretLine = caretLine()
    }

    // The caret's line in the document and the text before the caret on it. A range's document
    // leaves out a line that starts where the range ends, so the text after the caret tells.
    private fun caretLine(): Pair<Int, String> {
        val key = state.annotatedString to state.selection
        val known = caretLineOf
        if (known == null || known.first !== key.first || known.second != key.second) {
            val caret = state.selection.min
            val before = state.toRichTextDocument(TextRange(0, caret)).blocks
            val after = state.toRichTextDocument(TextRange(caret, state.annotatedString.text.length)).blocks
            val total = currentDocument().blocks.size
            cachedCaretLine = if (before.size + after.size > total) before.lastIndex to before.last().text else before.size to ""
            caretLineOf = key
        }
        return cachedCaretLine
    }

    // Apply the shortcut the last change completes, if any.
    private fun changed() {
        val text = state.annotatedString.text
        if (text == lastText && state.selection == lastSelection) return
        val grew = text.length > lastText.length
        val ended = ending
        ending = null
        val caret = state.selection.min
        if (ended != null && grew && state.selection.collapsed && caret == ended.first + text.length - lastText.length) {
            ended.second(TextRange(ended.first, caret))
        }
        val document = currentDocument()
        val newLines = document.blocks.size - lastBlocks
        if (grew && state.selection.collapsed) {
            val (index, line) = caretLine()
            if (index in document.blocks.indices) {
                if (newLines > 0 && line.isEmpty() && index > 0) {
                    enter(document, index)
                    currentDocument().blocks.getOrNull(index - 1)?.let { linkFinished(index - 1, it.text) }
                } else if (newLines == 0 && !keepCode(document, index)) {
                    val linked = line.endsWith(' ') && linkFinished(index, line.dropLast(1))
                    if (!linked) typed(document.blocks[index], line)
                }
                codeFont()
            }
        }
        record()
    }

    // Link the web address that ends [before], the start of line [index], once a space or line
    // break follows it, unless it is in code or already a link. Whether it linked one.
    private fun linkFinished(index: Int, before: String): Boolean {
        val block = currentDocument().blocks.getOrNull(index)?.takeUnless { it.isCode() } ?: return false
        val found = finishedWebLink(before) ?: return false
        val runs = runsOf(block)
        var offset = 0
        val taken = runs.any { run ->
            val at = offset
            offset += run.text.length
            at < found.end && found.start < offset && (run.href != null || RichMark.CODE in run.marks)
        }
        if (taken) return false
        changeLine(index, state.selection.min) { line ->
            saveLink(line, LinkRequest(LinkMode.SELECTION, found.start, found.end, "", ""), "", found.href)
        }
        return true
    }

    private fun restyle(document: RichTextDocument, caret: Int, change: (MutableList<RichTextBlock>) -> Unit) {
        val blocks = document.blocks.toMutableList()
        change(blocks)
        state.setRichTextDocument(RichTextDocument(blocks), TextRange(caret))
    }

    // Enter: quotes and code blocks carry on to the new line, an empty quote line or two empty
    // code lines end them, and a heading's new line is text.
    private fun enter(document: RichTextDocument, index: Int) {
        val blocks = document.blocks
        val current = blocks[index]
        val previous = blocks[index - 1]
        val caret = state.selection.min
        when {
            previous.isQuote() && previous.text.isEmpty() && current.text.isEmpty() ->
                restyle(document, caret - 1) {
                    it.removeAt(index - 1)
                    it[index - 1] = RichTextBlock("")
                }
            previous.isQuote() && !current.isQuote() ->
                restyle(document, caret) { it[index] = current.copy(textIndent = QUOTE_INDENT, headingLevel = 0) }
            index >= 3 && previous.isCode() && previous.text.isEmpty() && blocks[index - 2].isCode() &&
                blocks[index - 2].text.isEmpty() && blocks[index - 3].isCode() && current.text.isEmpty() ->
                restyle(document, caret - 2) {
                    it.removeAt(index - 1)
                    it.removeAt(index - 2)
                    it[index - 2] = RichTextBlock("")
                }
            previous.isCode() && !current.isCode() ->
                restyle(document, caret) { it[index] = codeLine(current.text) }
            previous.headingLevel > 0 && current.text.isEmpty() -> {
                if (current.headingLevel > 0) restyle(document, caret) { it[index] = current.copy(headingLevel = 0) }
                ending = state.selection.min to { range -> state.removeSpanStyle(state.getSpanStyle(range), range) }
            }
        }
    }

    // The editor turns `- `, `* ` and `1. ` into a list even on a code line; put the line back.
    private fun keepCode(document: RichTextDocument, index: Int): Boolean {
        val old = lastCodeLines.getOrNull(index)
        val (oldIndex, oldLine) = lastCaretLine
        if (old == null || oldIndex != index || document.blocks[index].type !is RichTextBlockType.ListItem) return false
        val at = oldLine.length
        restyle(document, lastSelection.min + 1) { it[index] = codeLine(old.substring(0, at) + " " + old.substring(at)) }
        return true
    }

    // A key typed on a line: line starts that make a heading, quote, code block or bullet, and
    // closing markers that style what they wrap.
    private fun typed(block: RichTextBlock, line: String) {
        if (block.isCode()) return
        val caret = state.selection.min
        val start = caret - line.length
        if (block.isPlain() && blockStart(line, start, caret)) return
        if (line.length >= 2 && block.spans.any { it.isInlineCode() && line.length - 2 in it.range }) return
        CODE_INPUT.find(line)?.let { match ->
            wrap(start, match.groups[1]!!.range, 1, 1) { state.addRichSpan(InlineCode, it) }
            state.removeRichSpan(InlineCode)
            ending = state.selection.min to { range -> state.removeRichSpan(InlineCode, range) }
            return
        }
        for ((pattern, style, marker) in listOf(Triple(STRIKE_INPUT, STRIKE, "~~"), Triple(STAR_INPUT, BOLD, "*"), Triple(UNDERSCORE_INPUT, ITALIC, "_"))) {
            val match = pattern.find(line) ?: continue
            val range = match.groups[1]!!.range
            wrap(start, range, marker.length, marker.length) { state.addSpanStyle(style, it) }
            state.removeSpanStyle(style)
            val from = start + range.first
            ending = state.selection.min to { typed -> endStyle(style, marker.takeIf { style != STRIKE }, from, typed) }
            return
        }
        LINK_INPUT.find(line)?.let { match ->
            val at = match.range.first
            val href = match.groupValues[2]
            if ((at > 0 && line[at - 1] == '!') || insideCodeSpan(line, at) || match.groupValues[1].isBlank() ||
                href.startsWith("javascript:", ignoreCase = true)
            ) {
                return
            }
            wrap(start, match.range, 1, href.length + 3) { state.addLinkToTextRange(href, it) }
        }
    }

    // Keep what is typed right after a shortcut's style out of it. A letter or digit right after
    // `*bold*` or `_italic_` puts the [marker]s back around the text from [from] instead, unstyled,
    // as markdown reads them.
    private fun endStyle(style: SpanStyle, marker: String?, from: Int, typed: TextRange) {
        val first = state.annotatedString.text.getOrNull(typed.min)
        if (marker != null && first != null && WORD_CHARACTER.matches(first.toString())) {
            state.removeSpanStyle(style, TextRange(from, typed.max))
            state.addTextAtIndex(typed.min, marker)
            state.addTextAtIndex(from, marker)
            state.selection = TextRange(typed.max + 2 * marker.length)
        } else {
            state.removeSpanStyle(style, typed)
        }
    }

    // Style the text between markers of [open] and [close] characters around [range] of the caret's line.
    private fun wrap(start: Int, range: IntRange, open: Int, close: Int, style: (TextRange) -> Unit) {
        val from = start + range.first
        val to = start + range.last + 1
        style(TextRange(from + open, to - close))
        state.removeTextRange(TextRange(to - close, to))
        state.removeTextRange(TextRange(from, from + open))
        state.selection = TextRange(to - open - close)
    }

    private fun blockStart(line: String, start: Int, caret: Int): Boolean {
        val heading = HEADING_START.find(line)
        val kind = when {
            heading != null -> heading.groupValues[1].length
            QUOTE_START.matches(line) -> QUOTE
            CODE_START.matches(line) -> CODE
            PLUS_START.matches(line) -> BULLET
            else -> return false
        }
        state.removeTextRange(TextRange(start, caret))
        val document = currentDocument()
        val index = caretLine().first
        restyle(document, start) {
            val block = it[index]
            it[index] = when (kind) {
                QUOTE -> block.copy(textIndent = QUOTE_INDENT)
                CODE -> codeLine(block.text)
                BULLET -> block.copy(type = RichTextBlockType.ListItem(ordered = false))
                else -> block.copy(headingLevel = kind)
            }
        }
        return true
    }

    // Typing on a code line keeps it monospace.
    private fun codeFont() {
        val (index, line) = caretLine()
        val block = currentDocument().blocks.getOrNull(index) ?: return
        if (!block.isCode() || block.text.isEmpty()) return
        val covered = block.spans.any { it is RichTextSpanMark.Custom && it.style == CodeLine && it.range == block.text.indices }
        if (!covered) {
            val start = state.selection.min - line.length
            state.addRichSpan(CodeLine, TextRange(start, start + block.text.length))
        }
    }

    private companion object {
        const val QUOTE = 7
        const val CODE = 8
        const val BULLET = 9
    }
}

/** A link in the composer's text: the line of the editor's document it is on, and where on that line. */
data class BodyLink(
    /** its line in the editor's document */
    val line: Int,
    /** the link on that line */
    val link: RunLink,
    /** where that line starts in the text, when known */
    val lineStart: Int? = null,
)

/** What the composer's link dialog is for: the line, the request on it, and where that line starts in the text, if known. */
data class BodyLinkRequest(
    /** its line in the editor's document */
    val line: Int,
    /** what it is for on that line */
    val request: LinkRequest,
    /** where the line starts in the text, to put the caret after the link; null to leave it */
    val lineStart: Int?,
)

private fun RichBlock.withRuns(runs: List<RichRun>): RichBlock = when (this) {
    is RichBlock.Paragraph -> copy(runs = runs)
    is RichBlock.Heading -> copy(runs = runs)
    is RichBlock.Bullet -> copy(runs = runs)
    is RichBlock.Ordered -> copy(runs = runs)
    is RichBlock.Quote -> copy(runs = runs)
    is RichBlock.Code -> this
}
