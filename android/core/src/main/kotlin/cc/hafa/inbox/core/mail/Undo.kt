package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.label.LabelDelta

// Taking back the last action on threads: which labels undo puts back, whether it puts back the
// snooze, merging label changes made one after another, and what the undo bar says. A port of
// shared/src/undo.ts; both run shared/testdata/undo-fixtures.json.

/** What an undoable action did. */
enum class UndoKind {
    /** marked done */
    DONE,

    /** snoozed */
    SNOOZE,

    /** pinned or unpinned */
    PIN,

    /** reported as spam */
    SPAM,

    /** marked not spam */
    NOT_SPAM,

    /** moved to the trash */
    TRASH,

    /** todos deleted */
    DELETE,

    /** a todo moved, or threads moved to a category */
    MOVE,

    /** labels added or removed */
    LABEL,

    /** marked read */
    READ,

    /** marked unread */
    UNREAD,
}

/** The labels done, snooze, pin, spam and trash touch. */
val UNDOABLE_LABELS: List<String> = listOf(Labels.INBOX, Labels.STARRED, Labels.SPAM, Labels.TRASH)

/** The labels undo puts back for an action that changed [touched] (label changes and moves). */
fun undoLabels(kind: UndoKind, touched: List<String>): List<String> = when (kind) {
    UndoKind.LABEL, UndoKind.MOVE -> touched.distinct()
    UndoKind.READ, UndoKind.UNREAD -> listOf(Labels.UNREAD)
    else -> UNDOABLE_LABELS
}

/** The change that takes a thread's labels from [current] back to [before], for the labels in [restore]. */
fun undoChange(before: List<String>, current: List<String>, restore: List<String>): LabelDelta = LabelDelta(
    add = restore.filter { it in before && it !in current },
    remove = restore.filter { it !in before && it in current },
)

/** Whether undoing the action writes each thread's snooze back as it was. */
fun restoresSnooze(kind: UndoKind): Boolean =
    kind == UndoKind.DONE || kind == UndoKind.SNOOZE || kind == UndoKind.SPAM || kind == UndoKind.TRASH

/**
 * The threads a label change's undo holds when it follows another label change still on offer:
 * each thread as it was before the earlier one, then the threads only the later one touched.
 */
fun <Item> mergeUndoItems(earlier: List<Item>, later: List<Item>, id: (Item) -> String): List<Item> {
    val seen = earlier.map(id).toSet()
    return earlier + later.filterNot { id(it) in seen }
}

/** What the undo bar says about an action on [count] threads and todos. */
fun undoText(kind: UndoKind, count: Int, pinned: Boolean): String {
    val one = count == 1
    return when (kind) {
        UndoKind.SNOOZE -> if (one) "Snoozed" else "$count snoozed"
        UndoKind.PIN -> when {
            pinned -> if (one) "Pinned" else "$count pinned"
            else -> if (one) "Unpinned" else "$count unpinned"
        }
        UndoKind.DELETE -> if (one) "Deleted" else "$count deleted"
        UndoKind.MOVE -> if (one) "Moved" else "$count moved"
        UndoKind.SPAM -> if (one) "Marked as spam" else "$count marked as spam"
        UndoKind.NOT_SPAM -> if (one) "Moved to Inbox" else "$count moved to Inbox"
        UndoKind.TRASH -> if (one) "Moved to trash" else "$count moved to trash"
        UndoKind.LABEL -> if (one) "Labels changed" else "Labels changed on $count"
        UndoKind.READ -> if (one) "Marked as read" else "$count marked as read"
        UndoKind.UNREAD -> if (one) "Marked as unread" else "$count marked as unread"
        UndoKind.DONE -> if (one) "Done" else "$count done"
    }
}
