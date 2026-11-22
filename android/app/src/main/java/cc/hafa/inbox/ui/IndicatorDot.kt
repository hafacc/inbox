package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.ui.theme.paper

// Matches web's indicator-dot.svelte: an 8dp dot in a 2dp ring, growing in over 150ms.
private val DOT_SIZE = 8.dp
private val RING_WIDTH = 2.dp
private const val DOT_MOTION_MS = 150
private const val DOT_START_SCALE = 0.3f

/** What an indicator dot means, which sets its color. */
enum class DotTone {
    /** unread mail */
    UNREAD,

    /** something that needs the user */
    PROBLEM,
}

/**
 * A small dot on the top-right corner of [content]: an icon, or with [round] a circle such as an
 * avatar, where it moves in onto the circle's edge. [ring] is the color behind it, so it stands off.
 */
@Composable
fun IndicatorDot(
    show: Boolean,
    tone: DotTone,
    ring: Color,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    content: @Composable () -> Unit,
) {
    val color = when (tone) {
        DotTone.UNREAD -> paper.accent
        DotTone.PROBLEM -> paper.snooze
    }
    val shift = if (round) RING_WIDTH else DOT_SIZE / 2 + RING_WIDTH
    Box(modifier) {
        content()
        AnimatedVisibility(
            visible = show,
            enter = scaleIn(tween(DOT_MOTION_MS), initialScale = DOT_START_SCALE) + fadeIn(tween(DOT_MOTION_MS)),
            exit = scaleOut(tween(DOT_MOTION_MS), targetScale = DOT_START_SCALE) + fadeOut(tween(DOT_MOTION_MS)),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = shift, y = -shift),
        ) {
            Box(
                Modifier
                    .size(DOT_SIZE + RING_WIDTH * 2)
                    .background(ring, CircleShape)
                    .padding(RING_WIDTH)
                    .background(color, CircleShape),
            )
        }
    }
}
