package cc.hafa.inbox.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.abs

/**
 * Keep horizontal drags that start inside the system back-gesture area away from the content, so a
 * back swipe doesn't also swipe the row. Taps, long presses and vertical drags always pass through.
 */
@Composable
fun Modifier.systemEdgeGuard(): Modifier {
    val insets = WindowInsets.systemGestures
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val leftEdge = insets.getLeft(density, direction).toFloat()
    val rightEdge = insets.getRight(density, direction).toFloat()
    var rowLeft by remember { mutableFloatStateOf(0f) }
    var rootWidth by remember { mutableFloatStateOf(0f) }
    return onGloballyPositioned { coordinates ->
        rowLeft = coordinates.positionInRoot().x
        rootWidth = coordinates.findRootCoordinates().size.width.toFloat()
    }.pointerInput(leftEdge, rightEdge) {
        if (leftEdge <= 0f && rightEdge <= 0f) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val screenX = rowLeft + down.position.x
            if (screenX < leftEdge || screenX > rootWidth - rightEdge) {
                val slop = viewConfiguration.touchSlop
                var horizontal = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    if (!horizontal && dy > slop && dy >= dx) break
                    if (dx > slop) horizontal = true
                    if (horizontal) change.consume()
                }
            }
        }
    }
}
