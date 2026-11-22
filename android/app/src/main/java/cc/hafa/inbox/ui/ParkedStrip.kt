package cc.hafa.inbox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.compose.ComposePark
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.launch

/** The parked message's strip at the bottom of the screen, over the navigation bar: its [title]; a tap opens it again. */
@Composable
internal fun ParkedStrip(title: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val edge = paper.border
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp)
            .background(paper.card)
            .drawBehind { drawLine(edge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Draft: $title" }
            .navigationBarsPadding()
            .height(ComposePark.STRIP_HEIGHT.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = paper.accent, modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = paper.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ExpandLess, contentDescription = null, tint = paper.textSecondary)
    }
}

/** Which message last went into the strip, so opening it again grows it from there. */
internal class ParkedMemory {
    /** the parked composer's draft id on this device, until the composer closes */
    var localId: String? = null
}

/** A drag down on the composer's top bar, from [rememberParkDrag]: what [parkDrag] follows. */
internal class ParkDrag(
    /** the drag's deltas, moving the composer */
    val state: DraggableState,
    /** the drag starting */
    val onStart: () -> Unit,
    /** the drag ending, with its speed in px/s */
    val onStop: suspend (Float) -> Unit,
)

/**
 * A drag down on the composer's top bar: it moves the composer down by [drop] (px) with the
 * finger, and letting go parks it ([onPark]) when it went far or fast enough, else springs back.
 */
@Composable
internal fun rememberParkDrag(drop: Animatable<Float, AnimationVector1D>, onPark: () -> Unit): ParkDrag {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val park by rememberUpdatedState(onPark)
    // Where the finger has taken it, ahead of the snaps the drag launches.
    var offset by remember { mutableFloatStateOf(0f) }
    val state = rememberDraggableState { delta ->
        offset = (offset + delta).coerceAtLeast(0f)
        val target = offset
        scope.launch { drop.snapTo(target) }
    }
    return remember(state, drop, density) {
        ParkDrag(
            state = state,
            onStart = { offset = drop.value },
            onStop = { velocity ->
                if (ComposePark.parksOnRelease(offset / density.density, velocity / density.density)) {
                    park()
                } else {
                    drop.animateTo(0f, spring())
                }
            },
        )
    }
}

/** Follows [drag] on this element: the composer's top bar. */
internal fun Modifier.parkDrag(drag: ParkDrag): Modifier = draggable(
    state = drag.state,
    orientation = Orientation.Vertical,
    onDragStarted = { drag.onStart() },
    onDragStopped = { velocity -> drag.onStop(velocity) },
)
