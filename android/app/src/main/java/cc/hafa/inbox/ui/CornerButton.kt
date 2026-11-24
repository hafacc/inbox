package cc.hafa.inbox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.menu.CornerSide
import cc.hafa.inbox.core.menu.HOLD_TAP
import cc.hafa.inbox.core.menu.cornerDragStarts
import cc.hafa.inbox.core.menu.cornerHoldWedges
import cc.hafa.inbox.core.menu.cornerSide
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.launch

/** The bottom corner every round button sits in, and how to move them all, saved on the device. */
@Immutable
internal data class CornerPlacement(
    /** the corner */
    val side: CornerSide,
    /** move every round button to another corner */
    val onSide: (CornerSide) -> Unit,
)

/** The round buttons' corner; the right one, fixed, outside the app's screens (as in tests). */
internal val LocalCornerPlacement = staticCompositionLocalOf { CornerPlacement(CornerSide.RIGHT) {} }

/** A corner button's actions: the ring next to it and the ring beyond, each from the top. */
@Immutable
internal data class CornerMenu(
    /** Done and Snooze, or what takes their places */
    val inner: List<HoldMenuItem>,
    /** the rest */
    val outer: List<HoldMenuItem>,
    /** run a picked action */
    val onPick: (HoldMenuItem) -> Unit,
)

/** The alignment of a stack of round buttons in [side]. */
internal fun cornerAlignment(side: CornerSide): Alignment =
    if (side == CornerSide.LEFT) Alignment.BottomStart else Alignment.BottomEnd

/** A round corner button's size. */
internal val CORNER_BUTTON = 56.dp

// How the button grows while lifted, and over how long, as on the web; how long it takes to snap into its corner.
private const val LIFTED_SCALE = 1.1f
private const val LIFT_MS = 150
private const val SNAP_MS = 200

/**
 * A round 56 dp button for a bottom corner ([LocalCornerPlacement]), with [above] stacked over it
 * and moving with it. With [menu], a press grows its actions out of it as a two-ring round menu
 * (the window's [HoldMenuHost]): slide to one and let go, or tap to keep the menu open and tap one.
 * Without, a tap runs [onTap]. A press dragged down from it, before reaching a wedge
 * ([cornerDragStarts]), lifts the button to follow the finger, and letting go snaps it to the
 * nearer bottom corner; TalkBack offers the move as an action. Place it with [cornerAlignment].
 */
@Composable
internal fun CornerButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    menuLabel: String = label,
    menu: (() -> CornerMenu)? = null,
    onTap: () -> Unit = {},
    above: @Composable () -> Unit = {},
) {
    val placement = LocalCornerPlacement.current
    val holdMenu = LocalHoldMenu.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    // Where the stack is laid out, and where it is drawn instead (top left, in the root) while dragged and as it snaps
    // from there into its corner over [snap]: the corner it lands in is wherever the layout puts it, so the snap follows
    // the layout rather than a place worked out ahead.
    var stackAt by remember { mutableStateOf(Offset.Zero) }
    var heldAt by remember { mutableStateOf<Offset?>(null) }
    val snap = remember { Animatable(0f) }
    var lifted by remember { mutableStateOf(false) }
    val liftScale by animateFloatAsState(if (lifted) LIFTED_SCALE else 1f, tween(LIFT_MS), label = "liftScale")
    val liftShadow by animateDpAsState(if (lifted) 12.dp else 6.dp, tween(LIFT_MS), label = "liftShadow")
    var button by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current by rememberUpdatedState(placement)
    val actions by rememberUpdatedState(menu)
    val tap by rememberUpdatedState(onTap)
    // The menu this button showed, while it shows.
    var shown by remember { mutableStateOf<HoldMenuSpec?>(null) }

    fun openMenu(sticky: Boolean): Boolean {
        val build = actions ?: return false
        val placed = button?.takeIf { it.isAttached } ?: return false
        if (holdMenu == null) return false
        val built = build()
        val items = built.inner + built.outer
        val spec = HoldMenuSpec(
            center = placed.localToRoot(Offset(placed.size.width / 2f, placed.size.height / 2f)),
            wedges = holdWedges(items, cornerHoldWedges(built.inner.map { it.id }, built.outer.map { it.id }, current.side)),
            pill = "Slide to an action",
            url = false,
            label = menuLabel,
            onPick = built.onPick,
            stickyPill = "Tap an action",
        )
        holdMenu.show(spec, sticky = sticky)
        shown = spec
        return true
    }

    fun activate() {
        if (actions != null) openMenu(sticky = true) else tap()
    }

    // Where the stack is drawn now.
    fun drawnAt(): Offset = heldAt?.let { held -> lerp(held, stackAt, snap.value) } ?: stackAt

    fun settle(at: Offset?) {
        val rootWidth = button?.takeIf { it.isAttached }?.findRootCoordinates()?.size?.width?.div(density)
        val side = if (at != null && rootWidth != null) cornerSide(at.x / density, rootWidth) else current.side
        if (side != current.side) current.onSide(side)
        lifted = false
        scope.launch {
            snap.animateTo(1f, tween(SNAP_MS))
            heldAt = null
            snap.snapTo(0f)
        }
    }

    val moveLabel = if (placement.side == CornerSide.RIGHT) "Move to the left corner" else "Move to the right corner"
    Column(
        horizontalAlignment = if (placement.side == CornerSide.LEFT) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .onGloballyPositioned { stackAt = it.positionInRoot() }
            .graphicsLayer {
                val offset = drawnAt() - stackAt
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        above()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .onGloballyPositioned { button = it }
                .scale(liftScale)
                .shadow(liftShadow, CircleShape)
                .size(CORNER_BUTTON)
                .background(paper.accent, CircleShape)
                .onKeyEvent { event ->
                    val press = event.type == KeyEventType.KeyUp &&
                        (event.key == Key.Enter || event.key == Key.Spacebar || event.key == Key.NumPadEnter)
                    if (press) activate()
                    press
                }
                .focusable()
                .pointerInput(holdMenu) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val placed = button?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        val pressedAt = placed.localToRoot(down.position)
                        val center = placed.localToRoot(Offset(placed.size.width / 2f, placed.size.height / 2f))
                        val menuShown = actions != null && openMenu(sticky = false)
                        if (menuShown) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        var slid = false
                        var reachedWedge = false
                        var dragging = false
                        var dragFrom = Offset.Zero
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !placed.isAttached) {
                                if (dragging) settle(null) else holdMenu?.cancel()
                                break
                            }
                            val at = placed.localToRoot(change.position)
                            if (!slid && (at - pressedAt).getDistance() >= HOLD_TAP * density) slid = true
                            change.consume()
                            if (change.pressed) {
                                if (!dragging && menuShown) {
                                    holdMenu?.move(at)
                                    reachedWedge = reachedWedge || (holdMenu?.active ?: -1) >= 0
                                }
                                if (!dragging && cornerDragStarts(pressedAt.y / density, at.y / density, reachedWedge)) {
                                    // Down, away from the wedges: the menu fades away and the button follows the finger.
                                    if (menuShown && holdMenu?.spec === shown) holdMenu?.close(fade = true)
                                    dragging = true
                                    lifted = true
                                    dragFrom = drawnAt()
                                    scope.launch { snap.snapTo(0f) }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                if (dragging) heldAt = dragFrom + (at - pressedAt)
                            } else {
                                if (dragging) {
                                    settle(at)
                                } else if (menuShown) {
                                    holdMenu?.release(if (slid) at else center, nearPress = (at - pressedAt).getDistance() < HOLD_TAP * density)
                                } else if (!slid) {
                                    tap()
                                }
                                break
                            }
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick {
                        activate()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(moveLabel) {
                            current.onSide(if (current.side == CornerSide.RIGHT) CornerSide.LEFT else CornerSide.RIGHT)
                            true
                        },
                    )
                },
        ) {
            Icon(icon, contentDescription = null, tint = paper.onAccent)
        }
    }
}
