package cc.hafa.inbox.core.mail

import kotlin.math.abs

/** The share of a row's width a swipe crosses before letting go acts. */
const val SWIPE_COMMIT_FRACTION = 0.4f

/** How fast (dp per second) a swipe flung in its own direction acts wherever it is let go; Material's swipe-to-dismiss uses the same. */
const val SWIPE_FLING_SPEED = 125f

/** The side a swipe moves toward: start is rightward, end leftward. */
enum class SwipeSide {
    /** rightward */
    START,

    /** leftward */
    END,
}

/** How far a swipe on a row this wide goes before letting go acts. */
fun commitThreshold(rowWidth: Float): Float = rowWidth * SWIPE_COMMIT_FRACTION

/** How far a swipe has come toward letting go acting, from 0 to 1. */
fun swipeProgress(dx: Float, rowWidth: Float): Float {
    val threshold = commitThreshold(rowWidth)
    return if (threshold <= 0f) 0f else minOf(1f, abs(dx) / threshold)
}

/**
 * Which side acts when a swipe is let go [dx] from rest, moving at [velocity] (rightward positive).
 *
 * A fling at least [SWIPE_FLING_SPEED] acts when it moves the way the row already went, and puts
 * the row back when it moves the other way; slower, the row must be past [commitThreshold].
 */
fun committedSide(dx: Float, rowWidth: Float, velocity: Float): SwipeSide? {
    if (dx == 0f) return null
    val side = if (dx > 0f) SwipeSide.START else SwipeSide.END
    return if (abs(velocity) >= SWIPE_FLING_SPEED) {
        if ((velocity > 0f) == (dx > 0f)) side else null
    } else {
        if (abs(dx) >= commitThreshold(rowWidth)) side else null
    }
}

/** What a swipe on a thread does. */
enum class ThreadSwipeAction {
    /** open the snooze sheet */
    SNOOZE,

    /** wake a snoozed thread now */
    WAKE,

    /** mark it done */
    DONE,

    /** move it back to the inbox */
    INBOX,
}

/**
 * What a swipe toward [side] on a thread does, or null where that direction doesn't move.
 *
 * Rightward snoozes, or wakes a snoozed thread (any thread in the Snoozed view); leftward marks it
 * done where Done applies (in the inbox, snoozed, or with a lapsed snooze), and elsewhere moves it
 * to the inbox, but for a thread in spam or trash ([spamOrTrash]). While rows are selected it acts
 * on this thread alone, as it does otherwise.
 */
fun threadSwipeAction(
    side: SwipeSide,
    status: SnoozeStatus,
    inInbox: Boolean,
    spamOrTrash: Boolean,
    inSnoozedView: Boolean,
): ThreadSwipeAction? {
    val snoozed = inSnoozedView || status == SnoozeStatus.SNOOZED
    val canDone = snoozed || inInbox || status == SnoozeStatus.LAPSED
    return when {
        side == SwipeSide.START -> if (snoozed) ThreadSwipeAction.WAKE else ThreadSwipeAction.SNOOZE
        canDone -> ThreadSwipeAction.DONE
        spamOrTrash -> null
        else -> ThreadSwipeAction.INBOX
    }
}

/** What a swipe on a todo does. */
enum class TodoSwipeAction {
    /** open the snooze sheet */
    SNOOZE,

    /** make a done or snoozed todo active */
    REACTIVATE,

    /** mark it done */
    DONE,

    /** delete a done todo */
    DELETE,
}

/**
 * What a swipe toward [side] on a todo does.
 *
 * Rightward snoozes, or makes a done or snoozed todo active; leftward marks it done, or deletes a
 * done one. While rows are selected it acts on this todo alone, as it does otherwise.
 */
fun todoSwipeAction(side: SwipeSide, done: Boolean, snoozed: Boolean): TodoSwipeAction = when {
    side == SwipeSide.START -> if (done || snoozed) TodoSwipeAction.REACTIVATE else TodoSwipeAction.SNOOZE
    else -> if (done) TodoSwipeAction.DELETE else TodoSwipeAction.DONE
}

/** What a swipe on a bundle's row does. */
enum class BundleSwipeAction {
    /** open the snooze sheet for every thread in it */
    SNOOZE,

    /** mark every thread in it done (its Done all) */
    DONE,
}

/**
 * What a swipe toward [side] on a bundle's row does, or null where it doesn't move.
 *
 * Only a closed bundle swipes, as a thread's row does: rightward snoozes every thread in it,
 * leftward marks them all done (its Done all). An [open] bundle's header doesn't swipe. While rows
 * are selected it acts on this bundle alone, as a thread's swipe does.
 */
fun bundleSwipeAction(side: SwipeSide, open: Boolean): BundleSwipeAction? = when {
    open -> null
    side == SwipeSide.START -> BundleSwipeAction.SNOOZE
    else -> BundleSwipeAction.DONE
}

/** The name shown under a swiped bundle: its [action] with how many [threads] it acts on, as "Done · 14" or "Snooze · 10+". */
fun bundleSwipeLabel(action: BundleSwipeAction, threads: String): String =
    "${if (action == BundleSwipeAction.DONE) "Done" else "Snooze"} · $threads"
