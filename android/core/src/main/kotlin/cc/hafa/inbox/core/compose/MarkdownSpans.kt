package cc.hafa.inbox.core.compose

// Port of shared/src/compose/markdown-spans.ts; both run shared/testdata/markdown-span-fixtures.json.

/** What a styled stretch of the composer's text is. */
enum class MarkdownSpanKind(
    /** the name web and the fixtures use */
    val wire: String,
) {
    /** an inline image, `![alt](cid:id)` */
    IMAGE("image"),

    /** a bare URL */
    URL("url"),

    /** `*bold*`, markers included */
    BOLD("bold"),

    /** `_italic_`, markers included */
    ITALIC("italic"),

    /** a style marker */
    MARKER("marker"),
}

/** A styled stretch of the text, from [start] up to (not including) [end]. */
data class MarkdownSpan(
    /** first index */
    val start: Int,
    /** index after the last */
    val end: Int,
    /** what it is */
    val kind: MarkdownSpanKind,
)

/** JavaScript's `\s` as the inside of a regex character class, which Java's `\s` (ASCII only) isn't. */
const val SPACE = """\t\n\u000B\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"""
private val IMAGE = Regex("""!\[[^\]\n]*]\(cid:[^$SPACE()]+\)""", RegexOption.IGNORE_CASE)
private val BARE_URL = Regex("""(?:https?://|www\.)[^$SPACE<]+""", RegexOption.IGNORE_CASE)
private val URL_PREFIX = Regex("""(?:https?://|www\.)""", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCTUATION = "?!.,:;*_~'\""

// web's /[\p{L}\p{N}]/u tested on one UTF-16 unit, so half a surrogate pair is neither.
private fun isAlphanumeric(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
    Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    -> true
    else -> false
}

/** A bare URL's length once trailing punctuation and unmatched closing parentheses are left out. */
private fun bareUrlLength(url: String): Int {
    var length = url.length
    while (length > 0) {
        val last = url[length - 1]
        if (last in TRAILING_PUNCTUATION) {
            length--
        } else if (last == ')') {
            val kept = url.substring(0, length)
            if (kept.count { it == ')' } > kept.count { it == '(' }) length-- else return length
        } else {
            return length
        }
    }
    return length
}

/** Inline images and bare URLs, in order; they never overlap. */
private fun literalSpans(text: String): MutableList<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '!' && text.getOrNull(index + 1) == '[') {
            val match = IMAGE.matchAt(text, index)
            if (match != null) {
                spans.add(MarkdownSpan(index, match.range.last + 1, MarkdownSpanKind.IMAGE))
                index = match.range.last + 1
                continue
            }
        }
        if (char in "hwHW" && (index == 0 || !isAlphanumeric(text[index - 1].code))) {
            val match = BARE_URL.matchAt(text, index)
            if (match != null) {
                val length = bareUrlLength(match.value)
                if (length > URL_PREFIX.matchAt(match.value, 0)!!.value.length) {
                    spans.add(MarkdownSpan(index, index + length, MarkdownSpanKind.URL))
                    index += length
                    continue
                }
            }
        }
        index++
    }
    return spans
}

/**
 * The spans of the composer's text: each `*bold*`/`_italic_` (markers included) with a `marker`
 * span for each of its two markers, by latr's rules ([InlineStyle]); each bare URL; each inline
 * image. Images and URLs are read first and count as letters to the style rules, so no marker
 * inside one opens or closes a style. Sorted by start, then end descending, then kind in
 * [MarkdownSpanKind] order.
 */
fun markdownSpans(text: String): List<MarkdownSpan> {
    val spans = literalSpans(text)
    val masked = if (spans.isEmpty()) {
        text
    } else {
        buildString {
            var at = 0
            for (span in spans) {
                append(text, at, span.start)
                repeat(span.end - span.start) { append('a') }
                at = span.end
            }
            append(text, at, text.length)
        }
    }
    for ((style, start, end) in InlineStyle.parse(masked)) {
        val kind = if (style == InlineStyle.Style.BOLD) MarkdownSpanKind.BOLD else MarkdownSpanKind.ITALIC
        spans.add(MarkdownSpan(start, end, kind))
        spans.add(MarkdownSpan(start, start + 1, MarkdownSpanKind.MARKER))
        spans.add(MarkdownSpan(end - 1, end, MarkdownSpanKind.MARKER))
    }
    return spans.sortedWith(compareBy<MarkdownSpan> { it.start }.thenByDescending { it.end }.thenBy { it.kind.ordinal })
}
