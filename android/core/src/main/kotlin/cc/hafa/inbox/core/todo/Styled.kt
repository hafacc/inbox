package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.compose.InlineStyle
import cc.hafa.inbox.core.mail.findWebLinks

// latr's styled text tree, the same as shared/src/todos/styled.ts (ported from latr's web
// inline-style.ts styledTree); both run the `styled` cases of shared/testdata/todo-list-fixtures.json.

/** A piece of a todo's text as shown: plain text, a style marker, a `#tag`, a web address, or a styled span. */
sealed interface StyledNode {
    /** Plain text. */
    data class Text(
        /** the text */
        val text: String,
    ) : StyledNode

    /** A `*` or `_` marker, kept in the text. */
    data class Marker(
        /** the marker */
        val text: String,
    ) : StyledNode

    /** A `#tag`. */
    data class Tag(
        /** the tag, `#` included */
        val text: String,
    ) : StyledNode

    /** A web address. */
    data class Link(
        /** the address as written */
        val text: String,
        /** where it leads */
        val href: String,
    ) : StyledNode

    /** A styled span: its markers and what they hold. */
    data class Span(
        /** the style */
        val style: InlineStyle.Style,
        /** the opening marker, the content and the closing marker */
        val children: List<StyledNode>,
    ) : StyledNode
}

// A `#tag` or a web address (with its [href]) in a todo's text.
private class Leaf(val start: Int, val end: Int, val href: String?)

/**
 * The (properly nested) styled spans of [text] as a tree, with markers, `#tags` and web addresses
 * as their own leaves. Web addresses are found first and count as letters to the style and tag
 * rules, so no marker or `#` inside one styles it.
 */
fun styledTree(text: String): List<StyledNode> {
    val links = findWebLinks(text)
    val masked = StringBuilder(text)
    for (link in links) {
        for (index in link.start until link.end) masked.setCharAt(index, 'a')
    }
    val spans = InlineStyle.parse(masked.toString())
    val leaves = (Tags.parse(masked.toString()).map { Leaf(it.start, it.end, null) } + links.map { Leaf(it.start, it.end, it.href) })
        .sortedBy { it.start }
    var next = 0

    // Pushes text[from, to), cutting out the parts of tags and links inside it.
    fun pushText(nodes: MutableList<StyledNode>, from: Int, to: Int) {
        var pos = from
        for (leaf in leaves) {
            val start = maxOf(leaf.start, pos)
            val end = minOf(leaf.end, to)
            if (start >= end) continue
            if (start > pos) nodes += StyledNode.Text(text.substring(pos, start))
            val piece = text.substring(start, end)
            nodes += leaf.href?.let { StyledNode.Link(piece, it) } ?: StyledNode.Tag(piece)
            pos = end
        }
        if (pos < to) nodes += StyledNode.Text(text.substring(pos, to))
    }

    fun build(limit: Int, from: Int): List<StyledNode> {
        val nodes = mutableListOf<StyledNode>()
        var pos = from
        while (pos < limit) {
            val span = spans.getOrNull(next)
            if (span != null && span.start < limit) {
                if (span.start > pos) pushText(nodes, pos, span.start)
                next++
                val inner = build(span.end - 1, span.start + 1)
                nodes += StyledNode.Span(
                    span.style,
                    listOf(StyledNode.Marker(text[span.start].toString())) + inner +
                        StyledNode.Marker(text[span.end - 1].toString()),
                )
                pos = span.end
            } else {
                pushText(nodes, pos, limit)
                pos = limit
            }
        }
        return nodes
    }
    return build(text.length, 0)
}

/** A todo's text as a one-line summary shows it: its style markers dropped, its tags and web addresses kept whole. */
fun styledPlain(text: String): String {
    fun flatten(nodes: List<StyledNode>): String = nodes.joinToString("") { node ->
        when (node) {
            is StyledNode.Span -> flatten(node.children)
            is StyledNode.Marker -> ""
            is StyledNode.Text -> node.text
            is StyledNode.Tag -> node.text
            is StyledNode.Link -> node.text
        }
    }
    return flatten(styledTree(text)).trim()
}
