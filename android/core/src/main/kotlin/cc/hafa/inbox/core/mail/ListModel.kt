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

/** An entry of a list section: a thread or label group ([ListEntry]), or a todo ([TodoEntry]); a group's items are threads and todos. */
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

/** A label group of [items] (threads and todos), newest first. */
private fun groupEntry(labelId: String, items: List<SectionEntry<Row>>): ListEntry.Group<Row> =
    ListEntry.Group(
        labelId,
        items.mapNotNull { (it as? ListEntry.Single)?.thread },
        items.mapNotNull { (it as? TodoEntry)?.todo },
        items,
    )

/**
 * Inbox: pinned threads and todos first, then time sections; a big label folds into one group, in
 * the section of its newest thread or todo. Active [todos] sit among the threads by [listDate], and
 * an unpinned one joins the groups of the labels its tags name ([todoLabels], see `todoLabelIds`)
 * as a thread joins its labels': it counts toward a group and takes part in choosing one. An email
 * groups by its own labels and those its todo's tags name ([rowTodoLabels]), together, by the same
 * rule. An open thread or bundle in [held] keeps its held place: a held
 * thread its date, its pinning and its own row; a held bundle its date, and stays a bundle however
 * few items are left in it, even none, until Gmail is known to have no more of its label
 * ([exhausted]), when an empty one leaves.
 */
fun inboxSections(
    rows: List<Row>,
    now: Long,
    userLabelIds: List<String>,
    zone: ZoneId = ZoneId.systemDefault(),
    held: HeldPlaces = HeldPlaces(),
    todos: List<Todo> = emptyList(),
    exhausted: Set<String> = emptySet(),
    todoLabels: (Todo) -> List<String> = { emptyList() },
    rowTodoLabels: (Row) -> List<String> = { emptyList() },
): List<SectionModel> {
    val placed = newestFirst(rows, held)
    val (pinned, rest) = placed.partition { (_, place) -> place.pinned }
    val kept = userLabelIds.filterTo(linkedSetOf()) { it in held.bundles }
    val loose = todos.newestFirst(zone)
    val todoDates = loose.associate { it.id to it.listDate(zone) }
    val (pinnedTodos, otherTodos) = loose.partition { held.pins[todoRowKey(it.id)] ?: it.pinned }
    val dates = placed.associate { (row, place) -> row.thread.id to place.date }
    fun itemDate(item: SectionEntry<Row>): Long = when (item) {
        is ListEntry.Single -> dates.getValue(item.thread.thread.id)
        is TodoEntry -> todoDates.getValue(item.todo.id)
        is ListEntry.Group -> error("a group is not an item")
    }
    val items = (rest.map<Pair<Row, ThreadPlace>, SectionEntry<Row>> { ListEntry.Single(it.first) } + otherTodos.map(::TodoEntry))
        .sortedByDescending(::itemDate)
    val groups = groupByLabel(
        items,
        { item ->
            when (item) {
                is TodoEntry -> todoLabels(item.todo)
                is ListEntry.Single -> if (placeOf(item.thread, held).single) {
                    emptyList()
                } else {
                    (item.thread.thread.labelIds + rowTodoLabels(item.thread)).distinct()
                }
                is ListEntry.Group -> emptyList()
            }
        },
        userLabelIds,
        MIN_GROUP_SIZE,
        kept,
    ).map<ListEntry<SectionEntry<Row>>, SectionEntry<Row>> { entry ->
        when (entry) {
            is ListEntry.Single -> entry.thread
            is ListEntry.Group -> groupEntry(entry.labelId, entry.threads)
        }
    }.toMutableList()
    val out = mutableListOf<SectionModel>()
    val pinnedEntries = (
        pinned.map<Pair<Row, ThreadPlace>, Pair<SectionEntry<Row>, Long>> { (row, place) -> ListEntry.Single(row) to place.date } +
            pinnedTodos.map { TodoEntry(it) to todoDates.getValue(it.id) }
        ).sortedByDescending { (_, date) -> date }
    if (pinnedEntries.isNotEmpty()) out += SectionModel("pinned", "Pinned", pinnedEntries.map { it.first }, canDoneAll = false)
    for (labelId in kept) {
        if (labelId !in exhausted && groups.none { it is ListEntry.Group && it.labelId == labelId }) groups += groupEntry(labelId, emptyList())
    }
    fun dateOf(entry: SectionEntry<Row>): Long = when (entry) {
        is ListEntry.Group -> held.bundles[entry.labelId] ?: itemDate(entry.items.first())
        else -> itemDate(entry)
    }
    val entries = groups.sortedByDescending(::dateOf)
    for (section in sectionize(entries) { pastSection(dateOf(it), now, zone) }) {
        out += SectionModel(section.key, section.title, section.items, canDoneAll = true)
    }
    return out
}

/**
 * The open thread and bundles at the places [sections] shows them: the open thread on its own row
 * or in a bundle, and each of the [openBundles] by its label and the date of its newest item; and
 * the focused thread, when it isn't the open one, by whether it is pinned now; and the focused todo
 * ([focusedTodo]) the same way, under its row key.
 */
fun openPlaces(
    sections: List<SectionModel>,
    openBundles: Set<String>,
    openId: String?,
    focusedId: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    focusedTodo: String? = null,
): HeldPlaces {
    val threads = mutableMapOf<String, ThreadPlace>()
    val bundles = mutableMapOf<String, Long>()
    val pins = mutableMapOf<String, Boolean>()
    val pinFocused = focusedId.takeIf { it != openId }
    fun place(row: Row, held: ThreadPlace) {
        val id = row.thread.id
        if (id == openId) threads[id] = held else if (id == pinFocused) pins[id] = row.thread.isPinned
    }
    fun placeTodo(todo: Todo) {
        if (todo.id == focusedTodo) pins[todoRowKey(todo.id)] = todo.pinned
    }
    for (section in sections) {
        for (entry in section.entries) {
            when (entry) {
                is ListEntry.Single -> place(entry.thread, ThreadPlace(entry.thread.sortDate, pinned = section.key == "pinned", single = true))
                is ListEntry.Group -> {
                    if (entry.labelId in openBundles) {
                        bundles[entry.labelId] = when (val first = entry.items.firstOrNull()) {
                            is ListEntry.Single -> first.thread.sortDate
                            is TodoEntry -> first.todo.listDate(zone)
                            else -> 0L
                        }
                    }
                    for (row in entry.threads) place(row, ThreadPlace(row.sortDate, pinned = false, single = false))
                    entry.todos.forEach(::placeTodo)
                }
                is TodoEntry -> placeTodo(entry.todo)
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

/** The unpinned todo ids a section's "Done all" sweeps: its own todo rows and its bundles' todos. */
fun doneAllTodoIds(section: SectionModel): List<String> =
    section.entries.flatMap { entry ->
        when (entry) {
            is TodoEntry -> listOf(entry.todo)
            is ListEntry.Group -> entry.todos
            is ListEntry.Single -> emptyList()
        }
    }.filterNot { it.pinned }.map { it.id }

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
                    entry.items.map { item ->
                        when (item) {
                            is ListEntry.Single -> LayoutEntry.Thread(LayoutThread(item.thread.thread.id, item.thread))
                            is TodoEntry -> LayoutEntry.Todo(item.todo.id, item.todo)
                            is ListEntry.Group -> error("a group is not an item")
                        }
                    },
                    more(entry.labelId),
                    loading(entry.labelId),
                )
                is TodoEntry -> LayoutEntry.Todo(entry.todo.id, entry.todo)
            }
        },
    )
}
