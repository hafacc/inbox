package cc.hafa.inbox.core.mail

// What an open thread offers, so every action a thread takes anywhere else is there too. Ported
// from threadActions in shared/src/view-filter.ts; both run shared/testdata/thread-action-fixtures.json.

/** An action an open thread offers. */
enum class ThreadAction {
    /** archive it */
    DONE,

    /** move it back to the inbox */
    INBOX,

    /** snooze it, or change its snooze time */
    SNOOZE,

    /** end its snooze now */
    WAKE,

    /** pin it */
    PIN,

    /** unpin it */
    UNPIN,

    /** add a todo to it */
    ADD_TODO,

    /** edit its todo */
    EDIT_TODO,

    /** change its labels */
    LABEL,

    /** mark it important */
    IMPORTANT,

    /** mark it not important */
    UNIMPORTANT,

    /** mark it read */
    READ,

    /** mark it unread */
    UNREAD,

    /** report it as spam */
    SPAM,

    /** take it out of spam */
    NOT_SPAM,

    /** move it to the trash */
    DELETE,

    /** take it out of the trash, back to the inbox */
    UNTRASH,

    /** make a filter for mail like it */
    FILTER,
}

/** The todo action an open thread offers. */
enum class ThreadTodoAction {
    /** add a todo */
    ADD,

    /** edit its todo */
    EDIT,
}

/**
 * The actions an open thread with [labelIds] offers, in order: Done or Move to inbox (see
 * [inboxAction]), Snooze, Wake now when [snoozed], Pin or Unpin, Label, Add or Edit todo ([todo],
 * null while todos are off), Mark important or not, Mark read or unread ([unread]), Report spam or
 * Not spam, Delete (Move to inbox from the trash), and Filter mail like this when [hasSender].
 */
fun threadActions(
    labelIds: List<String>,
    snoozed: Boolean,
    unread: Boolean,
    todo: ThreadTodoAction?,
    hasSender: Boolean,
): List<ThreadAction> = buildList {
    when (inboxAction(FilteredThread(labelIds, snoozed))) {
        InboxAction.DONE -> add(ThreadAction.DONE)
        InboxAction.INBOX -> add(ThreadAction.INBOX)
        null -> Unit
    }
    add(ThreadAction.SNOOZE)
    if (snoozed) add(ThreadAction.WAKE)
    add(if (Labels.STARRED in labelIds) ThreadAction.UNPIN else ThreadAction.PIN)
    add(ThreadAction.LABEL)
    when (todo) {
        ThreadTodoAction.ADD -> add(ThreadAction.ADD_TODO)
        ThreadTodoAction.EDIT -> add(ThreadAction.EDIT_TODO)
        null -> Unit
    }
    add(if (Labels.IMPORTANT in labelIds) ThreadAction.UNIMPORTANT else ThreadAction.IMPORTANT)
    add(if (unread) ThreadAction.READ else ThreadAction.UNREAD)
    add(if (Labels.SPAM in labelIds) ThreadAction.NOT_SPAM else ThreadAction.SPAM)
    add(if (Labels.TRASH in labelIds) ThreadAction.UNTRASH else ThreadAction.DELETE)
    if (hasSender) add(ThreadAction.FILTER)
}

/** Where a phone shows an open thread's actions. */
data class ThreadActionPlaces(
    /** the corner button's ring next to it, from the top: Done or Move to inbox, Snooze, and Pin or Unpin only when one of those is missing */
    val inner: List<ThreadAction>,
    /** the corner button's ring beyond, from the top: Pin or Unpin, Label, Add or Edit todo, Mark read or unread, Report spam or Not spam, Delete or Move to inbox */
    val outer: List<ThreadAction>,
    /** the marker beside the subject: Mark important or not */
    val marker: ThreadAction?,
    /** the line above the messages: Wake now and Filter mail like this */
    val line: List<ThreadAction>,
)

private val LINE_ACTIONS = setOf(ThreadAction.WAKE, ThreadAction.FILTER)

// The corner button's rings, each from the top.
private val DISC_INNER = listOf(ThreadAction.DONE, ThreadAction.INBOX, ThreadAction.SNOOZE)
private val DISC_PIN = listOf(ThreadAction.PIN, ThreadAction.UNPIN)
private val DISC_OUTER = listOf(
    ThreadAction.LABEL,
    ThreadAction.ADD_TODO,
    ThreadAction.EDIT_TODO,
    ThreadAction.READ,
    ThreadAction.UNREAD,
    ThreadAction.SPAM,
    ThreadAction.NOT_SPAM,
    ThreadAction.DELETE,
    ThreadAction.UNTRASH,
)

/**
 * Where a phone shows each of [actions] (from [threadActions]): Mark important as a marker beside
 * the subject, Wake now and Filter mail like this on the line above the messages, and on the corner
 * button's two rings: Done (Move to inbox) and Snooze next to it, then Pin (Unpin), Label, Add or
 * Edit todo, Mark read or unread, Report spam (Not spam) and Delete (Move to inbox from the trash)
 * beyond, in the selection's order. Where Done has no place (spam, trash) Pin stays next to Snooze,
 * so neither ring holds a lone action.
 */
fun threadActionPlaces(actions: List<ThreadAction>): ThreadActionPlaces {
    val inner = DISC_INNER.filter { it in actions }
    val pin = DISC_PIN.filter { it in actions }
    val outer = DISC_OUTER.filter { it in actions }
    return ThreadActionPlaces(
        inner = if (inner.size < 2) inner + pin else inner,
        outer = if (inner.size < 2) outer else pin + outer,
        marker = actions.firstOrNull { it == ThreadAction.IMPORTANT || it == ThreadAction.UNIMPORTANT },
        line = actions.filter { it in LINE_ACTIONS },
    )
}

// What an email row's round menu can offer, in its order.
private val ROW_HOLD = listOf(ThreadAction.PIN, ThreadAction.UNPIN, ThreadAction.DELETE, ThreadAction.UNTRASH)

/**
 * What an email row's round menu offers of [actions] (from [threadActions]), in its two places as a
 * todo's: Pin or Unpin, then Delete (Move to inbox from the trash). Every other action is on its
 * swipes, the open thread and the selection bar.
 */
fun rowHoldActions(actions: List<ThreadAction>): List<ThreadAction> = ROW_HOLD.filter { it in actions }
