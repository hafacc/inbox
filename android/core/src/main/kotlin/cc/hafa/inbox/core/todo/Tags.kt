package cc.hafa.inbox.core.todo

// latr's `#tags`, ported from latr's android util/Tags.kt; unlike latr, the tag being typed ends at
// the caret whatever follows it. web's shared/src/todos/tags.ts is the same port. Both run
// shared/testdata/tag-fixtures.json (latr's byte for byte) and tag-complete-fixtures.json.

/** `#tags` in todo text; same rules as web `tags.ts`, pinned by `tag-fixtures.json`. */
object Tags {
    /** A `#tag`: [start, end) covers the `#` and the name, in UTF-16 offsets. */
    data class Span(
        /** the `#`'s index */
        val start: Int,
        /** the index after the name */
        val end: Int,
    )

    /** A `#tag` being typed: [start] is its `#`, the caret ends the name typed so far. */
    data class Typed(
        /** the `#`'s index */
        val start: Int,
        /** the name typed so far, without `#` */
        val prefix: String,
    )

    /** A known tag: its first spelling seen (without `#`) and how many texts use it. */
    data class Known(
        /** the name, as first spelled */
        val name: String,
        /** how many texts use it */
        val count: Int,
    )

    private val openers = setOf('('.code, '['.code, '{'.code, '"'.code, '\''.code, '*'.code, '_'.code, '“'.code, '‘'.code, '«'.code)
    private val trailing = setOf('_'.code, '-'.code, '/'.code)

    /** Whether [codePoint] is whitespace as web's `\s` has it. */
    internal fun isSpace(codePoint: Int): Boolean = when (codePoint) {
        in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> false
    }

    private fun isNameChar(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || Character.getType(codePoint).toByte() == Character.DECIMAL_DIGIT_NUMBER ||
            codePoint in trailing || when (Character.getType(codePoint).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
                else -> false
            }

    private fun startsTag(text: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = text[index - 1].code
        return isSpace(prev) || prev in openers
    }

    /** Every `#tag` in [text]: a `#` at a word start followed by a name with at least one letter. */
    fun parse(text: String): List<Span> {
        if ('#' !in text) return emptyList()
        val tags = mutableListOf<Span>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '#' || !startsTag(text, index)) {
                index++
                continue
            }
            var end = index + 1
            var lastKept = index + 1
            var hasLetter = false
            while (end < text.length) {
                val codePoint = Character.codePointAt(text, end)
                if (!isNameChar(codePoint)) break
                end += Character.charCount(codePoint)
                if (codePoint !in trailing) {
                    lastKept = end
                    if (Character.isLetter(codePoint)) hasLetter = true
                }
            }
            if (hasLetter) tags.add(Span(index, lastKept))
            index = maxOf(end, index + 1)
        }
        return tags
    }

    /** The tag being typed at the caret: from its `#` to the caret, whatever follows; the name may still be empty. */
    fun typing(text: String, caret: Int): Typed? {
        if (caret <= 0) return null
        val start = text.lastIndexOf('#', caret - 1)
        if (start < 0 || !startsTag(text, start)) return null
        val prefix = text.substring(start + 1, caret)
        var index = 0
        while (index < prefix.length) {
            val codePoint = Character.codePointAt(prefix, index)
            if (!isNameChar(codePoint)) return null
            index += Character.charCount(codePoint)
        }
        return Typed(start, prefix)
    }

    /** How many of [texts] use each tag, by lowercased name; a text counts once per tag. */
    fun count(texts: Iterable<String>): Map<String, Known> {
        val counts = LinkedHashMap<String, Known>()
        for (text in texts) {
            for (name in names(text)) {
                val key = name.lowercase()
                val known = counts[key]
                counts[key] = known?.copy(count = known.count + 1) ?: Known(name, 1)
            }
        }
        return counts
    }

    private fun names(text: String): Collection<String> {
        val seen = LinkedHashMap<String, String>()
        for (span in parse(text)) {
            val name = text.substring(span.start + 1, span.end)
            seen.getOrPut(name.lowercase()) { name }
        }
        return seen.values
    }

    private val notKeyChars = Regex("""[^\p{L}\p{Nd}\p{M}_]+""")
    private val keyEdges = Regex("""^[-_]+|[-_]+$""")

    /**
     * The key a tag name (without `#`) or a label name matches by: lower case, each run of anything
     * but letters, digits, marks and `_` as one `-`, `-` and `_` trimmed from each `/`-separated
     * part, empty parts dropped. `Read Later` and `read--later` both give `read-later`.
     */
    fun key(name: String): String =
        name.split('/')
            .map { part -> part.lowercase().replace(notKeyChars, "-").replace(keyEdges, "") }
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /**
     * Known tags starting with [prefix] (ignoring case), most used first, then by name.
     *
     * [ownText] is the saved text of the todo being edited: its tags are left out, being
     * there already (and so is the half-typed one it holds). A tag spelled exactly as typed
     * is left out too. [offered] adds tags no text uses yet (the user's labels as tags), as used
     * by none; one that matches a tag in use or in [ownText] (by [key]) is left out.
     */
    fun suggest(
        counts: Map<String, Known>,
        ownText: String,
        prefix: String,
        limit: Int = 5,
        offered: List<String> = emptyList(),
    ): List<String> {
        val ownNames = names(ownText)
        val own = ownNames.map { it.lowercase() }.toSet()
        val wanted = prefix.lowercase()
        val matches = counts.entries
            .filter { (lower, _) -> lower !in own && lower.startsWith(wanted) && lower != wanted }
            .map { Triple(it.value.name, it.key, it.value.count) }
            .toMutableList()
        val taken = (ownNames.map(::key) + counts.values.map { key(it.name) }).toMutableSet()
        for (name in offered) {
            if (!taken.add(key(name))) continue
            val lower = name.lowercase()
            if (lower.startsWith(wanted) && lower != wanted) matches += Triple(name, lower, 0)
        }
        return matches
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.second })
            .take(limit)
            .map { it.first }
    }

    /** [text] with the typed tag replaced by `#name` and a space; returns the text and the caret after the space. */
    fun complete(text: String, typed: Typed, caret: Int, name: String): Pair<String, Int> {
        val before = text.substring(0, typed.start)
        val after = text.substring(caret)
        val tag = "#$name"
        val completed = if (after.startsWith(" ")) before + tag + after else "$before$tag $after"
        return completed to before.length + tag.length + 1
    }
}
