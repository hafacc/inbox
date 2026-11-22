package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.label.threadCategory

// Selecting several threads in a list and acting on them together; the same rules as web's
// shared/src/selection.ts, pinned by shared/testdata/selection-fixtures.json.

/** A selected thread's snooze status where it is listed. */
enum class SelectedStatus {
    /** snoozed (or listed in Snoozed) */
    SNOOZED,

    /** its snooze ended */
    LAPSED,

    /** not snoozed */
    NONE,
}

/** A selected thread as the selection bar's actions see it. */
data class SelectedThread(
    /** Gmail thread id */
    val id: String,
    /** the labels of all its messages */
    val labelIds: List<String>,
    /** its snooze status where it is listed */
    val status: SelectedStatus,
)

/** A toggle the bar offers for the whole set: what it does, and the threads it changes. */
data class SetToggle(
    /** whether it turns the state on (pin, mark unread, report spam) rather than off */
    val on: Boolean,
    /** the threads it changes */
    val ids: List<String>,
)

/** What each of the selection bar's actions does to the selected threads. */
data class SelectionPlan(
    /** threads Done takes out of the inbox */
    val done: List<String>,
    /** snoozed threads Wake now brings back */
    val wake: List<String>,
    /** Pin: pins the unpinned ones, or unpins all when every one is pinned */
    val pin: SetToggle,
    /** Mark read: reads the unread ones, or marks all unread when none is */
    val unread: SetToggle,
    /** Spam: reports the ones not in spam, or takes all out when every one is */
    val spam: SetToggle,
)

/** A selected todo as the selection bar's actions see it. */
data class SelectedTodo(
    /** todo id */
    val id: String,
    /** whether it is done */
    val done: Boolean,
    /** whether it is pinned */
    val pinned: Boolean,
)

/** Pin across selected threads and todos together, and what it changes in each. */
data class MixedPin(
    /** whether it pins (some thread or open todo isn't pinned) rather than unpins */
    val on: Boolean,
    /** the threads it changes */
    val threads: List<String>,
    /** the todos it changes */
    val todos: List<String>,
)

/** What Done, Snooze and Pin do to selected todos, beside the selected threads. */
data class TodoSelectionPlan(
    /** todos Done marks done */
    val done: List<String>,
    /** todos Snooze snoozes */
    val snooze: List<String>,
    /** Pin across threads and todos */
    val pin: MixedPin,
)

/** Whether every, some or none of the selected threads carry a label. */
enum class LabelState {
    /** every one */
    ALL,

    /** some */
    SOME,

    /** none */
    NONE,
}

/** One label's state across the selected threads, and what choosing it changes. */
data class LabelChoice(
    /** whether every, some or none of the threads carry it */
    val state: LabelState,
    /** threads choosing it adds it to */
    val add: List<String>,
    /** threads choosing it removes it from */
    val remove: List<String>,
)

/** An action the selection bar applies to every selected thread. */
enum class SetAction {
    /** Done */
    DONE,

    /** Snooze */
    SNOOZE,

    /** Wake now */
    WAKE,

    /** Pin or unpin */
    PIN,

    /** Mark read or unread */
    UNREAD,

    /** Report spam or not spam */
    SPAM,

    /** add or remove a label */
    LABEL,

    /** move to a category */
    CATEGORY,
}

// Actions that take threads out of the list, or move them where the selection no longer makes sense.
private val CLEARS = setOf(SetAction.DONE, SetAction.SNOOZE, SetAction.WAKE, SetAction.SPAM, SetAction.CATEGORY)

/** Select [id], or unselect it when it already is; a newly selected id goes last. */
fun toggleSelected(selected: List<String>, id: String): List<String> =
    if (id in selected) selected - id else selected + id

/**
 * Shift-click: select every id from [anchor] to [id] in list [order], keeping what was selected.
 * Without an anchor in the list, or with [id] not in it, it toggles [id] alone.
 */
fun selectRange(selected: List<String>, order: List<String>, anchor: String?, id: String): List<String> {
    val from = if (anchor == null) -1 else order.indexOf(anchor)
    val to = order.indexOf(id)
    return if (from == -1 || to == -1) {
        toggleSelected(selected, id)
    } else {
        val range = order.subList(minOf(from, to), maxOf(from, to) + 1)
        selected + range.filterNot { it in selected }
    }
}

/** The selected ids still shown in the list, in the order they were selected. */
fun keepShown(selected: List<String>, shown: Collection<String>): List<String> {
    val listed = shown.toSet()
    return selected.filter { it in listed }
}

/** Whether the selection ends once [action] is applied to it (true), or stays for the next one. */
fun clearsSelection(action: SetAction): Boolean = action in CLEARS

/** What each of the selection bar's actions would do to [threads]. */
fun selectionPlan(threads: List<SelectedThread>): SelectionPlan {
    val unpinned = threads.filterNot { Labels.STARRED in it.labelIds }
    val unread = threads.filter { Labels.UNREAD in it.labelIds }
    val notSpam = threads.filterNot { Labels.SPAM in it.labelIds }
    val all = threads.map { it.id }
    return SelectionPlan(
        done = notSpam.filter { Labels.INBOX in it.labelIds || it.status != SelectedStatus.NONE }.map { it.id },
        wake = threads.filter { it.status == SelectedStatus.SNOOZED }.map { it.id },
        pin = if (unpinned.isNotEmpty()) SetToggle(true, unpinned.map { it.id }) else SetToggle(false, all),
        unread = if (unread.isNotEmpty()) SetToggle(false, unread.map { it.id }) else SetToggle(true, all),
        spam = if (notSpam.isNotEmpty()) SetToggle(true, notSpam.map { it.id }) else SetToggle(false, all),
    )
}

/** [labelId] across [threads]; choosing it adds it to all unless all carry it, then removes it. */
fun labelChoice(threads: List<SelectedThread>, labelId: String): LabelChoice {
    val carrying = threads.filter { labelId in it.labelIds }
    return when {
        threads.isNotEmpty() && carrying.size == threads.size -> LabelChoice(LabelState.ALL, emptyList(), carrying.map { it.id })
        else -> LabelChoice(
            if (carrying.isEmpty()) LabelState.NONE else LabelState.SOME,
            threads.filterNot { labelId in it.labelIds }.map { it.id },
            emptyList(),
        )
    }
}

/** The category every one of [threads] is in, or null when they differ (or none is given). */
fun selectionCategory(threads: List<SelectedThread>): String? =
    threads.map { threadCategory(it.labelIds) }.toSet().singleOrNull()

/** What Done, Snooze and Pin do to [todos] selected with [threads]; a done todo is left alone. */
fun todoSelectionPlan(threads: List<SelectedThread>, todos: List<SelectedTodo>): TodoSelectionPlan {
    val openIds = todos.filterNot { it.done }.map { it.id }
    val unpinnedThreads = threads.filterNot { Labels.STARRED in it.labelIds }.map { it.id }
    val unpinnedTodos = todos.filter { !it.done && !it.pinned }.map { it.id }
    val pin = if (unpinnedThreads.isNotEmpty() || unpinnedTodos.isNotEmpty()) {
        MixedPin(true, unpinnedThreads, unpinnedTodos)
    } else {
        MixedPin(false, threads.map { it.id }, openIds)
    }
    return TodoSelectionPlan(done = openIds, snooze = openIds, pin = pin)
}

/** Whether every one of [shown] is selected (and there is at least one). */
fun allSelected(selected: List<String>, shown: List<String>): Boolean =
    shown.isNotEmpty() && shown.all { it in selected }

/** Select all: every one of [shown], in list order; or none when all already are. */
fun selectAll(selected: List<String>, shown: List<String>): List<String> =
    if (allSelected(selected, shown)) emptyList() else shown.toList()

/** An action the selection bar can offer, in bar order. */
enum class SelectionAction {
    /** Wake now */
    WAKE,

    /** Snooze */
    SNOOZE,

    /** Pin or unpin */
    PIN,

    /** add or remove labels */
    LABEL,

    /** move to a label */
    MOVE,

    /** move to a category */
    CATEGORY,

    /** mark read or unread */
    READ,

    /** report spam or not spam */
    SPAM,

    /** move to the inbox (threads neither in it nor snoozed) */
    INBOX,

    /** delete (todos) */
    DELETE,

    /** Done */
    DONE,
}

// In bar order.
private val THREAD_ACTIONS = listOf(
    SelectionAction.WAKE,
    SelectionAction.SNOOZE,
    SelectionAction.PIN,
    SelectionAction.LABEL,
    SelectionAction.MOVE,
    SelectionAction.CATEGORY,
    SelectionAction.READ,
    SelectionAction.SPAM,
    SelectionAction.INBOX,
    SelectionAction.DONE,
)
private val TODO_ACTIONS = listOf(SelectionAction.SNOOZE, SelectionAction.PIN, SelectionAction.DELETE, SelectionAction.DONE)

/**
 * The actions the selection bar offers for [threads] selected threads and [todos] selected todos:
 * each kind's own, or only those both support when the selection mixes them. Whether one is shown
 * also depends on the rows (Wake needs a snoozed thread; see [selectionPlan]).
 */
fun selectionActions(threads: Int, todos: Int): List<SelectionAction> = when {
    threads > 0 && todos > 0 -> THREAD_ACTIONS.filter { it in TODO_ACTIONS }
    threads > 0 -> THREAD_ACTIONS
    todos > 0 -> TODO_ACTIONS
    else -> emptyList()
}

/** Done and Move to inbox across selected threads ([inboxPlan]), as the web's `selectionInboxPlan`. */
fun selectionInboxPlan(threads: List<SelectedThread>): InboxPlan =
    inboxPlan(threads.map { it.id to FilteredThread(it.labelIds, it.status == SelectedStatus.SNOOZED) })

/** An action the selection's corner button offers on a phone. */
enum class SelectionDiscAction {
    /** Done */
    DONE,

    /** move to the inbox */
    INBOX,

    /** snooze */
    SNOOZE,

    /** wake now */
    WAKE,

    /** pin or unpin */
    PIN,

    /** change labels */
    LABEL,

    /** mark read or unread */
    READ,

    /** report spam or not spam */
    SPAM,

    /** delete (todos) */
    DELETE,
}

/** The selection's actions on a phone's corner button: the ring next to it and the ring beyond, each from the top. */
data class SelectionDisc(
    /** Done (or Move to inbox), Snooze (or Wake now) and Pin */
    val inner: List<SelectionDiscAction>,
    /** the rest */
    val outer: List<SelectionDiscAction>,
)

// The outer ring's order, from the top.
private val DISC_OUTER = listOf(
    SelectionDiscAction.SNOOZE,
    SelectionDiscAction.DONE,
    SelectionDiscAction.LABEL,
    SelectionDiscAction.READ,
    SelectionDiscAction.SPAM,
    SelectionDiscAction.DELETE,
)

/**
 * Where the corner button puts each of [available] (the actions that would change something): the
 * inner ring holds Done, Snooze and Pin, Move to inbox taking Done's place and Wake now Snooze's
 * wherever they apply; a Done or Snooze so displaced, and every other action, go on the outer ring.
 */
fun selectionDisc(available: Collection<SelectionDiscAction>): SelectionDisc {
    val first = when {
        SelectionDiscAction.INBOX in available -> SelectionDiscAction.INBOX
        SelectionDiscAction.DONE in available -> SelectionDiscAction.DONE
        else -> null
    }
    val second = when {
        SelectionDiscAction.WAKE in available -> SelectionDiscAction.WAKE
        SelectionDiscAction.SNOOZE in available -> SelectionDiscAction.SNOOZE
        else -> null
    }
    val inner = listOfNotNull(first, second, SelectionDiscAction.PIN.takeIf { it in available })
    return SelectionDisc(inner, DISC_OUTER.filter { it in available && it !in inner })
}
