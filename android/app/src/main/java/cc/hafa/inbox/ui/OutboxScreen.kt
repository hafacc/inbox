package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.formatWakeTime
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.HELD_SEND_STATUS
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.blockedText
import cc.hafa.inbox.core.send.canSendAgain
import cc.hafa.inbox.core.send.failureText
import cc.hafa.inbox.core.send.retryingDetail
import cc.hafa.inbox.core.send.retryingText
import cc.hafa.inbox.mail.OutboxItem
import cc.hafa.inbox.mail.Sending
import cc.hafa.inbox.ui.theme.paper

/**
 * Everything not sent yet: scheduled sends, ones going out or being retried, ones waiting for a
 * sign-in or a server fix, and ones that failed, with edit, cancel and send again. Its name in the
 * top bar scrolls back to the top on a tap and switches views on a hold ([onView]), as in the mail views.
 */
@Composable
fun OutboxScreen(
    sending: Sending,
    now: Long,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    todosOn: Boolean,
    onView: (View) -> Unit,
    contentPadding: PaddingValues,
) {
    val items by sending.outboxItems.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val toTop = remember { TopRequests() }
    toTop.Follow(listState)
    ScreenBackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().background(paper.page)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = paper.text)
            }
            ViewName("Outbox", current = null, todosOn = todosOn, onTop = toTop::request, onPick = onView)
        }
        if (items.isEmpty()) {
            Text(
                "Nothing waiting to send. Scheduled messages, and any that haven't gone yet, show here.",
                color = paper.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                items(items, key = { it.doc.id }) { item -> OutboxCard(item, sending, now) }
            }
        }
    }
}

/** How an outbox doc reads: icon, the short status on the right, and the lines under it. */
data class OutboxLook(
    /** the leading icon */
    val icon: ImageVector,
    /** the short status on the right */
    val status: String,
    /** the line under the subject, if any */
    val detail: String?,
    /** the muted line under that, if any */
    val quiet: String?,
)

/** How [doc] reads in the outbox and in its thread. */
fun outboxLook(doc: OutboxDoc, now: Long): OutboxLook = when (doc.status) {
    OutboxStatus.WAITING -> if (doc.sendAt > now) {
        OutboxLook(Icons.Outlined.Schedule, formatWakeTime(doc.sendAt, now), null, null)
    } else {
        OutboxLook(Icons.AutoMirrored.Outlined.Send, "Sending…", null, null)
    }
    OutboxStatus.SENDING -> OutboxLook(Icons.AutoMirrored.Outlined.Send, "Sending…", null, null)
    OutboxStatus.RETRYING -> OutboxLook(
        Icons.Outlined.Sync,
        "Not sent yet",
        retryingText(doc.nextAttemptAt),
        retryingDetail(doc.lastErrorCode, doc.lastErrorMessage),
    )
    OutboxStatus.BLOCKED -> OutboxLook(
        Icons.Outlined.HourglassEmpty,
        "Not sent yet",
        blockedText(doc.blockedOn),
        "It goes by itself after that.",
    )
    OutboxStatus.FAILED -> OutboxLook(Icons.Outlined.ErrorOutline, "Didn't send", failureText(doc.errorCode, doc.errorMessage), null)
    OutboxStatus.SENT -> OutboxLook(Icons.AutoMirrored.Outlined.Send, "Sent", null, null)
}

@Composable
private fun OutboxCard(item: OutboxItem, sending: Sending, now: Long) {
    val doc = item.doc
    val failed = doc.status == OutboxStatus.FAILED
    val editable = !item.held && (doc.status == OutboxStatus.WAITING || doc.status == OutboxStatus.BLOCKED || failed)
    val look = if (item.held) OutboxLook(Icons.Outlined.CloudOff, HELD_SEND_STATUS, null, null) else outboxLook(doc, now)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(paper.card)
            .border(1.dp, paper.border, RoundedCornerShape(12.dp))
            .clickable(enabled = !item.held, onClickLabel = if (editable) "Edit" else "View") { sending.editScheduled(doc) }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(32.dp).background(if (failed) paper.snoozeSoft else paper.accentSoft, CircleShape),
            ) {
                Icon(look.icon, contentDescription = null, tint = if (failed) paper.onSnoozeSoft else paper.accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val to = item.to.joinToString(", ") { it.name.ifEmpty { it.email } }
                Text(
                    to.ifEmpty { "No recipients" },
                    fontWeight = FontWeight.SemiBold,
                    color = paper.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.subject?.ifEmpty { "(no subject)" } ?: "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                look.status,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = if (failed) paper.onSnoozeSoft else paper.textSecondary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        look.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = paper.text,
                modifier = Modifier.padding(start = 44.dp, top = 6.dp, end = 8.dp),
            )
        }
        look.quiet?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
                modifier = Modifier.padding(start = 44.dp, top = 2.dp, end = 8.dp),
            )
        }
        if (item.localId != null) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { sending.cancelHeld(item.localId) }) { Text("Cancel", color = paper.textSecondary) }
            }
        } else if (!item.held) {
            OutboxActions(item, sending, editable)
        }
    }
}

// Edit, send again and cancel under a send that has its outbox doc.
@Composable
private fun OutboxActions(item: OutboxItem, sending: Sending, editable: Boolean) {
    val doc = item.doc
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        if (editable) {
            TextButton(onClick = { sending.editScheduled(doc) }) { Text("Edit", color = paper.accent) }
        }
        if (doc.canSendAgain) {
            TextButton(onClick = { sending.sendAgain(doc) }) { Text("Send again", color = paper.accent, fontWeight = FontWeight.SemiBold) }
        }
        TextButton(onClick = { sending.cancelScheduled(doc) }) {
            Text(if (doc.status == OutboxStatus.FAILED) "Keep as draft" else "Cancel", color = paper.textSecondary)
        }
    }
}
