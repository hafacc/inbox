package cc.hafa.inbox.core.mail

// How a mail list is used: which rows it shows in what order, keyboard focus, the open thread, the
// open bundle and its paging, selection, the scroll anchor, rows on their way out, and undo. One
// pure transition ([listStep]) takes every event; the app keeps no focus or open state of its own.
// Ported from web's shared/src/list-state.ts; both run shared/testdata/list-state-fixtures.json.
// The rules it keeps are in docs/design.md, "The list".

/** Threads an open bundle shows before "Show N more". */
const val GROUP_PREVIEW = 5

/** A thread as a layout gives it: its id and what the app draws it from. */
data class LayoutThread<out ThreadData>(
    /** Gmail thread id */
    val id: String,
    /** the app's own data for the row */
    val data: ThreadData,
)

/** One entry of a layout section: a thread, a todo, or a bundle of one label's threads. */
sealed interface LayoutEntry<out ThreadData, out TodoData> {
    /** A thread with a row of its own. */
    data class Thread<out ThreadData>(
        /** the thread */
        val thread: LayoutThread<ThreadData>,
    ) : LayoutEntry<ThreadData, Nothing>

    /** A todo with a row of its own. */
    data class Todo<out TodoData>(
        /** todo id */
        val id: String,
        /** the app's own data for the row */
        val data: TodoData,
    ) : LayoutEntry<Nothing, TodoData>

    /** A bundle of one label's threads. */
    data class Bundle<out ThreadData>(
        /** the label */
        val labelId: String,
        /** its threads, in order */
        val threads: List<LayoutThread<ThreadData>>,
        /** whether Gmail has more of the label's inbox threads than were fetched */
        val more: Boolean,
        /** whether a next page is on its way */
        val loading: Boolean,
    ) : LayoutEntry<ThreadData, Nothing>
}

/** One section of a layout: what the app's data says the list holds, in order. */
data class LayoutSection<out ThreadData, out TodoData>(
    /** stable section key */
    val key: String,
    /** heading */
    val title: String,
    /** whether the heading offers "Done all" */
    val doneAll: Boolean,
    /** entries in order */
    val entries: List<LayoutEntry<ThreadData, TodoData>>,
)

/** A row of the list, in visual order. */
sealed interface ListRow<out ThreadData, out TodoData> {
    /** unique row key */
    val key: String

    /** key of the section it shows in */
    val section: String

    /** A section heading. */
    data class Section(
        override val key: String,
        override val section: String,
        /** heading */
        val title: String,
        /** whether the heading offers "Done all" */
        val doneAll: Boolean,
    ) : ListRow<Nothing, Nothing>

    /** A thread's row. */
    data class Thread<out ThreadData>(
        override val key: String,
        /** Gmail thread id */
        val id: String,
        override val section: String,
        /** the open bundle it shows in, or null for a row of its own */
        val bundle: String?,
        /** the app's own data for the row */
        val data: ThreadData,
    ) : ListRow<ThreadData, Nothing>

    /** A todo's own row. */
    data class Todo<out TodoData>(
        override val key: String,
        /** todo id */
        val id: String,
        override val section: String,
        /** the app's own data for the row */
        val data: TodoData,
    ) : ListRow<Nothing, TodoData>

    /** A bundle's header. */
    data class Bundle<out ThreadData>(
        override val key: String,
        /** the label */
        val labelId: String,
        override val section: String,
        /** all its threads, shown or not */
        val threads: List<LayoutThread<ThreadData>>,
        /** whether it is the open bundle */
        val open: Boolean,
        /** whether Gmail has more of its threads */
        val more: Boolean,
        /** whether a next page is on its way */
        val loading: Boolean,
    ) : ListRow<ThreadData, Nothing>

    /** The "Show more" row under an open bundle. */
    data class More(
        override val key: String,
        /** the bundle's label */
        val labelId: String,
        override val section: String,
        /** loaded threads the preview leaves out */
        val hidden: Int,
        /** whether Gmail has more of its threads */
        val more: Boolean,
        /** whether a next page is on its way */
        val loading: Boolean,
    ) : ListRow<Nothing, Nothing>
}

/** The open bundle. */
data class OpenBundle<out ThreadData>(
    /** the label */
    val labelId: String,
    /** whether it shows every loaded thread, not just the first [GROUP_PREVIEW] */
    val showAll: Boolean,
    /** while a page the keyboard asked for loads: the row focus sat on, which new rows follow */
    val awaiting: String?,
    /** its threads as last shown */
    val threads: List<LayoutThread<ThreadData>>,
)

/** Who moved focus last: the keyboard (it scrolls into view and leaving a bundle closes it) or a pointer. */
enum class FocusSource {
    /** keys, Tab included */
    KEYBOARD,

    /** a click, tap or accessibility focus */
    POINTER,
}

/** Where the rows an action acts on come from. */
enum class TargetSource {
    /** the open thread */
    THREAD,

    /** the selection */
    SELECTION,

    /** the focused row */
    ROW,
}

/** Rows an action acts on. */
data class ListTarget(
    /** the open thread, the selection, or the focused row */
    val from: TargetSource,
    /** thread ids */
    val threads: List<String>,
    /** todo ids */
    val todos: List<String>,
    /** the label of a focused bundle, whose threads [threads] lists */
    val bundle: String?,
)

/** Work the app does after a step. */
sealed interface ListEffect {
    /** Load the next page of a bundle. */
    data class LoadMore(
        /** the bundle's label */
        val labelId: String,
    ) : ListEffect

    /** Apply an action to a target. */
    data class Act(
        /** the action's name, as the key that asked for it names it */
        val action: String,
        /** what it acts on */
        val target: ListTarget,
    ) : ListEffect

    /** Edit a todo. */
    data class Edit(
        /** todo id */
        val todoId: String,
    ) : ListEffect
}

/** What undo puts back: the rows an action took away and where focus was. */
data class ListUndo(
    /** row keys */
    val keys: List<String>,
    /** the focused row */
    val focus: String?,
)

/** The row the viewport is pinned to, and its offset from the viewport top in pixels. */
data class ListAnchor(
    /** row key */
    val key: String,
    /** offset from the viewport top */
    val offset: Int,
)

/** Where an action came from. */
enum class ActSource {
    /** a row's swipe or button, or the focused row */
    ROW,

    /** the open thread */
    THREAD,

    /** the selection bar */
    SELECTION,

    /** a notification */
    NOTIFICATION,
}

/** The whole list as one value. */
data class ListState<out ThreadData, out TodoData>(
    /** the layout last given */
    val layout: List<LayoutSection<ThreadData, TodoData>> = emptyList(),
    /** rows in visual order */
    val rows: List<ListRow<ThreadData, TodoData>> = emptyList(),
    /** key of the focused row */
    val focus: String? = null,
    /** who moved focus last */
    val focusBy: FocusSource? = null,
    /** bumped whenever focus should scroll into view */
    val reveal: Int = 0,
    /** a row focus goes to once a layout lists it (an opened thread or an undone row) */
    val pendingFocus: String? = null,
    /** the open thread */
    val open: String? = null,
    /** the open bundle */
    val bundle: OpenBundle<ThreadData>? = null,
    /** the open menu or dropdown, the layer above the open thread and bundle */
    val menu: String? = null,
    /** selected row keys, in the order picked */
    val selection: List<String> = emptyList(),
    /** the last row picked one at a time, where a range starts */
    val selectAnchor: String? = null,
    /** the row the viewport is pinned to */
    val anchor: ListAnchor? = null,
    /** keys an action took out, hidden until the layout drops them too */
    val removing: Set<String> = emptySet(),
    /** what undo puts back */
    val undo: ListUndo? = null,
    /** work the last step asks of the app */
    val effect: ListEffect? = null,
)

/** Everything that changes a list. */
sealed interface ListEvent<out ThreadData, out TodoData> {
    /** The app's data changed: a refetch, new mail, a bundle's page, pinned or focus mode, todos from another device. */
    data class Layout<out ThreadData, out TodoData>(
        /** the new layout */
        val sections: List<LayoutSection<ThreadData, TodoData>>,
    ) : ListEvent<ThreadData, TodoData>

    /** j or ↓. */
    data object Next : ListEvent<Nothing, Nothing>

    /** k or ↑. */
    data object Previous : ListEvent<Nothing, Nothing>

    /** Enter or o on the focused row. */
    data object Enter : ListEvent<Nothing, Nothing>

    /** Escape: closes the top layer, else steps back one level. */
    data object Back : ListEvent<Nothing, Nothing>

    /** A click or tap outside the top layer (the open menu, else thread, else bundle): closes that layer. */
    data object Dismiss : ListEvent<Nothing, Nothing>

    /** A menu or dropdown opened. */
    data class OpenMenu(
        /** the menu's stable id */
        val id: String,
    ) : ListEvent<Nothing, Nothing>

    /** A menu or dropdown closed. */
    data class CloseMenu(
        /** the menu's stable id */
        val id: String,
    ) : ListEvent<Nothing, Nothing>

    /** x: select or unselect the focused row. */
    data object Select : ListEvent<Nothing, Nothing>

    /** An action key: resolves its target into an [ListEffect.Act]. */
    data class Command(
        /** the action's name */
        val action: String,
    ) : ListEvent<Nothing, Nothing>

    /** A row took focus (a click, a tap, Tab, accessibility focus). */
    data class Focus(
        /** row key */
        val key: String,
        /** who moved it */
        val by: FocusSource,
    ) : ListEvent<Nothing, Nothing>

    /** A thread or bundle row was clicked or tapped open (a bundle toggles); with a layer open, a tap outside it closes it. */
    data class Tap(
        /** row key */
        val key: String,
    ) : ListEvent<Nothing, Nothing>

    /** A row the user just made (a new todo): focus goes to it and it scrolls into view, now or once a layout lists it. */
    data class Reveal(
        /** row key */
        val key: String,
    ) : ListEvent<Nothing, Nothing>

    /** Open a thread from outside the list: a notification, a link. */
    data class OpenThread(
        /** Gmail thread id */
        val id: String,
    ) : ListEvent<Nothing, Nothing>

    /** Close the open thread. */
    data object CloseThread : ListEvent<Nothing, Nothing>

    /** Close the open bundle (a tap outside it, its header's close). */
    data object CloseBundle : ListEvent<Nothing, Nothing>

    /** Show every loaded thread of the open bundle. */
    data object ShowAll : ListEvent<Nothing, Nothing>

    /** Select or unselect one row, or ([range]) every row from the last one picked. */
    data class Pick(
        /** row key */
        val key: String,
        /** whether it picks a range */
        val range: Boolean,
    ) : ListEvent<Nothing, Nothing>

    /** Select every selectable row, or none when all are. */
    data object PickAll : ListEvent<Nothing, Nothing>

    /** Drop the selection. */
    data object ClearSelection : ListEvent<Nothing, Nothing>

    /** An action was applied: [removes] are the rows it takes out of this list. */
    data class Act(
        /** row keys it takes out */
        val removes: List<String>,
        /** where it came from */
        val from: ActSource,
        /** whether undo can take it back */
        val undoable: Boolean,
        /** whether it ends the selection it acted on */
        val clearSelection: Boolean,
    ) : ListEvent<Nothing, Nothing>

    /** Undo the last action. */
    data object Undo : ListEvent<Nothing, Nothing>

    /** The undo offer ran out. */
    data object UndoExpired : ListEvent<Nothing, Nothing>

    /** A write failed and its rows come back. */
    data class Restore(
        /** row keys */
        val keys: List<String>,
    ) : ListEvent<Nothing, Nothing>

    /** Where the viewport sits, measured just before a layout. */
    data class Anchor(
        /** row key */
        val key: String,
        /** offset from the viewport top in pixels */
        val offset: Int,
    ) : ListEvent<Nothing, Nothing>

    /** Another view: nothing carries over but undo. */
    data object Reset : ListEvent<Nothing, Nothing>
}

private const val TODO_PREFIX = "todo:"
private const val BUNDLE_PREFIX = "bundle:"
private const val SECTION_PREFIX = "section:"
private const val MORE_PREFIX = "more:"

/** A thread's row key: its id. */
fun threadRowKey(id: String): String = id

/** A todo's row key. */
fun todoRowKey(id: String): String = TODO_PREFIX + id

/** A bundle's row key. */
fun bundleRowKey(labelId: String): String = BUNDLE_PREFIX + labelId

/** A section heading's row key. */
fun sectionRowKey(key: String): String = SECTION_PREFIX + key

/** The todo a row key names, or null. */
fun rowTodoId(key: String?): String? = key?.takeIf { it.startsWith(TODO_PREFIX) }?.removePrefix(TODO_PREFIX)

/** The thread a row key names, or null. */
fun rowThreadId(key: String?): String? =
    key?.takeIf { candidate -> listOf(TODO_PREFIX, BUNDLE_PREFIX, SECTION_PREFIX, MORE_PREFIX).none { candidate.startsWith(it) } }

/** Whether a row takes focus: threads, todos and bundles do, headings and "more" don't. */
fun ListRow<*, *>.isFocusable(): Boolean = this is ListRow.Thread || this is ListRow.Todo || this is ListRow.Bundle

/** Whether a row can be selected: threads and todos. */
fun ListRow<*, *>.isSelectable(): Boolean = this is ListRow.Thread || this is ListRow.Todo

/** The keys of the rows that take focus, in visual order. */
fun ListState<*, *>.focusableKeys(): List<String> = rows.filter { it.isFocusable() }.map { it.key }

/** The keys of the rows that can be selected, in visual order. */
fun ListState<*, *>.selectableKeys(): List<String> = rows.filter { it.isSelectable() }.map { it.key }

/** The row with [key], or null. */
fun <ThreadData, TodoData> ListState<ThreadData, TodoData>.rowOf(key: String?): ListRow<ThreadData, TodoData>? =
    if (key == null) null else rows.firstOrNull { it.key == key }

/** The selected thread ids, in the order picked. */
fun ListState<*, *>.selectedThreads(): List<String> = selection.mapNotNull(::rowThreadId)

/** The selected todo ids, in the order picked. */
fun ListState<*, *>.selectedTodos(): List<String> = selection.mapNotNull(::rowTodoId)

/** What an action key acts on: the open thread, else the selection, else the focused row. */
fun listTarget(state: ListState<*, *>): ListTarget? {
    val open = state.open
    return when {
        open != null -> ListTarget(TargetSource.THREAD, listOf(open), emptyList(), null)
        state.selection.isNotEmpty() -> ListTarget(TargetSource.SELECTION, state.selectedThreads(), state.selectedTodos(), null)
        else -> when (val row = state.rowOf(state.focus)) {
            is ListRow.Thread -> ListTarget(TargetSource.ROW, listOf(row.id), emptyList(), null)
            is ListRow.Todo -> ListTarget(TargetSource.ROW, emptyList(), listOf(row.id), null)
            is ListRow.Bundle -> ListTarget(TargetSource.ROW, row.threads.map { it.id }, emptyList(), row.labelId)
            else -> null
        }
    }
}

/** The threads either side of an open one, which a sideways swipe on it opens. */
data class OpenNeighbors(
    /** the thread above it, or null at the top */
    val previous: String?,
    /** the thread below it, or null at the end */
    val next: String?,
)

/**
 * The threads before and after the open thread, in visual order: inside the open bundle when it
 * shows there, else among the list's own thread rows (todos, bundles and headings are passed over).
 * Both are null with no thread open or its row not listed.
 */
fun openNeighbors(state: ListState<*, *>): OpenNeighbors {
    val open = state.open?.let { state.rowOf(threadRowKey(it)) } as? ListRow.Thread<*>
        ?: return OpenNeighbors(null, null)
    val peers = state.rows.mapNotNull { row -> (row as? ListRow.Thread<*>)?.takeIf { it.bundle == open.bundle }?.id }
    val at = peers.indexOf(open.id)
    return OpenNeighbors(peers.getOrNull(at - 1), peers.getOrNull(at + 1))
}

/** Where the open thread sits in the open bundle: its label and how many of its loaded threads follow it. */
data class OpenBundleTail(
    /** the bundle's label */
    val labelId: String,
    /** its loaded threads after the open one */
    val after: Int,
)

/**
 * How near the open thread is to the end of the bundle it shows in, so the next page is fetched
 * before a swipe runs out of threads; null with no thread open in a bundle.
 */
fun openBundleTail(state: ListState<*, *>): OpenBundleTail? {
    val open = state.open?.let { state.rowOf(threadRowKey(it)) } as? ListRow.Thread<*> ?: return null
    val labelId = open.bundle ?: return null
    val header = state.rows.firstOrNull { it is ListRow.Bundle<*> && it.labelId == labelId } as? ListRow.Bundle<*>
        ?: return null
    val at = header.threads.indexOfFirst { it.id == open.id }
    return OpenBundleTail(labelId, header.threads.size - at - 1)
}

// The first key after `key` in `order` that `alive` keeps, else the last one before it.
private fun survivor(order: List<String>, key: String, alive: (String) -> Boolean): String? {
    val index = order.indexOf(key)
    return if (index == -1) {
        null
    } else {
        order.subList(index + 1, order.size).firstOrNull(alive) ?: order.subList(0, index).lastOrNull(alive)
    }
}

/** Every key [layout] holds anywhere, rows or not: threads (in bundles too), todos, bundles. */
fun layoutKeys(layout: List<LayoutSection<*, *>>): Set<String> {
    val keys = hashSetOf<String>()
    fun addThread(thread: LayoutThread<*>) {
        keys += threadRowKey(thread.id)
    }
    for (section in layout) {
        for (entry in section.entries) {
            when (entry) {
                is LayoutEntry.Thread -> addThread(entry.thread)
                is LayoutEntry.Todo -> keys += todoRowKey(entry.id)
                is LayoutEntry.Bundle -> {
                    keys += bundleRowKey(entry.labelId)
                    entry.threads.forEach(::addThread)
                }
            }
        }
    }
    return keys
}

private data class Built<ThreadData, TodoData>(
    val rows: List<ListRow<ThreadData, TodoData>>,
    val bundle: OpenBundle<ThreadData>?,
)

// The rows a layout makes, given the open bundle and the rows on their way out. Each thread and
// todo shows once, where it first appears; a heading shows only over rows.
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
private fun <ThreadData, TodoData> build(
    layout: List<LayoutSection<ThreadData, TodoData>>,
    bundle: OpenBundle<ThreadData>?,
    removing: Set<String>,
    open: String?,
): Built<ThreadData, TodoData> {
    val seen = hashSetOf<String>()
    val rows = mutableListOf<ListRow<ThreadData, TodoData>>()
    var openBundle: OpenBundle<ThreadData>? = null
    // A thread, unless shown already or on its way out.
    fun take(thread: LayoutThread<ThreadData>): LayoutThread<ThreadData>? {
        val key = threadRowKey(thread.id)
        return if (key in seen || key in removing) {
            null
        } else {
            seen += key
            thread
        }
    }
    for (section in layout) {
        val sectionRows = mutableListOf<ListRow<ThreadData, TodoData>>()
        for (entry in section.entries) {
            when (entry) {
                is LayoutEntry.Thread -> {
                    val thread = take(entry.thread) ?: continue
                    sectionRows += ListRow.Thread(threadRowKey(thread.id), thread.id, section.key, null, thread.data)
                }
                is LayoutEntry.Todo -> {
                    val key = todoRowKey(entry.id)
                    if (key in seen || key in removing) continue
                    seen += key
                    sectionRows += ListRow.Todo(key, entry.id, section.key, entry.data)
                }
                is LayoutEntry.Bundle -> {
                    val key = bundleRowKey(entry.labelId)
                    if (key in seen) continue
                    seen += key
                    val isOpen = bundle != null && bundle.labelId == entry.labelId
                    val threads = entry.threads.mapNotNull(::take)
                    if (threads.isEmpty() && !(isOpen && (entry.more || entry.loading))) continue
                    sectionRows += ListRow.Bundle(key, entry.labelId, section.key, threads, isOpen, entry.more, entry.loading)
                    if (bundle == null || !isOpen) continue
                    val holdsOpen = open != null && threads.any { it.id == open }
                    val showAll = bundle.showAll || holdsOpen
                    openBundle = bundle.copy(showAll = showAll, threads = threads)
                    val shown = if (showAll) threads else threads.take(GROUP_PREVIEW)
                    for (thread in shown) {
                        sectionRows += ListRow.Thread(threadRowKey(thread.id), thread.id, section.key, entry.labelId, thread.data)
                    }
                    val hidden = threads.size - shown.size
                    if (hidden > 0 || entry.more || entry.loading) {
                        sectionRows += ListRow.More(MORE_PREFIX + entry.labelId, entry.labelId, section.key, hidden, entry.more, entry.loading)
                    }
                }
            }
        }
        if (sectionRows.isNotEmpty()) {
            rows += ListRow.Section(sectionRowKey(section.key), section.key, section.title, section.doneAll)
            rows += sectionRows
        }
    }
    return Built(rows, openBundle)
}

// The state with its rows rebuilt and everything that points at rows made to point at rows that
// exist: focus (to the next row, else the previous), the open thread, selection, the anchor.
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun <ThreadData, TodoData> settle(
    before: ListState<ThreadData, TodoData>,
    next: ListState<ThreadData, TodoData>,
): ListState<ThreadData, TodoData> {
    val built = build(next.layout, next.bundle, next.removing, next.open)
    val rows = built.rows
    val keys = rows.mapTo(hashSetOf()) { it.key }
    val focusable = rows.filter { it.isFocusable() }.mapTo(hashSetOf()) { it.key }
    val beforeFocusable = before.focusableKeys()
    var focus = next.focus
    var pendingFocus = next.pendingFocus
    var open = next.open
    var reveal = next.reveal
    var focusBy = next.focusBy
    var effect = next.effect
    var bundle = built.bundle

    // An open thread whose row left closes; focus goes where its row was.
    if (open != null && open !in keys && pendingFocus != open) {
        if (focus == null || focus == open) focus = open
        open = null
    }
    // A row waited for that came back inside a closed bundle is found at the bundle.
    val waited = pendingFocus
    if (waited != null && waited != open && waited !in focusable) {
        val holder = rows.firstOrNull { row -> row is ListRow.Bundle && !row.open && row.threads.any { threadRowKey(it.id) == waited } }
        if (holder != null) pendingFocus = holder.key
    }
    if (pendingFocus != null && pendingFocus in focusable) {
        focus = pendingFocus
        pendingFocus = null
        reveal += 1
    }
    val awaiting = bundle?.awaiting
    if (bundle != null && awaiting != null) {
        val labelId = bundle.labelId
        val known = before.bundle?.threads.orEmpty().mapTo(hashSetOf()) { it.id }
        val bundleRow = rows.firstOrNull { it is ListRow.Bundle && it.labelId == labelId } as ListRow.Bundle?
        // The page lands after the row focus left; new mail at the bundle's top doesn't count.
        val inside = rows.mapNotNull { row -> (row as? ListRow.Thread)?.takeIf { it.bundle == labelId } }
        val from = inside.indexOfFirst { it.key == awaiting }
        val fresh = inside.drop(from + 1).firstOrNull { it.id !in known }
        if (fresh != null) {
            focus = fresh.key
            reveal += 1
            bundle = bundle.copy(awaiting = null)
        } else if (bundleRow != null && !bundleRow.loading && (!bundleRow.more || loadingBefore(before, labelId))) {
            // Gmail has no more, or a page came and brought nothing to show.
            focus = if (awaiting in focusable) awaiting else focus
            bundle = bundle.copy(awaiting = null)
        }
    }
    val lost = focus
    if (lost != null && lost !in focusable) {
        focus = survivor(beforeFocusable, lost) { it in focusable }
        // Inside an open bundle focus stays in it: its next thread (one shown in the lost one's place
        // counts), else, once Gmail is known to have no more, its previous one.
        val near = bundle?.takeIf { bundleOf(before, lost) == it.labelId }?.let { bundleNeighbors(before, rows, lost, it.labelId) }
        if (near?.next != null && bundleIn(rows, focus) != bundle.labelId) focus = near.next
        // The open bundle's last shown thread went while Gmail has more: focus waits on its header
        // for the next page rather than leaving the bundle.
        val header = bundle?.let { open -> rows.firstOrNull { it is ListRow.Bundle && it.labelId == open.labelId } as ListRow.Bundle? }
        if (
            bundle != null &&
            bundle.awaiting == null &&
            header != null &&
            header.more &&
            bundleOf(before, lost) == bundle.labelId &&
            rows.none { it.key == focus && it is ListRow.Thread && it.bundle == bundle.labelId }
        ) {
            focus = header.key
            reveal += 1
            bundle = bundle.copy(showAll = true, awaiting = lost)
            if (!header.loading) effect = ListEffect.LoadMore(bundle.labelId)
        } else if (near?.previous != null && bundleIn(rows, focus) != bundle.labelId) {
            focus = near.previous
        }
    }
    if (bundle?.awaiting != null && focus != bundleRowKey(bundle.labelId)) {
        bundle = bundle.copy(awaiting = null)
    }
    if (focus == null) focusBy = null
    // Focus outside the open bundle, by any route, closes it.
    if (bundle != null && bundleIn(rows, focus) != bundle.labelId) {
        val shut = bundle.labelId
        return settle(
            before,
            next.copy(
                focus = focus,
                focusBy = focusBy,
                reveal = reveal,
                pendingFocus = pendingFocus,
                effect = effect,
                bundle = null,
                open = if (open != null && bundleIn(rows, open) == shut) null else open,
            ),
        )
    }
    // Rows a refetch dropped are gone for good; the ones still listed stay hidden until it does.
    val listed = layoutKeys(next.layout)
    val removing = next.removing.filterTo(linkedSetOf()) { it in listed }
    val selectable = rows.filter { it.isSelectable() }.mapTo(hashSetOf()) { it.key }
    val selection = next.selection.filter { it in selectable }
    val selectAnchor = next.selectAnchor?.takeIf { it in selectable }
    var anchor = next.anchor
    if (anchor != null && anchor.key !in keys) {
        val moved = survivor(before.rows.map { it.key }, anchor.key) { it in keys }
        anchor = moved?.let { ListAnchor(it, anchor.offset) }
    }
    return next.copy(
        rows = rows,
        focus = focus,
        focusBy = focusBy,
        reveal = reveal,
        pendingFocus = pendingFocus,
        open = open,
        bundle = bundle,
        effect = effect,
        selection = if (selection.size == next.selection.size) next.selection else selection,
        selectAnchor = selectAnchor,
        anchor = anchor,
        removing = if (removing.size == next.removing.size) next.removing else removing,
    )
}

// The threads of bundle `labelId` around `lost` that `rows` shows: the first after the last one
// kept before it (one newly shown in its place included), and that last one kept before it.
private data class BundleNeighbors(val next: String?, val previous: String?)

private fun bundleNeighbors(before: ListState<*, *>, rows: List<ListRow<*, *>>, lost: String, labelId: String): BundleNeighbors {
    fun inside(row: ListRow<*, *>?) = row is ListRow.Thread && row.bundle == labelId
    val shown = rows.withIndex().associate { (index, row) -> row.key to index }
    val at = before.rows.indexOfFirst { it.key == lost }
    val previous = before.rows.subList(0, at.coerceAtLeast(0)).asReversed()
        .firstOrNull { row -> shown[row.key]?.let { inside(rows[it]) } == true }?.key
    val floor = shown[previous ?: bundleRowKey(labelId)]
    val next = floor?.let { rows.drop(it + 1).firstOrNull { row -> inside(row) && row.key != lost } }
    return BundleNeighbors(next?.key, previous)
}

// The bundle row holding `key` (its header or one of its shown threads), or null.
private fun bundleOf(state: ListState<*, *>, key: String?): String? = bundleIn(state.rows, key)

// The bundle in `rows` holding `key` (its header, a shown thread or its "more" row), or null.
private fun bundleIn(rows: List<ListRow<*, *>>, key: String?): String? =
    when (val row = if (key == null) null else rows.firstOrNull { it.key == key }) {
        is ListRow.Bundle -> row.labelId
        is ListRow.Thread -> row.bundle
        is ListRow.More -> row.labelId
        else -> null
    }

// The label of the bundle that lists thread `id`, open or closed, or null.
private fun bundleListing(state: ListState<*, *>, id: String): String? =
    state.rows.firstNotNullOfOrNull { row -> (row as? ListRow.Bundle)?.takeIf { bundle -> bundle.threads.any { it.id == id } }?.labelId }

private fun <ThreadData> openBundle(state: ListState<ThreadData, *>, labelId: String): OpenBundle<ThreadData> {
    val row = state.rows.firstOrNull { it is ListRow.Bundle && it.labelId == labelId } as ListRow.Bundle<ThreadData>?
    return OpenBundle(labelId, showAll = false, awaiting = null, threads = row?.threads.orEmpty())
}

// Focus moved by keys to `key`: it scrolls into view, a bundle it leaves closes, and an open
// thread gives way to the thread focus lands on.
private fun <ThreadData, TodoData> keyFocus(state: ListState<ThreadData, TodoData>, key: String): ListState<ThreadData, TodoData> {
    val bundle = state.bundle
    val left = bundle != null && bundleOf(state, state.focus) == bundle.labelId && bundleOf(state, key) != bundle.labelId
    var open = state.open
    if (open != null && open != key) {
        open = if (state.rowOf(key) is ListRow.Thread) key else null
    }
    return state.copy(
        focus = key,
        focusBy = FocusSource.KEYBOARD,
        reveal = state.reveal + 1,
        pendingFocus = null,
        open = open,
        bundle = if (left) null else bundle,
    )
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
private fun <ThreadData, TodoData> moveFocus(state: ListState<ThreadData, TodoData>, forward: Boolean): ListState<ThreadData, TodoData> {
    val order = state.focusableKeys()
    if (order.isEmpty()) return state
    val index = if (state.focus == null) -1 else order.indexOf(state.focus)
    val bundle = state.bundle
    val row = state.rowOf(state.focus)
    if (bundle?.awaiting != null && forward) {
        // Still waiting: ask again unless a page is already on its way.
        val header = state.rowOf(bundleRowKey(bundle.labelId)) as? ListRow.Bundle
        return if (header != null && !header.loading && header.more) state.copy(effect = ListEffect.LoadMore(bundle.labelId)) else state
    }
    if (forward && bundle != null && row != null) {
        val inBundle = state.rows.filter { it is ListRow.Thread && it.bundle == bundle.labelId }
        val last = inBundle.lastOrNull()
        val header = state.rows.firstOrNull { it is ListRow.Bundle && it.labelId == bundle.labelId } as ListRow.Bundle?
        val atEnd = (last != null && last.key == row.key) || (last == null && header?.key == row.key)
        if (atEnd && header != null) {
            if (header.threads.size > inBundle.size) {
                val shown = settle(state, state.copy(bundle = bundle.copy(showAll = true)))
                return keyFocus(shown, threadRowKey(header.threads[inBundle.size].id))
            }
            if (header.more) {
                return keyFocus(state, header.key).copy(
                    bundle = bundle.copy(showAll = true, awaiting = row.key),
                    effect = if (header.loading) null else ListEffect.LoadMore(bundle.labelId),
                )
            }
        }
    }
    val at = when {
        index == -1 && forward -> 0
        index == -1 -> order.size - 1
        else -> (index + if (forward) 1 else -1).coerceIn(0, order.size - 1)
    }
    val key = order[at]
    return if (key == state.focus) {
        state.copy(focusBy = FocusSource.KEYBOARD, reveal = state.reveal + 1)
    } else {
        keyFocus(state, key)
    }
}

private fun <ThreadData, TodoData> back(state: ListState<ThreadData, TodoData>): ListState<ThreadData, TodoData> = when {
    state.menu != null -> state.copy(menu = null)
    state.open != null -> state.copy(open = null, focus = state.open, focusBy = FocusSource.KEYBOARD, pendingFocus = null)
    state.selection.isNotEmpty() -> state.copy(selection = emptyList(), selectAnchor = null)
    state.bundle != null -> closeBundle(state)
    state.focus != null -> state.copy(focus = null, focusBy = null, pendingFocus = null)
    else -> state
}

private fun <ThreadData, TodoData> focusRow(state: ListState<ThreadData, TodoData>, event: ListEvent.Focus): ListState<ThreadData, TodoData> {
    val row = state.rowOf(event.key)
    return if (row == null || !row.isFocusable() || state.focus == event.key) {
        state
    } else {
        // Tab is the keyboard too: leaving the open bundle closes it. Neither opens a thread.
        val bundle = state.bundle
        val left = event.by == FocusSource.KEYBOARD &&
            bundle != null &&
            bundleOf(state, state.focus) == bundle.labelId &&
            bundleOf(state, event.key) != bundle.labelId
        state.copy(
            focus = event.key,
            focusBy = event.by,
            pendingFocus = null,
            open = if (state.open == event.key) state.open else null,
            bundle = if (left) null else bundle,
        )
    }
}

private fun <ThreadData, TodoData> act(state: ListState<ThreadData, TodoData>, event: ListEvent.Act): ListState<ThreadData, TodoData> {
    val listed = layoutKeys(state.layout)
    val shown = state.rows.mapTo(hashSetOf()) { it.key }
    // A bundle goes with its threads: it shows again when Gmail has more of them.
    val removes = event.removes
        .flatMap { key ->
            when (val row = state.rowOf(key)) {
                is ListRow.Bundle -> row.threads.map { threadRowKey(it.id) }
                else -> listOf(key)
            }
        }
        .filter { key -> (rowThreadId(key) != null || rowTodoId(key) != null) && (key in listed || key in shown) }
    val closes = state.open != null && state.open in removes
    return state.copy(
        removing = if (removes.isEmpty()) state.removing else state.removing + removes,
        open = if (closes) null else state.open,
        focus = if (closes) state.open else state.focus,
        pendingFocus = state.pendingFocus?.takeUnless { it in removes },
        selection = if (event.from == ActSource.SELECTION && event.clearSelection) emptyList() else state.selection,
        undo = if (event.undoable) ListUndo(removes, if (closes) state.open else state.focus) else state.undo,
    )
}

// A tap on the row with [key]. A tap outside the top layer closes that layer rather than acting.
private fun <ThreadData, TodoData> tap(state: ListState<ThreadData, TodoData>, key: String): ListState<ThreadData, TodoData> {
    val row = state.rowOf(key) ?: return state
    val bundle = state.bundle
    val outside = bundle != null && bundleOf(state, row.key) != bundle.labelId
    return when {
        state.menu != null -> state.copy(menu = null)
        outside && state.open != null -> closeThread(state)
        outside -> closeBundle(state).copy(focusBy = FocusSource.POINTER)
        else -> when (row) {
            is ListRow.Bundle -> toggleBundle(state, row.labelId).copy(focusBy = FocusSource.POINTER)
            is ListRow.Todo -> focusRow(state, ListEvent.Focus(row.key, FocusSource.POINTER))
            is ListRow.Thread -> openThread(state, row.id).copy(focusBy = FocusSource.POINTER)
            else -> state
        }
    }
}

// Whether the open bundle's row showed a page on its way before this step.
private fun loadingBefore(before: ListState<*, *>, labelId: String): Boolean =
    before.rows.any { it is ListRow.Bundle && it.labelId == labelId && it.loading }

private fun <ThreadData, TodoData> undo(state: ListState<ThreadData, TodoData>): ListState<ThreadData, TodoData> {
    val undo = state.undo
    return when {
        undo == null -> state
        undo.keys.isEmpty() -> state.copy(undo = null)
        else -> {
            val back = undo.keys.toSet()
            val target = undo.focus?.takeIf { it in back || it in layoutKeys(state.layout) } ?: undo.keys.first()
            state.copy(
                removing = state.removing.filterTo(linkedSetOf()) { it !in back },
                pendingFocus = target,
                focusBy = FocusSource.KEYBOARD,
                // Focus goes back to the row undone, so a thread open elsewhere closes.
                open = if (state.open == target) state.open else null,
                undo = null,
            )
        }
    }
}

private fun <ThreadData, TodoData> pick(state: ListState<ThreadData, TodoData>, event: ListEvent.Pick): ListState<ThreadData, TodoData> {
    val row = state.rowOf(event.key)
    return if (row == null || !row.isSelectable()) {
        state
    } else {
        val selection = if (event.range) {
            selectRange(state.selection, state.selectableKeys(), state.selectAnchor, row.key)
        } else {
            toggleSelected(state.selection, row.key)
        }
        state.copy(selection = selection, selectAnchor = row.key)
    }
}

@Suppress("CyclomaticComplexMethod")
private fun <ThreadData, TodoData> step(
    state: ListState<ThreadData, TodoData>,
    event: ListEvent<ThreadData, TodoData>,
): ListState<ThreadData, TodoData> = when (event) {
    is ListEvent.Layout -> state.copy(layout = event.sections)
    ListEvent.Next -> moveFocus(state, forward = true)
    ListEvent.Previous -> moveFocus(state, forward = false)
    ListEvent.Enter -> when (val row = state.rowOf(state.focus)) {
        is ListRow.Bundle -> toggleBundle(state, row.labelId)
        is ListRow.Todo -> state.copy(effect = ListEffect.Edit(row.id))
        is ListRow.Thread -> if (state.open == row.key) state else openThread(state, row.id)
        else -> state
    }
    ListEvent.Back -> back(state)
    ListEvent.Select -> {
        val row = state.rowOf(state.focus)
        if (row == null || !row.isSelectable()) state else state.copy(selection = toggleSelected(state.selection, row.key), selectAnchor = row.key)
    }
    is ListEvent.Command -> listTarget(state)?.let { state.copy(effect = ListEffect.Act(event.action, it)) } ?: state
    is ListEvent.Focus -> focusRow(state, event)
    is ListEvent.Tap -> tap(state, event.key)
    is ListEvent.Reveal -> {
        val row = state.rowOf(event.key)
        when {
            row == null -> state.copy(open = null, pendingFocus = event.key)
            !row.isFocusable() -> state
            else -> keyFocus(state, event.key).copy(focusBy = FocusSource.POINTER)
        }
    }
    is ListEvent.OpenThread -> openThread(state, event.id)
    ListEvent.CloseThread -> closeThread(state)
    ListEvent.CloseBundle -> closeBundle(state)
    ListEvent.Dismiss -> when {
        state.menu != null -> state.copy(menu = null)
        state.open != null -> closeThread(state)
        else -> closeBundle(state)
    }
    is ListEvent.OpenMenu -> if (state.menu == event.id) state else state.copy(menu = event.id)
    is ListEvent.CloseMenu -> if (state.menu == event.id) state.copy(menu = null) else state
    ListEvent.ShowAll -> state.bundle?.let { state.copy(bundle = it.copy(showAll = true)) } ?: state
    is ListEvent.Pick -> pick(state, event)
    ListEvent.PickAll -> {
        val all = state.selectableKeys()
        val every = all.isNotEmpty() && all.all { it in state.selection }
        state.copy(selection = if (every) emptyList() else all, selectAnchor = null)
    }
    ListEvent.ClearSelection -> state.copy(selection = emptyList(), selectAnchor = null)
    is ListEvent.Act -> act(state, event)
    ListEvent.Undo -> undo(state)
    ListEvent.UndoExpired -> if (state.undo == null) state else state.copy(undo = null)
    is ListEvent.Restore -> {
        val back = event.keys.toSet()
        state.copy(removing = state.removing.filterTo(linkedSetOf()) { it !in back })
    }
    is ListEvent.Anchor -> state.copy(anchor = ListAnchor(event.key, event.offset))
    ListEvent.Reset -> ListState(layout = state.layout, undo = state.undo, reveal = state.reveal)
}

private fun <ThreadData, TodoData> closeThread(state: ListState<ThreadData, TodoData>): ListState<ThreadData, TodoData> =
    if (state.open == null) {
        state
    } else {
        state.copy(
            open = null,
            focus = if (state.rowOf(state.open) == null) state.focus else state.open,
            pendingFocus = if (state.pendingFocus == state.open) null else state.pendingFocus,
        )
    }

private fun <ThreadData, TodoData> openThread(state: ListState<ThreadData, TodoData>, id: String): ListState<ThreadData, TodoData> {
    val key = threadRowKey(id)
    val holder = bundleListing(state, id)
    val bundle = when {
        holder == null -> null
        state.bundle?.labelId != holder -> openBundle(state, holder).copy(showAll = true)
        else -> state.bundle
    }
    val listed = state.rows.any { it.key == key } || holder != null
    return state.copy(
        open = id,
        bundle = bundle,
        focus = if (listed) key else state.focus,
        pendingFocus = if (listed) null else key,
    )
}

private fun <ThreadData, TodoData> toggleBundle(state: ListState<ThreadData, TodoData>, labelId: String): ListState<ThreadData, TodoData> {
    val key = bundleRowKey(labelId)
    return if (state.bundle?.labelId == labelId) {
        closeBundle(state).copy(open = null, focus = key)
    } else {
        state.copy(open = null, bundle = openBundle(state, labelId), focus = key, pendingFocus = null)
    }
}

// Close the open bundle (and a thread open in it); focus inside it goes to its header.
private fun <ThreadData, TodoData> closeBundle(state: ListState<ThreadData, TodoData>): ListState<ThreadData, TodoData> {
    val bundle = state.bundle ?: return state
    val inside = bundleOf(state, state.focus) == bundle.labelId
    val openInside = state.open != null && bundleOf(state, state.open) == bundle.labelId
    return state.copy(
        bundle = null,
        open = if (openInside) null else state.open,
        focus = if (inside) bundleRowKey(bundle.labelId) else state.focus,
    )
}

/**
 * The list after [event]: the one transition every input goes through.
 *
 * A step applies the event, then rebuilds the rows from the last layout and repairs whatever
 * pointed at a row that is gone: focus moves to the next row in visual order, else the previous;
 * an open thread whose row left closes; selection and the scroll anchor keep only rows that exist.
 * [ListState.effect] carries the work the step asks of the app (a page to load, an action to
 * apply, a todo to edit) and is null after any step that asks none. The rules are in
 * docs/design.md, "The list".
 *
 * ```kotlin
 * var list = listStep(ListState(), ListEvent.Layout(sections))
 * list = listStep(list, ListEvent.Next)
 * ```
 */
fun <ThreadData, TodoData> listStep(
    state: ListState<ThreadData, TodoData>,
    event: ListEvent<ThreadData, TodoData>,
): ListState<ThreadData, TodoData> = settle(state, step(state.copy(effect = null), event))

/** Every way [state] breaks the list's rules (design.md, "The list"); empty when it keeps them all. */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
fun listProblems(state: ListState<*, *>): List<String> {
    val problems = mutableListOf<String>()
    val keys = state.rows.map { it.key }
    val keySet = keys.toSet()
    if (keySet.size != keys.size) problems += "a row key repeats"
    val focusable = state.focusableKeys().toSet()
    val selectable = state.selectableKeys().toSet()
    if (state.focus != null && state.focus !in focusable) problems += "focus ${state.focus} is not a row that takes focus"
    if (state.pendingFocus != null && state.pendingFocus in keySet) problems += "pending focus ${state.pendingFocus} is already listed"
    if (state.open != null && state.focus != state.open && state.pendingFocus != state.open) {
        problems += "open thread ${state.open} doesn't hold focus"
    }
    if (state.open != null && state.open in keySet && state.rowOf(state.open) !is ListRow.Thread) {
        problems += "open ${state.open} is not a thread row"
    }
    if (state.selection.toSet().size != state.selection.size) problems += "selection repeats a row"
    for (key in state.selection) {
        if (key !in selectable) problems += "selected $key is not a row"
    }
    for (key in state.removing) {
        if (key in keySet) problems += "$key shows while on its way out"
    }
    state.anchor?.let { if (it.key !in keySet) problems += "anchor ${it.key} is not a row" }
    // One place per thread and per todo.
    val places = linkedMapOf<String, Int>()
    fun count(key: String) {
        places[key] = (places[key] ?: 0) + 1
    }
    for (row in state.rows) {
        when (row) {
            is ListRow.Thread -> if (row.bundle == null) count(row.key)
            is ListRow.Todo -> count(row.key)
            is ListRow.Bundle -> for (thread in row.threads) count(threadRowKey(thread.id))
            else -> Unit
        }
    }
    for ((key, times) in places) {
        if (times > 1) problems += "$key shows in $times places"
    }
    val openRows = state.rows.filter { it is ListRow.Bundle && it.open }
    if (openRows.size > 1) problems += "more than one bundle is open"
    val bundle = state.bundle
    if (bundle != null) {
        val index = state.rows.indexOfFirst { it is ListRow.Bundle && it.labelId == bundle.labelId }
        val header = state.rows.getOrNull(index) as? ListRow.Bundle
        if (header == null || !header.open) {
            problems += "open bundle ${bundle.labelId} has no open row"
        } else {
            // Its shown threads follow its header, in its order.
            var at = index + 1
            for (thread in header.threads) {
                val row = state.rows.getOrNull(at)
                if (row !is ListRow.Thread || row.id != thread.id) break
                at += 1
            }
            for (row in state.rows.drop(at)) {
                if (row is ListRow.Thread && row.bundle == bundle.labelId) problems += "${row.key} is apart from its bundle"
            }
        }
        if (bundleOf(state, state.focus) != bundle.labelId) {
            problems += "focus ${state.focus} is outside open bundle ${bundle.labelId}"
        }
        if (bundle.awaiting != null && state.focus != bundleRowKey(bundle.labelId)) {
            problems += "focus left the header of a bundle loading for it"
        }
    } else if (openRows.isNotEmpty()) {
        problems += "a bundle row is open with no open bundle"
    }
    for (row in state.rows) {
        if (row is ListRow.Thread && row.bundle != null && row.bundle != bundle?.labelId) {
            problems += "${row.key} shows inside closed bundle ${row.bundle}"
        }
    }
    // A heading heads at least one row.
    state.rows.forEachIndexed { index, row ->
        val after = state.rows.getOrNull(index + 1)
        if (row is ListRow.Section && (after == null || after is ListRow.Section)) problems += "heading ${row.key} heads nothing"
    }
    return problems
}
