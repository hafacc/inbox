package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.compose.InlineStyle

// latr's styled text tree, the same as shared/src/todos/styled.ts (ported from latr's web
// inline-style.ts styledTree); both run the `styled` cases of shared/testdata/todo-list-fixtures.json.

/** A piece of a todo's text as shown: plain text, a style marker, a `#tag`, or a styled span. */
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

    /** A styled span: its markers and what they hold. */
    data class Span(
        /** the style */
        val style: InlineStyle.Style,
        /** the opening marker, the content and the closing marker */
        val children: List<StyledNode>,
    ) : StyledNode
}

/** The (properly nested) styled spans of [text] as a tree, with markers and `#tags` as their own leaves. */
fun styledTree(text: String): List<StyledNode> {
    val spans = InlineStyle.parse(text)
    val tags = Tags.parse(text)
    var next = 0

    // Pushes text[from, to), cutting out the parts of tags inside it.
    fun pushText(nodes: MutableList<StyledNode>, from: Int, to: Int) {
        var pos = from
        for (tag in tags) {
            val start = maxOf(tag.start, pos)
            val end = minOf(tag.end, to)
            if (start >= end) continue
            if (start > pos) nodes += StyledNode.Text(text.substring(pos, start))
            nodes += StyledNode.Tag(text.substring(start, end))
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

/** A todo's text as a one-line summary shows it: its style markers dropped, its tags kept. */
fun styledPlain(text: String): String {
    fun flatten(nodes: List<StyledNode>): String = nodes.joinToString("") { node ->
        when (node) {
            is StyledNode.Span -> flatten(node.children)
            is StyledNode.Marker -> ""
            is StyledNode.Text -> node.text
            is StyledNode.Tag -> node.text
        }
    }
    return flatten(styledTree(text)).trim()
}
