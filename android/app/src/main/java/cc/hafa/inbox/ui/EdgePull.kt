package cc.hafa.inbox.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * How far an open thread must be pulled past its top or end to close it, in dp, as on the web: far
 * enough that a scroll carrying on past the edge doesn't close it by accident.
 */
const val THREAD_PULL_DISTANCE = 160f

/** How far a pulled thread can move however far the finger goes, in dp, as on the web. */
const val THREAD_PULL_STRETCH = 200f

/**
 * How far a pulled thread moves when the finger has gone [travel] dp past its edge: at first with the
 * finger, then less and less, never quite reaching [THREAD_PULL_STRETCH]. Keeps the sign of [travel].
 */
fun pullStretch(travel: Float): Float = sign(travel) * THREAD_PULL_STRETCH * (1f - exp(-abs(travel) / THREAD_PULL_STRETCH))

/** How far along a pull of [travel] dp is toward closing the thread, from 0 to 1. */
fun pullProgress(travel: Float): Float = minOf(1f, abs(travel) / THREAD_PULL_DISTANCE)

private val INDICATOR_SIZE = 40.dp
private val ARROW_SIZE = 18.dp

/**
 * A vertical drag that what it wraps leaves unused from its very start, past its top or end: the
 * content follows it, moved by [stretch] of the drag's length, and on release one of at least
 * [threshold] pixels calls [onRelease]; either way the content springs back. A scroll that merely
 * reaches the edge does not count.
 */
class EdgePull(
    private val threshold: Float,
    private val stretch: (Float) -> Float,
    private val onRelease: () -> Unit,
) : NestedScrollConnection {
    /** How far the finger has gone past the edge, in pixels: down from the top positive, up from the end negative. */
    var travel by mutableFloatStateOf(0f)
        private set

    /** How far the content is moved past the edge, in pixels. */
    val offset: Float
        get() = stretch(travel)

    /** Whether letting go now acts. */
    val armed: Boolean
        get() = abs(travel) >= threshold

    /** Whether a finger is moving the content, rather than it springing back. */
    val dragging: Boolean
        get() = counts == true

    // Whether the drag under way may move the content; null until its first movement.
    private var counts: Boolean? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val amount = available.y
        // Dragging back toward the content first undoes the distance past the edge.
        return if (source == NestedScrollSource.UserInput && counts == true && travel != 0f && sign(amount) != sign(travel)) {
            val taken = if (abs(amount) >= abs(travel)) -travel else amount
            travel += taken
            Offset(0f, taken)
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val amount = available.y
        if (source == NestedScrollSource.UserInput && counts == null) {
            counts = consumed.y == 0f && amount != 0f
        }
        return if (source == NestedScrollSource.UserInput && counts == true && amount != 0f) {
            travel += amount
            Offset(0f, amount)
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val counted = counts == true
        counts = null
        return if (travel == 0f || !counted) {
            Velocity.Zero
        } else {
            if (armed) {
                onRelease()
            }
            animate(travel, 0f, animationSpec = spring()) { value, _ -> travel = value }
            available
        }
    }
}

/**
 * A haptic tick each time [armed] turns, while [counts] holds when it does: for a pull that crosses
 * the point where letting go acts, either way.
 */
@Composable
fun ThresholdTicks(armed: () -> Boolean, counts: () -> Boolean) {
    val haptics = LocalHapticFeedback.current
    val isArmed by rememberUpdatedState(armed)
    val doesCount by rememberUpdatedState(counts)
    LaunchedEffect(haptics) {
        snapshotFlow { isArmed() }.drop(1).collect {
            if (doesCount()) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
        }
    }
}

/**
 * An [EdgePull] at the thread's distance that follows the finger by [pullStretch], or not at all with
 * animations off, where the indicator alone shows the pull. It ticks as the pull crosses the point
 * where letting go closes, and gives a release haptic before [onClose].
 */
@Composable
fun rememberEdgePull(onClose: () -> Unit): EdgePull {
    val close by rememberUpdatedState(onClose)
    val density = LocalDensity.current
    val animate = animationsOn()
    val haptics = LocalHapticFeedback.current
    val pull = remember(density, animate, haptics) {
        EdgePull(
            with(density) { THREAD_PULL_DISTANCE.dp.toPx() },
            stretch = { travel -> if (animate) density.density * pullStretch(travel / density.density) else 0f },
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            close()
        }
    }
    ThresholdTicks(armed = { pull.armed }, counts = { pull.dragging })
    return pull
}

/**
 * The round marker in the gap [pull] opens, at the top or the end: an arrow pointing the way of the
 * pull that grows in as it goes on and turns over, in the accent color, once letting go closes, when
 * screen readers hear [armedLabel].
 */
@Composable
fun PullIndicator(pull: EdgePull, armedLabel: String) {
    val pulling by remember(pull) { derivedStateOf { pull.travel != 0f } }
    val down by remember(pull) { derivedStateOf { pull.travel > 0f } }
    val armed by remember(pull) { derivedStateOf { pull.armed } }
    if (pulling) {
        val turn by animateFloatAsState(if (armed == down) 180f else 0f, label = "pull arrow")
        val shape = PullToRefreshDefaults.indicatorShape
        Box(Modifier.fillMaxSize(), contentAlignment = if (down) Alignment.TopCenter else Alignment.BottomCenter) {
            Box(
                Modifier
                    .graphicsLayer {
                        val progress = pullProgress(pull.travel / density)
                        translationY = pull.offset / 2
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = scaleX
                        shadowElevation = PullToRefreshDefaults.Elevation.toPx()
                        this.shape = shape
                        clip = true
                    }
                    .size(INDICATOR_SIZE)
                    .background(paper.card, shape)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (armed) {
                            contentDescription = armedLabel
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(ARROW_SIZE).graphicsLayer { rotationZ = turn },
                    tint = if (armed) paper.accent else paper.textSecondary,
                )
            }
        }
    }
}
