package cc.hafa.inbox.core.search

/** Where a chip left the free text: the chip, its place among the chips, and the free text before it. */
data class ChipSpot(
    /** the chip that formed */
    val chip: Chip,
    /** its index in the chips */
    val index: Int,
    /** free text before where the chip was */
    val before: String,
)

/**
 * Spot of the one chip that `parseQuery(text, cursor, tags)` turned into [formed], leaving [free];
 * [index] is where the chip lands among the chips.
 */
fun chipSpot(text: String, cursor: Int, formed: List<Chip>, free: String, index: Int, tags: Boolean = false): ChipSpot? {
    val chip = formed.singleOrNull() ?: return null
    val chipText = formatChip(chip)
    val token = tokenize(text).find { candidate ->
        (cursor < candidate.start || cursor > candidate.end) &&
            tokenChip(candidate, tags)?.let(::formatChip) == chipText
    } ?: return null
    return ChipSpot(chip, index, free.substring(0, minOf(token.start, free.length)))
}

/** Free text with the chip put back where it was when the next word is a top-level `OR`, else null. */
fun unchipBeforeOr(spot: ChipSpot, text: String, cursor: Int): Edit? {
    if (!text.startsWith(spot.before)) return null
    val rest = text.substring(spot.before.length)
    if (tokenize(rest).firstOrNull()?.text != "OR") return null
    val lead = if (spot.before.isEmpty() || spot.before.last().isWhitespace()) "" else " "
    val trail = if (rest.firstOrNull()?.isWhitespace() == true) "" else " "
    val inserted = "$lead${formatChip(spot.chip)}$trail"
    return Edit(
        text = "${spot.before}$inserted$rest",
        cursor = if (cursor >= spot.before.length) cursor + inserted.length else cursor,
    )
}

/** The search field after its free text changes. */
data class TypedField(
    /** every chip, the ones just formed last */
    val chips: List<Chip>,
    /** the free text parsed after the chips */
    val parsed: ParsedQuery,
    /** where the last chip formed, while it can still go back before an `OR` */
    val spot: ChipSpot?,
)

/**
 * The search field after the free text becomes [text], with the cursor at [cursor] (null when the
 * field isn't focused): finished operators (and, with [tags], `#tags`) become chips, and the last
 * chip goes back into the text when `OR` is typed after it.
 */
fun typeFree(chips: List<Chip>, spot: ChipSpot?, text: String, cursor: Int?, tags: Boolean = false): TypedField {
    val back = if (spot != null && spot.index == chips.lastIndex && chips[spot.index] == spot.chip) {
        unchipBeforeOr(spot, text, cursor ?: text.length)
    } else {
        null
    }
    val kept = if (back == null) chips else chips.dropLast(1)
    val typed = back?.text ?: text
    val at = back?.cursor ?: cursor
    val parsed = parseQuery(typed, at, tags)
    return if (parsed.chips.isEmpty()) {
        TypedField(kept, parsed, if (back == null) spot else null)
    } else {
        TypedField(kept + parsed.chips, parsed, chipSpot(typed, at ?: typed.length, parsed.chips, parsed.free, kept.size, tags))
    }
}
