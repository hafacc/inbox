package cc.hafa.inbox.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Field corners, as on web.
private val FIELD_SHAPE = RoundedCornerShape(10.dp)

/**
 * The theme's focus outline (keyboard, D-pad) and hover fill (mouse) for a tappable row or
 * button; put it before the element's `clickable`. Touch never focuses, so taps show only the ripple.
 * Without [hoverFill] (a selected row, whose fill shows instead) a hover paints nothing.
 */
fun Modifier.rowHighlight(shape: Shape = RectangleShape, hoverFill: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val colors = paper
    Modifier
        .onFocusChanged { focused = it.hasFocus }
        .hoverable(hoverSource)
        .background(if (hovered && hoverFill) colors.hover else Color.Transparent, shape)
        .then(if (focused) Modifier.border(2.dp, colors.focus, shape) else Modifier)
}

/** A text field's frame: the field fill, a hairline border, and the focus color while typing in it (warning color when [problem]). */
fun Modifier.fieldFrame(problem: Boolean = false, shape: Shape = FIELD_SHAPE): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val colors = paper
    val edge = when {
        problem -> colors.danger
        focused -> colors.focus
        else -> colors.border
    }
    Modifier
        .onFocusChanged { focused = it.hasFocus }
        .background(colors.field, shape)
        .border(if (focused || problem) 2.dp else 1.dp, edge, shape)
}
