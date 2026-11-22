package cc.hafa.inbox.core.compose

// latr's text styles, ported from latr's android util/InlineStyle.kt (parse and plain) with only
// the package and doc comments changed; web's shared/src/compose/inline-style.ts is the same port.
// Both run shared/testdata/inline-style-fixtures.json, a byte-for-byte copy of latr's. Keep them in
// step with latr until they move to a common package.

/** `*bold*` / `_italic_` spans in text; same rules as web `inline-style.ts`, pinned by `inline-style-fixtures.json`. */
object InlineStyle {
    /** A text style latr's markers give. */
    enum class Style {
        /** `*bold*` */
        BOLD,

        /** `_italic_` */
        ITALIC,
    }

    /** [start, end) covers the text and both markers, at [start] and [end] - 1. */
    data class Span(
        /** the style */
        val style: Style,
        /** the opening marker's index */
        val start: Int,
        /** the index after the closing marker */
        val end: Int,
    )

    private fun styleOf(marker: Char): Style? = when (marker) {
        '*' -> Style.BOLD
        '_' -> Style.ITALIC
        else -> null
    }

    // Explicit set, identical to web's, so the platforms agree on invisible characters.
    private fun isSpace(codePoint: Int): Boolean = when (codePoint) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> false
    }

    private fun isWordChar(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }

    private fun isLive(entry: Pair<Int, Int>, stackPositions: List<Int>): Boolean =
        entry.first < stackPositions.size && stackPositions[entry.first] == entry.second

    /** The spans of [text], sorted by start, then end descending; markers stay in the text. */
    fun parse(text: String): List<Span> {
        if ('*' !in text && '_' !in text) return emptyList()
        val spans = mutableListOf<Span>()
        val stackPositions = ArrayList<Int>()
        // Per marker, (stack depth, position) of its openers; stale once that stack slot no longer holds the position.
        val openDepths = mapOf('*' to ArrayList<Pair<Int, Int>>(), '_' to ArrayList<Pair<Int, Int>>())
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') {
                stackPositions.clear()
                openDepths.values.forEach { it.clear() }
                continue
            }
            val style = styleOf(c) ?: continue
            val prev = if (i > 0) Character.codePointBefore(text, i) else null
            val next = if (i + 1 < text.length) Character.codePointAt(text, i + 1) else null
            if (prev == c.code || next == c.code) continue
            val canClose = prev != null && !isSpace(prev) && (next == null || !isWordChar(next))
            val canOpen = next != null && !isSpace(next) && (prev == null || !isWordChar(prev))
            val depths = openDepths.getValue(c)
            while (depths.isNotEmpty() && !isLive(depths.last(), stackPositions)) depths.removeAt(depths.size - 1)
            if (canClose && depths.isNotEmpty()) {
                val k = depths.removeAt(depths.size - 1).first
                spans.add(Span(style, stackPositions[k], i + 1))
                while (stackPositions.size > k) stackPositions.removeAt(stackPositions.size - 1)
            } else if (canOpen) {
                depths.add(stackPositions.size to i)
                stackPositions.add(i)
            }
        }
        return spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })
    }

    /** [text] without the marker characters of its spans. */
    fun plain(text: String): String {
        val spans = parse(text)
        if (spans.isEmpty()) return text
        val markers = spans.flatMapTo(HashSet()) { listOf(it.start, it.end - 1) }
        return buildString { text.forEachIndexed { i, ch -> if (i !in markers) append(ch) } }
    }
}
