package cc.hafa.inbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.menu.HOLD_TAP
import cc.hafa.inbox.core.menu.HoldArc
import cc.hafa.inbox.core.menu.hitWedge
import cc.hafa.inbox.core.menu.pillTop
import cc.hafa.inbox.core.menu.polar
import cc.hafa.inbox.ui.theme.GeistMono
import cc.hafa.inbox.ui.theme.paper
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** How a wedge is colored. */
internal enum class HoldTone {
    /** plain */
    PLAIN,

    /** destructive, in the danger color */
    DANGER,

    /** Done's green */
    DONE,

    /** a state that is on (Unpin), its icon in the accent color */
    ON,
}

/** One action in a round menu. */
@Immutable
internal data class HoldMenuItem(
    /** what the action is, for whoever acts on it */
    val id: String,
    /** its name, shown in the pill while the finger is on it and read out */
    val label: String,
    /** its icon in the wedge */
    val icon: ImageVector,
    /** its colors */
    val tone: HoldTone = HoldTone.PLAIN,
)

/** One wedge of a round menu: [item] on [arc] (see `core.menu`, shared with the web). */
@Immutable
internal data class HoldWedge(
    /** the action it picks */
    val item: HoldMenuItem,
    /** where it sits, in dp around the disc's middle */
    val arc: HoldArc,
)

/** [items] on the wedges [arcs] laid out for their ids, in the same order. */
internal fun holdWedges(items: List<HoldMenuItem>, arcs: List<HoldArc>): List<HoldWedge> =
    arcs.mapNotNull { arc -> items.firstOrNull { it.id == arc.id }?.let { HoldWedge(it, arc) } }

/** A round menu to show. */
@Immutable
internal data class HoldMenuSpec(
    /** the disc's middle, in the window's (root) pixels */
    val center: Offset,
    /** its wedges */
    val wedges: List<HoldWedge>,
    /** what the pill says while no wedge is under the finger */
    val pill: String,
    /** whether [pill] is a web address, shown in monospace */
    val url: Boolean,
    /** the menu's name, read out */
    val label: String,
    /** run a picked action */
    val onPick: (HoldMenuItem) -> Unit,
    /** the menu closed, picked or not */
    val onClose: () -> Unit = {},
    /** what the pill says instead once the menu waits for a tap, when that differs */
    val stickyPill: String? = null,
)

/** The hold menu showing, if any, and the finger driving it. */
@Stable
internal class HoldMenuState {
    /** the menu shown, or null */
    var spec by mutableStateOf<HoldMenuSpec?>(null)
        private set

    /** the wedge under the finger, or -1 */
    var active by mutableIntStateOf(-1)
        private set

    /** whether the finger let go and the menu waits for a tap */
    var sticky by mutableStateOf(false)
        private set

    /** a menu fading away after it closed, drawn but not taking input */
    var fading by mutableStateOf<HoldMenuSpec?>(null)
        internal set

    /** pixels per dp, set by [HoldMenuHost] */
    var density = 1f

    /** Open [shown], following the finger until [release], or waiting for a tap when [sticky]. */
    fun show(shown: HoldMenuSpec, sticky: Boolean = false) {
        close()
        fading = null
        spec = shown
        active = -1
        this.sticky = sticky
    }

    /** The finger moved to [position] (root pixels). */
    fun move(position: Offset) {
        if (spec != null && !sticky) active = hit(position)
    }

    /**
     * The finger let go at [position]: pick the wedge under it, wait for a tap when it let go [nearPress]
     * (within [HOLD_TAP] of where it pressed; by default, of the disc's middle), or close.
     */
    fun release(position: Offset, nearPress: Boolean = spec?.let { (position - it.center).getDistance() < HOLD_TAP * density } == true) {
        if (spec == null || sticky) return
        val index = hit(position)
        if (index >= 0) {
            pick(index)
        } else if (nearPress) {
            active = -1
            sticky = true
        } else {
            close()
        }
    }

    /** The finger's gesture was taken away: close unless waiting for a tap. */
    fun cancel() {
        if (!sticky) close()
    }

    /** Pick wedge [index]: close and run its action. */
    fun pick(index: Int) {
        val shown = spec ?: return
        val item = shown.wedges.getOrNull(index)?.item ?: return
        close()
        shown.onPick(item)
    }

    /** Close the menu without picking; with [fade], it fades away rather than going at once. */
    fun close(fade: Boolean = false) {
        val shown = spec ?: return
        spec = null
        active = -1
        sticky = false
        if (fade) fading = shown
        shown.onClose()
    }

    /** The wedge under [position] (root pixels), or -1. */
    fun hit(position: Offset): Int {
        val shown = spec ?: return -1
        return hitWedge(
            shown.wedges.map { it.arc },
            shown.center.x / density,
            shown.center.y / density,
            position.x / density,
            position.y / density,
        )
    }
}

/** The hold menu of the screen, or null outside a [HoldMenuHost]. */
internal val LocalHoldMenu = staticCompositionLocalOf<HoldMenuState?> { null }

/**
 * The window's hold menu over [content]: a half disc of wedges that opens where a finger rests.
 *
 * Something under it calls [HoldMenuState.show] once a hold lasts [cc.hafa.inbox.core.menu.HOLD_MS], feeds the
 * finger's moves to [HoldMenuState.move] and its release to [HoldMenuState.release]: sliding into a
 * wedge and letting go picks it; letting go without sliding keeps it open for a tap; a tap away or
 * Back closes it. Each wedge is a button for TalkBack. [state] is the menu's, given to see what it shows.
 */
@Composable
internal fun HoldMenuHost(state: HoldMenuState = remember { HoldMenuState() }, content: @Composable () -> Unit) {
    state.density = LocalDensity.current.density
    CompositionLocalProvider(LocalHoldMenu provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            val shown = state.spec
            val fading = state.fading
            if (shown != null) {
                HoldMenuOverlay(state, shown)
            } else if (fading != null) {
                FadingMenu(state, fading)
            }
        }
    }
}

private val OpenEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

// How long the disc takes to grow, and how small it starts.
private const val DISC_OPEN_MS = 200
private const val MIN_SCALE = 0.15f

// How long the scrim takes to fade in, and how dark it ends.
private const val SCRIM_FADE_MS = 150
private const val SCRIM_ALPHA = 0.3f

// How long a menu takes to fade away when a corner button lifts out of it.
private const val FADE_OUT_MS = 150

// The ring marking the finger, and how see-through its fill is.
private val FINGER = 40.dp
private const val FINGER_ALPHA = 0.8f

// The touch target around each wedge's icon, for TalkBack's frame.
private val WEDGE_TARGET = 48.dp

// The pill keeps this far from the screen's sides and below the status bar.
private val PILL_GUTTER = 12.dp

private fun wedgePath(center: Offset, inner: Float, outer: Float, from: Float, to: Float): Path = Path().apply {
    // Compose's arcs run clockwise on screen, the wedges' angles counterclockwise.
    arcTo(Rect(center, outer), -to, to - from, forceMoveTo = true)
    arcTo(Rect(center, inner), -from, -(to - from), forceMoveTo = false)
    close()
}

@Composable
private fun HoldMenuOverlay(state: HoldMenuState, spec: HoldMenuSpec) {
    val haptics = LocalHapticFeedback.current
    val active = state.active
    LaunchedEffect(active) {
        if (active >= 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    BackHandler { state.close() }
    val grow = remember(spec) { Animatable(0f) }
    val shade = remember(spec) { Animatable(0f) }
    // With animations off, Compose ends both at once.
    LaunchedEffect(spec) {
        launch { shade.animateTo(1f, tween(SCRIM_FADE_MS, easing = FastOutSlowInEasing)) }
        grow.animateTo(1f, tween(DISC_OPEN_MS, easing = OpenEase))
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(spec, state.sticky) {
                if (state.sticky) {
                    detectTapGestures { position ->
                        val index = state.hit(position)
                        if (index >= 0) state.pick(index) else state.close()
                    }
                }
            }
            .semantics { paneTitle = spec.label },
    ) {
        MenuDisc(spec, active, state.sticky, shade = { shade.value }, grow = { grow.value }, onPick = state::pick)
    }
}

// A closed menu fading away, taking no input, then gone.
@Composable
private fun FadingMenu(state: HoldMenuState, spec: HoldMenuSpec) {
    val fade = remember(spec) { Animatable(1f) }
    LaunchedEffect(spec) {
        fade.animateTo(0f, tween(FADE_OUT_MS))
        if (state.fading === spec) state.fading = null
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.clearAndSetSemantics {}) {
        MenuDisc(spec, -1, sticky = false, shade = { 1f }, grow = { 1f }, onPick = null)
    }
}

// The scrim, the wedges, the finger's ring and the pill: the same for every round menu.
@Composable
private fun MenuDisc(spec: HoldMenuSpec, active: Int, sticky: Boolean, shade: () -> Float, grow: () -> Float, onPick: ((Int) -> Unit)?) {
    val density = LocalDensity.current
    val raised = paper.raised
    val border = paper.borderStrong
    val scrim = MaterialTheme.colorScheme.scrim
    val dp = density.density
    Box(Modifier.fillMaxSize().drawBehind { drawRect(scrim, alpha = SCRIM_ALPHA * shade()) }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (size.width > 0f && size.height > 0f) transformOrigin = TransformOrigin(spec.center.x / size.width, spec.center.y / size.height)
                    val scale = MIN_SCALE + (1f - MIN_SCALE) * grow()
                    scaleX = scale
                    scaleY = scale
                    alpha = grow()
                },
        ) {
            val fills = spec.wedges.map { toneFill(it.item.tone) }
            Canvas(Modifier.fillMaxSize()) {
                spec.wedges.forEachIndexed { index, wedge ->
                    val arc = wedge.arc
                    val path = wedgePath(spec.center, arc.ring.inner * dp, arc.ring.outer * dp, arc.from, arc.to)
                    val on = index == active
                    drawPath(path, if (on) fills[index] else raised)
                    drawPath(path, if (on) fills[index] else border, style = Stroke(1.dp.toPx()))
                }
            }
            val target = with(density) { WEDGE_TARGET.toPx() }
            spec.wedges.forEachIndexed { index, wedge ->
                val arc = wedge.arc
                val (atX, atY) = polar(spec.center.x, spec.center.y, (arc.ring.inner + arc.ring.outer) / 2f * dp, (arc.from + arc.to) / 2f)
                val tint = toneIcon(wedge.item.tone, index == active)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset((atX - target / 2).roundToInt(), (atY - target / 2).roundToInt()) }
                        .size(WEDGE_TARGET)
                        .then(
                            if (onPick != null) {
                                Modifier.semantics {
                                    role = Role.Button
                                    contentDescription = wedge.item.label
                                    onClick(wedge.item.label) {
                                        onPick(index)
                                        true
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Icon(wedge.item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
        }
        val finger = with(density) { FINGER.toPx() }
        Box(
            Modifier
                .offset { IntOffset((spec.center.x - finger / 2).roundToInt(), (spec.center.y - finger / 2).roundToInt()) }
                .size(FINGER)
                .background(paper.accentSoft.copy(alpha = FINGER_ALPHA), CircleShape)
                .border(2.dp, paper.accent, CircleShape),
        )
        HoldPill(spec, active, sticky)
    }
}

@Composable
private fun toneFill(tone: HoldTone): Color = when (tone) {
    HoldTone.DANGER -> paper.danger
    HoldTone.DONE -> paper.done
    else -> paper.accent
}

@Composable
private fun toneIcon(tone: HoldTone, lit: Boolean): Color = when {
    lit && tone == HoldTone.DANGER -> MaterialTheme.colorScheme.onError
    lit && tone == HoldTone.DONE -> MaterialTheme.colorScheme.onSecondary
    lit -> paper.onAccent
    tone == HoldTone.DANGER -> paper.danger
    tone == HoldTone.DONE -> paper.done
    tone == HoldTone.ON -> paper.accent
    else -> paper.textSecondary
}

@Composable
private fun HoldPill(spec: HoldMenuSpec, active: Int, sticky: Boolean) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val item = spec.wedges.getOrNull(active)?.item
    val url = item == null && spec.url
    val dp = density.density
    val top = pillTop(
        spec.center.x / dp,
        spec.center.y / dp,
        spec.wedges.map { it.arc },
        statusTop / dp + PILL_GUTTER.value,
    ) * dp
    Layout(
        content = {
            Text(
                item?.label ?: spec.stickyPill?.takeIf { sticky } ?: spec.pill,
                style = if (url) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono, fontSize = 12.sp)
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                },
                color = paper.onSnackbar,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .shadow(6.dp, CircleShape)
                    .background(paper.snackbar, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val gutter = PILL_GUTTER.roundToPx()
        val pill = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - 2 * gutter).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val left = (spec.center.x - pill.width / 2f).roundToInt().coerceIn(gutter, (constraints.maxWidth - gutter - pill.width).coerceAtLeast(gutter))
            pill.place(left, top.roundToInt())
        }
    }
}
