package cc.hafa.inbox.core.mail

import kotlin.math.abs

// Swiping an open thread sideways to the thread before or after it in the list. A port of
// shared/src/thread-pager.ts; both run shared/testdata/thread-pager-fixtures.json.

/** How far (dp) in from either screen edge a swipe is left to the system's Back. */
const val PAGE_EDGE = 24f

/** The share of the screen's width a swipe crosses before letting go moves to the next thread. */
const val PAGE_COMMIT_FRACTION = 0.28f

/** How fast (dp per second) a swipe flung in its own direction moves wherever it is let go. */
const val PAGE_FLING_SPEED = 600f

/** How far (dp) a flung swipe must have come first, so a flick on a tap doesn't move. */
const val PAGE_FLING_DISTANCE = 30f

/** How much of the finger's travel the thread follows toward a side with no thread. */
const val PAGE_RESISTANCE = 0.3f

/** Where a swipe goes: the thread below the open one, or the one above. */
enum class PageStep {
    /** the thread below */
    NEXT,

    /** the thread above */
    PREVIOUS,
}

/** Whether a swipe starting [x] across a screen [width] wide may move between threads: not from an edge. */
fun pageStarts(x: Float, width: Float, edge: Float = PAGE_EDGE): Boolean = x >= edge && x <= width - edge

/** The thread a swipe [dx] from rest (rightward positive) is moving toward: leftward is the next one. */
fun pageToward(dx: Float): PageStep? = when {
    dx < 0 -> PageStep.NEXT
    dx > 0 -> PageStep.PREVIOUS
    else -> null
}

/** How far the thread follows a finger [dx] from rest, less toward a side with no thread. */
fun pageOffset(dx: Float, hasPrevious: Boolean, hasNext: Boolean): Float {
    val open = when (pageToward(dx)) {
        PageStep.NEXT -> hasNext
        PageStep.PREVIOUS -> hasPrevious
        null -> true
    }
    return if (open) dx else dx * PAGE_RESISTANCE
}

/**
 * Where a swipe let go [dx] from rest, moving at [velocity] (rightward positive), on a screen
 * [width] wide goes, or null when it springs back, toward a side that has a thread. Flung at
 * [PAGE_FLING_SPEED] or faster, it moves when flung its own way once past [PAGE_FLING_DISTANCE] and
 * springs back when flung the other way; slower, it moves once past [PAGE_COMMIT_FRACTION] of the
 * width.
 */
fun pageStep(dx: Float, width: Float, velocity: Float, hasPrevious: Boolean, hasNext: Boolean): PageStep? {
    val toward = pageToward(dx) ?: return null
    val reachable = if (toward == PageStep.NEXT) hasNext else hasPrevious
    return when {
        !reachable -> null
        abs(velocity) >= PAGE_FLING_SPEED ->
            toward.takeIf { (velocity > 0) == (dx > 0) && abs(dx) >= PAGE_FLING_DISTANCE }
        else -> toward.takeIf { abs(dx) >= width * PAGE_COMMIT_FRACTION }
    }
}
