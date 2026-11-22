package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.todo.Position
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.listDate
import cc.hafa.inbox.core.todo.newestFirst
import cc.hafa.inbox.core.todo.ownKey
import java.time.ZoneId

/** A thread as a list shows it: its summary, snooze, and the date it sorts by. */
data class Row(
    /** the thread */
    val thread: ThreadSummary,
    /** its snooze status */
    val status: SnoozeStatus,
    /** when its snooze ends, epoch milliseconds */
    val snoozeUntil: Long?,
    /** epoch milliseconds the list sorts it by */
    val sortDate: Long,
)

/** An entry of a list section: a thread or label group ([ListEntry]), or a todo ([TodoEntry]). */
sealed interface SectionEntry<out Thread>

/** A todo among the threads of a list. */
data class TodoEntry(
    /** the todo */
    val todo: Todo,
) : SectionEntry<Nothing>

/** One section of a list, ready to render. */
data class SectionModel(
    /** stable identity of the section */
    val key: String,
    /** heading text */
    val title: String,
    /** rows, label groups and todos, in order */
    val entries: List<SectionEntry<Row>>,
    /** whether the heading offers "Done all" */
    val canDoneAll: Boolean,
)

/** When a done todo sorts among done threads: its own place. */
fun doneTodoDate(todo: Todo): Long = Position.keyTime(todo.ownKey())

/** How many of an open group's threads it shows: the first [GROUP_PREVIEW] until all are asked for. */
fun groupShownCount(threads: Int, showAll: Boolean): Int = if (showAll) threads else minOf(threads, GROUP_PREVIEW)

/**
 * A row's place as a list shows it: its held place while open, else its own, with its held pinning
 * while focused (see [HeldPlaces]).
 */
private fun placeOf(row: Row, held: HeldPlaces): ThreadPlace =
    held.threads[row.thread.id] ?: ThreadPlace(row.sortDate, held.pins[row.thread.id] ?: row.thread.isPinned, single = false)

/** Each thread once, newest first by its place (see [newestThreadsFirst]). */
private fun newestFirst(rows: List<Row>, held: HeldPlaces): List<Pair<Row, ThreadPlace>> =
    newestThreadsFirst(rows.map { it to placeOf(it, held) }, { (row, _) -> row.thread.id }, { (_, place) -> place.date })

/**
 * Inbox: pinned threads and todos first, then time sections; a big label folds into one group, in
 * the section of its newest thread. An open thread or bundle in [held] keeps its held place: a held
 * thread its date, its pinning and its own row; a held bundle its date, and stays a bundle however
 * few threads are left in it, even none, until Gmail is known to have no more of its label
 * ([exhausted]), when an empty one leaves. Active [todos] sit among the threads by [listDate]. A
 * row whose email has a todo ([hasEmailTodo]) shows it inside, and is never folded into a group,
 * nor counts toward one.
 */
fun inboxSections(
    rows: List<Row>,
    now: Long,
    userLabelIds: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
    held: HeldPlaces = HeldPlaces(),
    todos: List<Todo> = emptyList(),
    exhausted: Set<String> = emptySet(),
    hasEmailTodo: (Row) -> Boolean = { false },
): List<SectionModel> {
    val placed = newestFirst(rows, held)
    val (pinned, rest) = placed.partition { (_, place) -> place.pinned }
    val kept = userLabelIds.filterTo(linkedSetOf()) { it in held.bundles }
    val groups = groupByLabel(
        rest.map { it.first },
        { row -> if (placeOf(row, held).single || hasEmailTodo(row)) emptyList() else row.thread.labelIds },
        userLabelIds,
        MIN_GROUP_SIZE,
        kept,
    ).toMutableList()
    val loose = todos.newestFirst(zone)
    val todoDates = loose.associate { it.id to it.listDate(zone) }
    val (pinnedTodos, otherTodos) = loose.partition { it.pinned }
    val out = mutableListOf<SectionModel>()
    val pinnedEntries = (
        pinned.map<Pair<Row, ThreadPlace>, Pair<SectionEntry<Row>, Long>> { (row, place) -> ListEntry.Single(row) to place.date } +
            pinnedTodos.map { TodoEntry(it) to todoDates.getValue(it.id) }
        ).sortedByDescending { (_, date) -> date }
    if (pinnedEntries.isNotEmpty()) out += SectionModel("pinned", "Pinned", pinnedEntries.map { it.first }, canDoneAll = false)
    val dates = placed.associate { (row, place) -> row.thread.id to place.date }
    for (labelId in kept) {
        if (labelId !in exhausted && groups.none { it is ListEntry.Group && it.labelId == labelId }) groups += ListEntry.Group(labelId, emptyList())
    }
    fun dateOf(entry: SectionEntry<Row>): Long = when (entry) {
        is ListEntry.Single -> dates.getValue(entry.thread.thread.id)
        is ListEntry.Group -> held.bundles[entry.labelId] ?: dates.getValue(entry.threads.first().thread.id)
        is TodoEntry -> todoDates.getValue(entry.todo.id)
    }
    val entries = (groups + otherTodos.map<Todo, SectionEntry<Row>>(::TodoEntry)).sortedByDescending(::dateOf)
    for (section in sectionize(entries) { pastSection(dateOf(it), now, zone) }) {
        out += SectionModel(section.key, section.title, section.items, canDoneAll = true)
    }
    return out
}

/**
 * The open thread and bundles at the places [sections] shows them: the open thread on its own row
 * or in a bundle, and each of the [openBundles] by its label and the date of its newest thread; and
 * the focused thread, when it isn't the open one, by whether it is pinned now.
 */
fun openPlaces(sections: List<SectionModel>, openBundles: Set<String>, openId: String?, focusedId: String? = null): HeldPlaces {
    val threads = mutableMapOf<String, ThreadPlace>()
    val bundles = mutableMapOf<String, Long>()
    val pins = mutableMapOf<String, Boolean>()
    val pinFocused = focusedId.takeIf { it != openId }
    fun place(row: Row, held: ThreadPlace) {
        val id = row.thread.id
        if (id == openId) threads[id] = held else if (id == pinFocused) pins[id] = row.thread.isPinned
    }
    for (section in sections) {
        for (entry in section.entries) {
            when (entry) {
                is ListEntry.Single -> place(entry.thread, ThreadPlace(entry.thread.sortDate, pinned = section.key == "pinned", single = true))
                is ListEntry.Group -> {
                    if (entry.labelId in openBundles) {
                        bundles[entry.labelId] = entry.threads.firstOrNull()?.sortDate ?: 0L
                    }
                    for (row in entry.threads) place(row, ThreadPlace(row.sortDate, pinned = false, single = false))
                }
                is TodoEntry -> Unit
            }
        }
    }
    return HeldPlaces(threads, bundles, pins)
}

/** Snoozed: soonest wake first, threads and snoozed [todos] together, in sections by wake day. */
fun snoozedSections(
    rows: List<Row>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    todos: List<Todo> = emptyList(),
): List<SectionModel> {
    val dated: List<Pair<SectionEntry<Row>, Long>> = rows.map { ListEntry.Single(it) to (it.snoozeUntil ?: now) } +
        todos.newestFirst(zone).map { todo -> TodoEntry(todo) to (todo.snoozeUntil?.let { isoToEpoch(it, zone) } ?: now) }
    return sectionize(dated.sortedBy { (_, date) -> date }) { (_, date) -> futureSection(date, now, zone) }
        .map { section -> SectionModel(section.key, section.title, section.items.map { it.first }, canDoneAll = false) }
}

/**
 * Any other mailbox: newest first in time sections, an open thread in [held] at its held date, and
 * [todos] among the threads by [listDate].
 */
fun plainSections(
    rows: List<Row>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    held: HeldPlaces = HeldPlaces(),
    todos: List<Todo> = emptyList(),
): List<SectionModel> {
    val dated: List<Pair<SectionEntry<Row>, Long>> = newestFirst(rows, held).map { (row, place) -> ListEntry.Single(row) to place.date } +
        todos.newestFirst(zone).map { TodoEntry(it) to it.listDate(zone) }
    return sectionize(dated.sortedByDescending { (_, date) -> date }) { (_, date) -> pastSection(date, now, zone) }
        .map { section -> SectionModel(section.key, section.title, section.items.map { it.first }, canDoneAll = false) }
}

/** The unpinned thread ids a section's "Done all" sweeps. */
fun doneAllIds(section: SectionModel): List<String> =
    section.entries.flatMap { entry ->
        when (entry) {
            is ListEntry.Single -> listOf(entry.thread)
            is ListEntry.Group -> entry.threads
            is TodoEntry -> emptyList()
        }
    }.filterNot { it.thread.isPinned }.map { it.thread.id }

/** The unpinned todo ids a section's "Done all" sweeps: its own todo rows. */
fun doneAllTodoIds(section: SectionModel): List<String> =
    section.entries.mapNotNull { (it as? TodoEntry)?.todo }.filterNot { it.pinned }.map { it.id }

/**
 * [sections] as the list's layout ([listStep]): each thread, todos on their own rows, and each bundle with whether Gmail has [more] of it and whether its next
 * page is [loading].
 */
fun listLayout(
    sections: List<SectionModel>,
    more: (String) -> Boolean,
    loading: (String) -> Boolean,
): List<LayoutSection<Row, Todo>> = sections.map { section ->
    LayoutSection(
        section.key,
        section.title,
        section.canDoneAll,
        section.entries.map { entry ->
            when (entry) {
                is ListEntry.Single -> LayoutEntry.Thread(
                    LayoutThread(entry.thread.thread.id, entry.thread),
                )
                is ListEntry.Group -> LayoutEntry.Bundle(
                    entry.labelId,
                    entry.threads.map { LayoutThread(it.thread.id, it) },
                    more(entry.labelId),
                    loading(entry.labelId),
                )
                is TodoEntry -> LayoutEntry.Todo(entry.todo.id, entry.todo)
            }
        },
    )
}
