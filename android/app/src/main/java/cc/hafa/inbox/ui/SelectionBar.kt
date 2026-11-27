package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.mail.InboxPlan
import cc.hafa.inbox.core.mail.SelectionAction
import cc.hafa.inbox.core.mail.SelectionDiscAction
import cc.hafa.inbox.core.mail.SelectionPlan
import cc.hafa.inbox.core.mail.SetAction
import cc.hafa.inbox.core.mail.TodoSelectionPlan
import cc.hafa.inbox.core.mail.selectionDisc
import cc.hafa.inbox.ui.theme.paper

/** The selection bar's height, which the undo bar sits above while both show. */
internal val SELECTION_BAR_HEIGHT = 56.dp

// The bar's icons, the circle behind each, and the touch target around it, as on the web.
private val BAR_ICON = 18.dp
private val BAR_CIRCLE = 36.dp
private val BAR_TARGET = 48.dp

/** A round icon-only button of the selection bar: a [BAR_CIRCLE] circle in a [BAR_TARGET] tap. */
@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = BAR_ICON) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = BAR_CIRCLE, height = BAR_TARGET)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Icon(icon, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(size))
    }
}

/**
 * The bar at the bottom of the screen while threads and todos are selected, as the web's at phone width: a way to
 * clear them, how many, and Select all (or none). Their actions are on the corner button just above it
 * ([selectionCornerMenu]).
 */
@Composable
fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().height(SELECTION_BAR_HEIGHT).padding(horizontal = 8.dp),
    ) {
        BarIcon(Icons.Outlined.Close, "Clear selection", onClear, size = 20.dp)
        Text(
            "$count",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = paper.text,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 4.dp)
                .semantics {
                    contentDescription = "$count selected"
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Box(Modifier.padding(start = 4.dp)) {
            BarIcon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (allSelected) "Select none" else "Select all", onSelectAll)
        }
    }
}

/**
 * The selection's actions as the corner button's two rings ([selectionDisc]), each shown only where it would
 * change something: Done (or Move to inbox for the [inbox] ones) and Snooze (or Wake now) next to the button,
 * then Pin, a Done or Snooze so displaced, Label, Mark read, Spam and Delete (Move to inbox for threads all in
 * the trash) beyond; for todos alone Done alone next to it, Snooze, Pin and Delete beyond.
 */
internal fun selectionCornerMenu(
    plan: SelectionPlan,
    todoPlan: TodoSelectionPlan,
    inbox: InboxPlan,
    actions: List<SelectionAction>,
    threadCount: Int,
    onAction: (SetAction) -> Unit,
    onDelete: () -> Unit,
    onInbox: (List<String>) -> Unit,
): CornerMenu {
    val pin = todoPlan.pin
    val available = buildList {
        if (SelectionAction.DONE in actions && (inbox.done.isNotEmpty() || todoPlan.done.isNotEmpty())) add(SelectionDiscAction.DONE)
        if (SelectionAction.INBOX in actions && inbox.inbox.isNotEmpty()) add(SelectionDiscAction.INBOX)
        if (SelectionAction.SNOOZE in actions && (threadCount > 0 || todoPlan.snooze.isNotEmpty())) add(SelectionDiscAction.SNOOZE)
        if (SelectionAction.WAKE in actions && plan.wake.isNotEmpty()) add(SelectionDiscAction.WAKE)
        if (SelectionAction.PIN in actions && (pin.threads.isNotEmpty() || pin.todos.isNotEmpty())) add(SelectionDiscAction.PIN)
        if (SelectionAction.LABEL in actions) add(SelectionDiscAction.LABEL)
        if (SelectionAction.READ in actions) add(SelectionDiscAction.READ)
        if (SelectionAction.SPAM in actions) add(SelectionDiscAction.SPAM)
        if (SelectionAction.DELETE in actions && (todoPlan.delete.threads.isNotEmpty() || todoPlan.delete.todos.isNotEmpty())) {
            add(SelectionDiscAction.DELETE)
        }
    }
    fun item(action: SelectionDiscAction): HoldMenuItem = when (action) {
        SelectionDiscAction.DONE -> HoldMenuItem(action.name, "Done", Icons.Outlined.Check, HoldTone.DONE)
        SelectionDiscAction.INBOX -> HoldMenuItem(action.name, "Move to inbox", Icons.Outlined.MoveToInbox)
        SelectionDiscAction.SNOOZE -> HoldMenuItem(action.name, "Snooze", Icons.Outlined.Schedule)
        SelectionDiscAction.WAKE -> HoldMenuItem(action.name, "Wake now", Icons.Outlined.AlarmOff)
        SelectionDiscAction.PIN -> if (pin.on) {
            HoldMenuItem(action.name, "Pin", Icons.Outlined.PushPin)
        } else {
            HoldMenuItem(action.name, "Unpin", Icons.Filled.PushPin, HoldTone.ON)
        }
        SelectionDiscAction.LABEL -> HoldMenuItem(action.name, "Label", Icons.AutoMirrored.Outlined.Label)
        SelectionDiscAction.READ -> if (plan.unread.on) {
            HoldMenuItem(action.name, "Mark unread", Icons.Outlined.MarkEmailUnread)
        } else {
            HoldMenuItem(action.name, "Mark read", Icons.Outlined.MarkEmailRead)
        }
        SelectionDiscAction.SPAM -> if (plan.spam.on) {
            HoldMenuItem(action.name, "Report spam", Icons.Outlined.Report, HoldTone.DANGER)
        } else {
            HoldMenuItem(action.name, "Not spam", Icons.Outlined.VerifiedUser)
        }
        SelectionDiscAction.DELETE -> if (todoPlan.delete.untrash) {
            HoldMenuItem(action.name, "Move to inbox", Icons.Outlined.MoveToInbox)
        } else {
            HoldMenuItem(action.name, "Delete", Icons.Outlined.Delete, HoldTone.DANGER)
        }
    }
    val disc = selectionDisc(available, todosAlone = threadCount == 0)
    return CornerMenu(disc.inner.map(::item), disc.outer.map(::item)) { picked ->
        when (SelectionDiscAction.valueOf(picked.id)) {
            SelectionDiscAction.DONE -> onAction(SetAction.DONE)
            SelectionDiscAction.INBOX -> onInbox(inbox.inbox)
            SelectionDiscAction.SNOOZE -> onAction(SetAction.SNOOZE)
            SelectionDiscAction.WAKE -> onAction(SetAction.WAKE)
            SelectionDiscAction.PIN -> onAction(SetAction.PIN)
            SelectionDiscAction.LABEL -> onAction(SetAction.LABEL)
            SelectionDiscAction.READ -> onAction(SetAction.UNREAD)
            SelectionDiscAction.SPAM -> onAction(SetAction.SPAM)
            SelectionDiscAction.DELETE -> onDelete()
        }
    }
}
