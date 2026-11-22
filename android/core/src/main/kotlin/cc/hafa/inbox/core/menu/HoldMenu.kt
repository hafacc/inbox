package cc.hafa.inbox.core.menu

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// The round menu's geometry, shared by every round menu: the same rules as web's
// shared/src/hold-menu.ts, pinned by shared/testdata/hold-menu-fixtures.json. Angles are degrees
// counterclockwise from rightward, and points are dp with y growing downward.

/** How long a finger rests before a row's or link's round menu opens, in milliseconds. */
const val HOLD_MS = 450L

/** A release this close (dp) to where the press started keeps the menu open for a tap. */
const val HOLD_TAP = 14f

/** How far past the outer edge (dp) a slide still counts as on a wedge. */
const val HOLD_SLACK = 28f

/** Below this many dp from the screen's top a half disc opens downward. */
const val HOLD_TOP_ROOM = 190f

/** Within this many dp of the screen's bottom a row's half disc turns upward. */
const val HOLD_BOTTOM_ROOM = 80f

/** A link held this close (dp) to a side turns its half disc inward; a row's this close to the right opens leftward. */
const val HOLD_SIDE_ROOM = 110f

/** How far (dp) below where it pressed a finger drags, short of any wedge, before a corner button lifts to move. */
const val CORNER_DRAG_DOWN = 24f

// How far (degrees) a link's half disc turns inward near a side.
private const val SIDE_TURN = 35f

/** The ring a set of wedges fills, in dp. */
data class HoldRing(
    /** radius of its hole */
    val inner: Float,
    /** radius of its outer edge */
    val outer: Float,
)

/** A row's half disc: a todo's, an email's or a bundle's. */
val ROW_RING = HoldRing(26f, 104f)

/** A link's half disc. */
val LINK_RING = HoldRing(30f, 108f)

/** A corner button's inner ring, around the 56 dp button. */
val CORNER_INNER_RING = HoldRing(44f, 130f)

/** A corner button's outer ring. */
val CORNER_OUTER_RING = HoldRing(130f, 212f)

/** One wedge of a round menu: [id] from angle [from] counterclockwise to [to], across [ring]. */
data class HoldArc(
    /** the action it stands for */
    val id: String,
    /** where it starts */
    val from: Float,
    /** where it ends, above [from] */
    val to: Float,
    /** the ring it fills */
    val ring: HoldRing,
)

/** The bottom corner a corner button sits in. */
enum class CornerSide {
    /** the bottom left */
    LEFT,

    /** the bottom right, the default */
    RIGHT,
}

/** The point [radius] from ([centerX], [centerY]) at [degrees]. */
fun polar(centerX: Float, centerY: Float, radius: Float, degrees: Float): Pair<Float, Float> {
    val radians = degrees * PI.toFloat() / 180f
    return (centerX + radius * cos(radians)) to (centerY - radius * sin(radians))
}

/** The angle of ([x], [y]) seen from ([centerX], [centerY]), from 0 up to 360. */
fun angleOf(centerX: Float, centerY: Float, x: Float, y: Float): Float {
    val degrees = Math.toDegrees(atan2((centerY - y).toDouble(), (x - centerX).toDouble())).toFloat()
    return (degrees + 360f) % 360f
}

/** Whether [angle] lies on the arc from [from] counterclockwise to [to]; either edge may be negative. */
fun inArc(angle: Float, from: Float, to: Float): Boolean = (angle - from + 720f) % 360f <= to - from

/**
 * The index of the wedge under ([x], [y]) for a disc at ([centerX], [centerY]), or -1. A point a
 * little past the outermost wedge at its angle (by [HOLD_SLACK]) still counts for it, so a long
 * slide doesn't fall off.
 */
fun hitWedge(wedges: List<HoldArc>, centerX: Float, centerY: Float, x: Float, y: Float): Int {
    val distance = hypot(x - centerX, y - centerY)
    val angle = angleOf(centerX, centerY, x, y)
    var outermost = -1
    wedges.forEachIndexed { index, wedge ->
        if (inArc(angle, wedge.from, wedge.to)) {
            if (distance >= wedge.ring.inner && distance < wedge.ring.outer) return index
            if (outermost < 0 || wedge.ring.outer > wedges[outermost].ring.outer) outermost = index
        }
    }
    val edge = wedges.getOrNull(outermost)?.ring?.outer ?: 0f
    return if (outermost >= 0 && distance >= edge && distance < edge + HOLD_SLACK) outermost else -1
}

/**
 * A row's wedges around a long press at ([x], [y]) on a screen [width] by [height] dp (a todo's, an
 * email's or a bundle's; TalkBack and the menu key press on its dot, avatar or mark): [slots] share
 * a half disc, each a quarter for two, the first above the second as it opens up and right, right
 * and down near the top, or turned upward near the bottom; near the right edge it opens leftward
 * instead, mirrored. A null slot leaves its place empty (a done todo has no Pin).
 */
fun rowHoldWedges(slots: List<String?>, x: Float, y: Float, width: Float, height: Float): List<HoldArc> {
    val facing = when {
        y <= HOLD_TOP_ROOM -> -45f
        y > height - HOLD_BOTTOM_ROOM -> 75f
        else -> 45f
    }
    val leftward = x > width - HOLD_SIDE_ROOM
    val middle = if (leftward) 180f - facing else facing
    val share = 180f / slots.size
    return slots.mapIndexedNotNull { index, id ->
        id?.let {
            val from = if (leftward) middle - 90f + index * share else middle + 90f - (index + 1) * share
            HoldArc(it, from, from + share, ROW_RING)
        }
    }
}

/**
 * A link's wedges around a finger at ([x], [y]) on a screen [width] wide: a half disc above it, or
 * below near the top, turned inward near either side, [ids] left to right as it opens upward.
 */
fun linkHoldWedges(ids: List<String>, x: Float, y: Float, width: Float): List<HoldArc> {
    val up = y >= HOLD_TOP_ROOM
    var middle = if (up) 90f else 270f
    if (x < HOLD_SIDE_ROOM) middle += if (up) -SIDE_TURN else SIDE_TURN
    if (x > width - HOLD_SIDE_ROOM) middle += if (up) SIDE_TURN else -SIDE_TURN
    val share = 180f / ids.size
    val start = middle - 90f
    return ids.mapIndexed { index, id ->
        val from = start + (ids.size - 1 - index) * share
        HoldArc(id, from, from + share, LINK_RING)
    }
}

/**
 * A corner button's wedges: a quarter disc opening up and inward from the button, [inner] on the
 * ring next to it and [outer] on the ring beyond, each listed from the top down to the side. On
 * the left the disc mirrors, opening up and to the right.
 */
fun cornerHoldWedges(inner: List<String>, outer: List<String>, side: CornerSide): List<HoldArc> {
    fun ring(ids: List<String>, shape: HoldRing): List<HoldArc> {
        val share = 90f / ids.size
        return ids.mapIndexed { index, id ->
            val start = 90f + index * share
            val end = start + share
            if (side == CornerSide.RIGHT) HoldArc(id, start, end, shape) else HoldArc(id, 180f - end, 180f - start, shape)
        }
    }
    return ring(inner, CORNER_INNER_RING) + ring(outer, CORNER_OUTER_RING)
}

/** The top bar's view menu, around the view's name. */
val VIEW_RING = HoldRing(40f, 124f)

// The arc (degrees) the view menu fills: from down and to the left round to rightward, so it
// opens down from a name at the top left and stays on screen.
private const val VIEW_ARC_FROM = 240f
private const val VIEW_ARC_TO = 360f

/**
 * The top bar's view wedges around the view's name: a third of a disc opening downward, [ids]
 * sharing it from rightward round to down and to the left, in the side menu's order.
 */
fun viewHoldWedges(ids: List<String>): List<HoldArc> {
    val share = (VIEW_ARC_TO - VIEW_ARC_FROM) / ids.size
    return ids.mapIndexed { index, id -> HoldArc(id, VIEW_ARC_TO - (index + 1) * share, VIEW_ARC_TO - index * share, VIEW_RING) }
}

/**
 * Whether a press on a corner button that began at height [pressY] and is now at [y] drags the
 * button rather than choosing from its menu: it went more than [CORNER_DRAG_DOWN] down, before
 * reaching any wedge.
 */
fun cornerDragStarts(pressY: Float, y: Float, reachedWedge: Boolean): Boolean = !reachedWedge && y - pressY > CORNER_DRAG_DOWN

/** The bottom corner nearer to [x] on a screen [width] wide; the middle goes right. */
fun cornerSide(x: Float, width: Float): CornerSide = if (x < width / 2f) CornerSide.LEFT else CornerSide.RIGHT

/**
 * The top of the pill naming the menu: 48 above the disc's top, unless that is above [topLimit],
 * else 14 below its bottom.
 */
fun pillTop(centerX: Float, centerY: Float, wedges: List<HoldArc>, topLimit: Float): Float {
    val edges = wedges.flatMap { wedge ->
        listOf(wedge.from, (wedge.from + wedge.to) / 2f, wedge.to).map { polar(centerX, centerY, wedge.ring.outer, it).second }
    }
    val topmost = minOf(centerY, edges.minOrNull() ?: centerY)
    val lowest = maxOf(centerY, edges.maxOrNull() ?: centerY)
    return if (topmost - 48f > topLimit) topmost - 48f else lowest + 14f
}
