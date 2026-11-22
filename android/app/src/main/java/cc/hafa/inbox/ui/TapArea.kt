package cc.hafa.inbox.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The smallest square a finger reliably hits.
private val MIN_TAP = 48.dp

/**
 * A control drawn [shown] square that still takes a 48 dp tap, reaching past its edges without
 * taking more room, as the web's `hit-target`. Put it before the click; the content is laid out
 * in the 48 dp square, so center what it draws.
 */
internal fun Modifier.tapArea(shown: Dp): Modifier = layout { measurable, _ ->
    val area = maxOf(shown, MIN_TAP).roundToPx()
    val size = shown.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(area, area))
    layout(size, size) { placeable.place((size - area) / 2, (size - area) / 2) }
}
