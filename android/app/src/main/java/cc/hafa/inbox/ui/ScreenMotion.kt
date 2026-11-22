package cc.hafa.inbox.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How long a thread takes to grow out of its row or shrink back into it, as on the web. */
internal const val OPEN_MS = 200

/** The web's easing for opening and closing a thread. */
internal val OpenEasing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

// Material's easing for how far a back gesture has come.
private val BackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

// How small a screen shrinks at the end of a back gesture, and how round its corners get.
private const val BACK_SCALE = 0.1f
private val BACK_CORNER = 28.dp
private val BACK_INSET = 8.dp
private val ROW_CORNER = 20.dp
private val LIFT = 8.dp

/**
 * Whether the system runs animations (its animator duration scale isn't off), following the setting
 * as it changes: Compose's own animations read the same scale.
 */
@Composable
internal fun animationsOn(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return (scale?.scaleFactor ?: 1f) > 0f
}

/** A screen that follows a back gesture while it is dragged; see [ScreenBackHandler]. */
internal interface BackFollower {
    /** Show the gesture so far. */
    suspend fun follow(event: BackEventCompat)

    /** The gesture was let go of, just before the screen's back runs. */
    fun released() {}

    /** The gesture was called off; go back to rest. */
    fun canceled()
}

/** The screen motion that the nearest [ScreenBackHandler] drives, if any. */
internal val LocalBackFollower = staticCompositionLocalOf<BackFollower?> { null }

/**
 * Back for a whole screen while it is composed (screens open by composing, so they join the back
 * stack in the order they open, as [LayerBackHandler] does): a back gesture drags the screen
 * ([LocalBackFollower]) before [onBack] runs.
 */
@Composable
internal fun ScreenBackHandler(onBack: () -> Unit) {
    val follower = LocalBackFollower.current
    val animate by rememberUpdatedState(animationsOn())
    if (LocalLayerLeaving.current) return
    PredictiveBackHandler { events ->
        // With animations off the gesture shows nothing; Back still works when it ends.
        val shown = follower?.takeIf { animate }
        try {
            events.collect { shown?.follow(it) }
            shown?.released()
            onBack()
        } catch (canceled: CancellationException) {
            shown?.canceled()
            throw canceled
        }
    }
}

/** Where each thread's row sits, so an opened thread can grow out of it and shrink back into it. */
@Stable
internal class OpenOrigins {
    private val rows = HashMap<String, LayoutCoordinates>()

    /** Note that thread [id]'s row is laid out at [coordinates]. */
    fun place(id: String, coordinates: LayoutCoordinates) {
        rows.entries.removeAll { (key, placed) -> !placed.isAttached || (placed === coordinates && key != id) }
        rows[id] = coordinates
    }

    /** The shown part of thread [id]'s row in [frame]'s coordinates, or null when it isn't on screen. */
    fun boundsIn(frame: LayoutCoordinates, id: String): Rect? {
        val row = rows[id]?.takeIf { it.isAttached && frame.isAttached } ?: return null
        val bounds = frame.localBoundingBoxOf(row, clipBounds = true)
        return bounds.takeIf { it.height > 0f && it.width > 0f }
    }
}

/** The rows' positions that [ThreadOverList] reads. */
internal val LocalOpenOrigins = staticCompositionLocalOf<OpenOrigins?> { null }

/** Report this row as where thread [id] opens from and closes back into. */
@Composable
internal fun Modifier.openOrigin(id: String): Modifier {
    val origins = LocalOpenOrigins.current ?: return this
    return onGloballyPositioned { origins.place(id, it) }
}

private class Held<T>(var value: T)

/** [value], or the last one it had while [keep] was true, for content that animates out after it goes away. */
@Composable
internal fun <T> rememberLast(value: T, keep: Boolean): T {
    val held = remember { Held(value) }
    if (keep) held.value = value
    return held.value
}

// A rounded window of the given size at the layer's top left.
private class WindowShape(private val width: Float, private val height: Float, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, width, height, CornerRadius(corner)))
}

// The open thread's motion: growing from its row, following a back gesture, and closing.
@Stable
private class ThreadMotion(private val scope: CoroutineScope, startsOpen: Boolean) : BackFollower {
    val grow = Animatable(1f)
    val back = Animatable(0f)
    val alpha = Animatable(1f)
    var origin by mutableStateOf<Rect?>(null)
    var fromLeft by mutableStateOf(true)
    var startY = 0f
    var touchY by mutableStateOf(0f)

    // The list is drawn behind the thread while it opens, closes or follows a gesture.
    var listShown by mutableStateOf(!startsOpen)
    var dragging by mutableStateOf(false)

    override suspend fun follow(event: BackEventCompat) {
        if (!dragging) {
            dragging = true
            listShown = true
            fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
            startY = event.touchY
        }
        touchY = event.touchY
        back.snapTo(BackEasing.transform(event.progress))
    }

    override fun canceled() {
        scope.launch {
            back.animateTo(0f, tween(OPEN_MS, easing = OpenEasing))
            dragging = false
            listShown = false
        }
    }
}

/**
 * The list with the open thread over it: [thread] grows out of its row in [list] when [opened] turns
 * non-null, follows a back gesture with the list showing behind it, and shrinks back into its row
 * (or fades when the row is gone) once [opened] turns null again. [key] tells threads apart; a
 * move straight from one open thread to another that [inPlace] says was a swipe shows the new one
 * at once, without growing.
 */
@Composable
internal fun <T : Any> ThreadOverList(
    opened: T?,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    inPlace: (from: String, to: String) -> Boolean = { _, _ -> false },
    list: @Composable () -> Unit,
    thread: @Composable (T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val motion = remember { ThreadMotion(scope, startsOpen = opened != null) }
    val origins = remember { OpenOrigins() }
    val frame = remember { Held<LayoutCoordinates?>(null) }
    val shown = rememberLast(opened, opened != null)
    val openId = opened?.let(key)
    // The thread the layer shows: the open one, or the one still closing after it.
    var layerFor by remember { mutableStateOf(openId) }
    val closing = opened == null && layerFor != null
    val first = remember { Held(true) }
    LaunchedEffect(openId) {
        val wasFirst = first.value
        first.value = false
        val layer = frame.value
        val closingId = layerFor
        if (openId != null) {
            layerFor = openId
            motion.back.snapTo(0f)
            motion.dragging = false
            val start = layer?.let { origins.boundsIn(it, openId) }
            if (wasFirst || (closingId != null && inPlace(closingId, openId))) {
                motion.grow.snapTo(1f)
                motion.alpha.snapTo(1f)
            } else if (start != null) {
                motion.origin = start
                motion.alpha.snapTo(1f)
                motion.grow.snapTo(0f)
                motion.grow.animateTo(1f, tween(OPEN_MS, easing = OpenEasing))
            } else {
                motion.grow.snapTo(1f)
                motion.alpha.snapTo(0f)
                motion.alpha.animateTo(1f, tween(OPEN_MS, easing = OpenEasing))
            }
            motion.listShown = false
        } else if (closingId != null) {
            motion.listShown = true
            // The list behind lays out its rows first, so the row can be found.
            withFrameNanos {}
            val end = frame.value?.let { origins.boundsIn(it, closingId) }
            if (end != null) {
                motion.origin = end
                launch { motion.back.animateTo(0f, tween(OPEN_MS, easing = OpenEasing)) }
                motion.grow.animateTo(0f, tween(OPEN_MS, easing = OpenEasing))
            } else {
                motion.alpha.animateTo(0f, tween(OPEN_MS, easing = OpenEasing))
            }
            motion.dragging = false
            layerFor = null
        }
    }
    val threadShown = opened != null || closing
    // Closing shows the list at once, so its rows are laid out before the thread shrinks into one.
    val listShown = !threadShown || closing || motion.listShown
    CompositionLocalProvider(LocalOpenOrigins provides origins) {
        Box(modifier = modifier.fillMaxSize().onGloballyPositioned { frame.value = it }) {
            if (listShown) {
                Box(modifier = if (threadShown) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                    list()
                }
            }
            val current = if (opened != null) opened else shown
            if (threadShown && current != null) {
                ThreadLayer(motion) {
                    CompositionLocalProvider(LocalBackFollower provides motion, LocalLayerLeaving provides closing) { thread(current) }
                    if (closing || motion.grow.isRunning || motion.alpha.isRunning) {
                        // Taps on a thread that is on its way in or out reach neither it nor the list behind.
                        Box(modifier = Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadLayer(motion: ThreadMotion, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val progress = motion.back.value
                scaleX = 1f - BACK_SCALE * progress
                scaleY = scaleX
                val sideways = (size.width / 20f - BACK_INSET.toPx()).coerceAtLeast(0f)
                translationX = (if (motion.fromLeft) 1f else -1f) * sideways * progress
                val upDown = (size.height / 20f - BACK_INSET.toPx()).coerceAtLeast(0f)
                val pulled = if (size.height > 0f) ((motion.touchY - motion.startY) / size.height).coerceIn(-1f, 1f) else 0f
                translationY = upDown * pulled * progress
                shape = if (progress > 0f) WindowShape(size.width, size.height, BACK_CORNER.toPx() * progress) else RectangleShape
                clip = progress > 0f
                shadowElevation = if (progress > 0f) LIFT.toPx() else 0f
                alpha = motion.alpha.value
            }
            .graphicsLayer {
                val grown = motion.grow.value
                val origin = motion.origin
                if (grown < 1f && origin != null) {
                    val left = lerp(origin.left, 0f, grown)
                    val top = lerp(origin.top, 0f, grown)
                    val width = lerp(origin.width, size.width, grown)
                    val height = lerp(origin.height, size.height, grown)
                    translationX = left
                    translationY = top
                    shape = WindowShape(width, height, ROW_CORNER.toPx() * (1f - grown))
                    clip = true
                    shadowElevation = LIFT.toPx()
                } else {
                    translationX = 0f
                    translationY = 0f
                    shape = RectangleShape
                    clip = false
                    shadowElevation = 0f
                }
            },
    ) {
        content()
    }
}
