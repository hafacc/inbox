package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.compose.InlineStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// latr's list behavior, the same as shared/src/todos/list.ts (ported from latr's web group.ts,
// reorder.ts and todo.ts heldInPlace and rankBySearch), kept to the Active list; both run
// shared/testdata/todo-list-fixtures.json, generated from latr's own code.

/** One heading of a todo list and its rows. */
data class Bucket(
    /** the heading */
    val label: String,
    /** its rows, in list order */
    val todos: List<Todo>,
    /** the Pinned bucket, which only pinned rows move within */
    val pinned: Boolean = false,
    /** the earliest time a row under a date heading can have; null when the bucket isn't one */
    val floor: Long? = null,
    /** the time rows under a date heading are before; null for Today, which runs to now */
    val ceiling: Long? = null,
)

/** Where a row is dropped: a bucket, and an index among that bucket's other rows. */
data class DropSlot(
    /** the bucket's index */
    val bucket: Int,
    /** the index among the bucket's rows, the moving one left out */
    val index: Int,
)

private fun dayStart(epoch: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()

private fun LocalDate.epoch(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private fun groupByPastKey(todos: List<Todo>, keyOf: (Todo) -> String, now: Long, zone: ZoneId): List<Bucket> {
    val today = dayStart(now, zone)
    val todayAt = today.epoch(zone)
    val yesterday = today.minusDays(1).epoch(zone)
    val weekAgo = today.minusDays(WEEK_DAYS).epoch(zone)
    val heads = listOf(
        Bucket("Today", emptyList(), floor = todayAt),
        Bucket("Yesterday", emptyList(), floor = yesterday, ceiling = todayAt),
        Bucket("This week", emptyList(), floor = weekAgo, ceiling = yesterday),
        Bucket("Earlier", emptyList(), floor = 0L, ceiling = weekAgo),
    )
    val rows = heads.map { mutableListOf<Todo>() }
    for (todo in todos) {
        val at = Position.keyTime(keyOf(todo))
        val index = heads.indexOfFirst { head -> head.floor != null && at >= head.floor }.takeIf { it >= 0 } ?: heads.lastIndex
        rows[index] += todo
    }
    return heads.zip(rows).filter { (_, list) -> list.isNotEmpty() }.map { (head, list) -> head.copy(todos = list) }
}

private const val WEEK_DAYS = 6L

/** Active todos, already sorted, under their headings: Pinned, then by date, keeping their order. */
fun groupActive(sorted: List<Todo>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> {
    val (pinned, rest) = sorted.partition { it.pinned }
    val pinnedBucket = if (pinned.isEmpty()) emptyList() else listOf(Bucket("Pinned", pinned, pinned = true))
    return pinnedBucket + groupByPastKey(rest, { it.activeKey(zone) }, now, zone)
}

/** The slot a row is in now, or null when it isn't in [buckets]. */
fun slotOf(buckets: List<Bucket>, id: String): DropSlot? {
    for ((bucket, entry) in buckets.withIndex()) {
        val index = entry.todos.indexOfFirst { it.id == id }
        if (index >= 0) return DropSlot(bucket, index)
    }
    return null
}

/**
 * The todo as dropping it at [slot] leaves it.
 *
 * Returns the same object when the slot is where the row already is, and null when the row can't
 * go there: an unpinned row among pinned ones, or the reverse. [buckets] is the Active list as
 * shown, from [groupActive].
 */
fun planMove(todo: Todo, buckets: List<Bucket>, slot: DropSlot, now: Long, zone: ZoneId = ZoneId.systemDefault()): Todo? {
    val current = slotOf(buckets, todo.id)
    val bucket = buckets.getOrNull(slot.bucket)
    if (current == null || bucket == null) return null
    if (current == slot) return todo
    val others = bucket.todos.filter { it.id != todo.id }
    val above = others.getOrNull(slot.index - 1)
    val below = others.getOrNull(slot.index)
    if (bucket.pinned != todo.pinned) return null
    // Under a date heading, the top and the bottom go halfway to the heading's edge.
    val floor = bucket.floor
    val position = when {
        above != null && below != null -> Position.positionBetween(below.activeKey(zone), above.activeKey(zone), todo.id)
        below != null -> {
            val low = below.activeKey(zone)
            val edge = if (floor == null) null else Position.timeKey(bucket.ceiling ?: now)
            edge?.let { Position.positionBetween(low, it, todo.id) } ?: Position.positionAbove(low, todo.id)
        }
        above != null -> {
            val high = above.activeKey(zone)
            floor?.let { Position.positionBetween(Position.timeKey(it), high, todo.id) } ?: Position.positionBelow(high, todo.id)
        }
        else -> return null
    } ?: return null
    return todo.copy(
        position = position,
        modifiedAt = now,
        snoozeUntil = if (todo.isSnoozed(now, zone)) todo.snoozeUntil else null,
    )
}

/**
 * The copy of a todo being edited that the list places it by, so its row stays put until it loses
 * focus.
 *
 * [held] is the copy so far, [previous] and [live] the stored todo before and after a change. A
 * text change keeps the held text and place; any other change (a pin, say) is taken, text aside.
 */
fun heldInPlace(held: Todo, previous: Todo, live: Todo): Todo =
    if (live.text != previous.text) {
        live.copy(text = held.text, snoozeUntil = held.snoozeUntil, position = held.position, modifiedAt = held.modifiedAt)
    } else {
        live.copy(text = held.text)
    }

// Web's `\w`, which is ASCII only.
private fun isWordChar(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'

// A word matches where it starts the text or follows a char that isn't a word char, as web's
// `\b` (before a word char) and `(?<!\w)` (before any other) do.
private fun startsWord(haystack: String, word: String): Boolean {
    var from = haystack.indexOf(word)
    while (from >= 0) {
        if (from == 0 || !isWordChar(haystack[from - 1])) return true
        from = haystack.indexOf(word, from + 1)
    }
    return false
}

/**
 * The todos whose text (markers removed) has a word starting with one of the query's words, most
 * words matched first, otherwise in the order given. An empty query keeps them all.
 */
fun rankBySearch(todos: List<Todo>, query: String): List<Todo> = rankBySearch(todos, query) { it.text }

/** The [items] whose text ([textOf]) matches [query], ranked as [rankBySearch] ranks todos. */
fun <Item> rankBySearch(items: List<Item>, query: String, textOf: (Item) -> String): List<Item> {
    val words = buildList {
        val word = StringBuilder()
        for (char in query.lowercase()) {
            if (Tags.isSpace(char.code)) {
                if (word.isNotEmpty()) add(word.toString())
                word.clear()
            } else {
                word.append(char)
            }
        }
        if (word.isNotEmpty()) add(word.toString())
    }
    if (words.isEmpty()) return items
    return items
        .map { item ->
            val haystack = InlineStyle.plain(textOf(item)).lowercase()
            item to words.count { startsWord(haystack, it) }
        }
        .filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
        .map { (item, _) -> item }
}

