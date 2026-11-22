package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.viewFromKey
import cc.hafa.inbox.core.menu.viewHoldWedges
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/** A mailbox's icon, in the side menu and on the view name's round menu. */
internal fun mailboxIcon(box: Mailbox): ImageVector = when (box) {
    Mailbox.INBOX -> Icons.Outlined.Inbox
    Mailbox.SNOOZED -> Icons.Outlined.Schedule
    Mailbox.DONE -> Icons.Outlined.Check
    Mailbox.SENT -> Icons.AutoMirrored.Outlined.Send
    Mailbox.DRAFTS -> Icons.Outlined.Description
    Mailbox.SPAM -> Icons.Outlined.Report
}

/** The Todos view's icon, in the side menu and on the view name's round menu. */
internal val TodosIcon: ImageVector get() = Icons.Outlined.TaskAlt

// The views on the name's round menu, in the side menu's order.
private val MENU_BOXES: List<Mailbox> = listOf(Mailbox.INBOX, Mailbox.SNOOZED, Mailbox.DONE)

/** Asks for a list to scroll back to its top, as a tap on the view's name does. */
@Stable
class TopRequests {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Scroll the list following these requests to its top. */
    fun request() {
        requests.tryEmit(Unit)
    }

    /** Scroll [list] to its top on each request: gliding, or at once with the system's animations off. */
    @Composable
    fun Follow(list: LazyListState) {
        val animate = animationsOn()
        LaunchedEffect(list, animate) {
            requests.collectLatest { if (animate) list.animateScrollToItem(0) else list.scrollToItem(0) }
        }
    }
}

/**
 * The view's name in the top bar. A tap runs [onTop], which scrolls the list back to its top; a hold
 * opens the round menu of Inbox, Snoozed, Done and Todos (when [todosOn]) down from it, [current]
 * marked, and picking a view runs [onPick] with it, or [onTop] for the current one. TalkBack reads it
 * as "[title], go to top", with "Switch view" as its long press; a keyboard's Menu key opens the menu.
 */
@Composable
internal fun ViewName(
    title: String,
    current: View?,
    todosOn: Boolean,
    onTop: () -> Unit,
    onPick: (View) -> Unit,
    modifier: Modifier = Modifier,
) {
    val menu = LocalHoldMenu.current
    val haptics = LocalHapticFeedback.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var held by remember { mutableStateOf(false) }
    val top by rememberUpdatedState(onTop)
    val picked by rememberUpdatedState(onPick)
    val shownView by rememberUpdatedState(current)
    val withTodos by rememberUpdatedState(todosOn)
    val interactions = remember { MutableInteractionSource() }
    val open: (Boolean) -> Boolean = remember(menu) {
        { sticky ->
            val placed = coordinates
            if (menu != null && placed != null && placed.isAttached) {
                val boxes: List<Triple<View, String, ImageVector>> = MENU_BOXES.map { box -> Triple(View.Fixed(box), box.title, mailboxIcon(box)) }
                val views = boxes + listOfNotNull(Triple(View.Todos, "Todos", TodosIcon).takeIf { withTodos })
                val items = views.map { (view, name, icon) ->
                    HoldMenuItem(view.key, name, icon, if (view == shownView) HoldTone.ON else HoldTone.PLAIN)
                }
                val spec = HoldMenuSpec(
                    center = placed.localToRoot(Offset(placed.size.width / 2f, placed.size.height / 2f)),
                    wedges = holdWedges(items, viewHoldWedges(items.map { it.id })),
                    pill = "Slide to a view",
                    url = false,
                    label = "Switch view",
                    onPick = { item ->
                        val view = viewFromKey(item.id)
                        if (view == null || view == shownView) top() else picked(view)
                    },
                    onClose = { held = false },
                    stickyPill = "Tap a view",
                )
                menu.show(spec, sticky = sticky)
                held = true
                true
            } else {
                false
            }
        }
    }
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = paper.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .onGloballyPositioned { coordinates = it }
            .clip(RoundedCornerShape(12.dp))
            .background(if (held) paper.hover else Color.Transparent)
            .indication(interactions, ripple())
            .pointerInput(interactions) { showPresses(interactions) }
            .onKeyEvent { event ->
                val tap = event.key == Key.Enter || event.key == Key.Spacebar || event.key == Key.NumPadEnter
                val acted = event.type == KeyEventType.KeyUp && (tap || event.key == Key.Menu)
                if (acted && tap) top() else if (acted) open(true)
                acted
            }
            .focusable(interactionSource = interactions)
            .pointerInput(menu) {
                if (menu != null) {
                    holdMenuGestures(menu, haptics, claim = true, onTap = { top() }, open = { open(false) }) { coordinates }
                } else {
                    detectTapGestures { top() }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "$title, go to top"
                onClick("Go to top") {
                    top()
                    true
                }
                if (menu != null) onLongClick("Switch view") { open(true) }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

// Shows a press on the name as it goes down and up, whatever the hold menu's gestures make of it.
private suspend fun PointerInputScope.showPresses(interactions: MutableInteractionSource) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val press = PressInteraction.Press(down.position)
    interactions.tryEmit(press)
    do {
        val event = awaitPointerEvent()
    } while (event.changes.any { it.id == down.id && it.pressed })
    interactions.tryEmit(PressInteraction.Release(press))
}
