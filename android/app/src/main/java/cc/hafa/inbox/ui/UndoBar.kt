package cc.hafa.inbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.mail.UndoKind
import cc.hafa.inbox.core.mail.formatWakeTime
import cc.hafa.inbox.core.mail.undoText
import cc.hafa.inbox.mail.SendNotice
import cc.hafa.inbox.mail.UndoEntry
import cc.hafa.inbox.ui.theme.paper

/** What the undo bar says about an action, as on web. */
private fun undoLabel(entry: UndoEntry): String =
    undoText(entry.kind, entry.items.size + (entry.todos?.let { it.before.size + it.emailBefore.size } ?: 0), entry.pinned)

/** The dark pill offering to take back the last action. */
@Composable
fun UndoBar(entry: UndoEntry, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = paper.snackbar,
        contentColor = paper.onSnackbar,
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            val icon = when (entry.kind) {
                UndoKind.SNOOZE -> Icons.Outlined.Schedule
                UndoKind.PIN -> Icons.Outlined.PushPin
                UndoKind.DONE -> Icons.Outlined.Check
                UndoKind.SPAM -> Icons.Outlined.Report
                UndoKind.NOT_SPAM -> Icons.Outlined.Inbox
                UndoKind.DELETE, UndoKind.TRASH -> Icons.Outlined.Delete
                UndoKind.MOVE -> Icons.Outlined.SwapVert
                UndoKind.LABEL -> Icons.AutoMirrored.Outlined.Label
                UndoKind.READ -> Icons.Outlined.MarkEmailRead
                UndoKind.UNREAD -> Icons.Outlined.MarkEmailUnread
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                undoLabel(entry),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onUndo) {
                Text("Undo", color = paper.snackbarAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** What the sending bar says. */
private fun sendNoticeLabel(notice: SendNotice, now: Long): String = when (notice) {
    is SendNotice.Sending -> "Sending…"
    is SendNotice.Scheduled -> "Scheduled for ${formatWakeTime(notice.sendAt, now)}"
    is SendNotice.Sent -> "Sent"
    is SendNotice.Failed -> notice.text
    is SendNotice.Saved -> "Saved to Drafts"
    is SendNotice.Discarded -> "Draft discarded"
    is SendNotice.Info -> notice.text
}

/**
 * The dark pill about sending: Undo when [onUndo] is given (a send waiting, a draft just discarded),
 * then sent or why it didn't, with View; a kept draft opens on a tap.
 */
@Composable
fun SendBar(notice: SendNotice, onUndo: (() -> Unit)?, onView: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        enabled = notice is SendNotice.Saved,
        color = paper.snackbar,
        contentColor = paper.onSnackbar,
        shape = CircleShape,
        shadowElevation = 6.dp,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            if (notice is SendNotice.Saved) {
                onClick(label = "Open draft") {
                    onOpen()
                    true
                }
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            val icon = when (notice) {
                is SendNotice.Scheduled -> Icons.Outlined.Schedule
                is SendNotice.Failed -> Icons.Outlined.ErrorOutline
                is SendNotice.Sent -> Icons.Outlined.Check
                is SendNotice.Discarded -> Icons.Outlined.Delete
                else -> Icons.AutoMirrored.Outlined.Send
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                sendNoticeLabel(notice, System.currentTimeMillis()),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp).padding(vertical = 8.dp),
            )
            when {
                onUndo != null -> TextButton(onClick = onUndo) {
                    Text("Undo", color = paper.snackbarAccent, fontWeight = FontWeight.SemiBold)
                }
                notice is SendNotice.Failed -> TextButton(onClick = onView) {
                    Text("View", color = paper.snackbarAccent, fontWeight = FontWeight.SemiBold)
                }
                else -> Spacer(Modifier.width(12.dp))
            }
        }
    }
}
