package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.mail.LocalDraft
import cc.hafa.inbox.ui.theme.paper

/** This device's new messages not in Gmail yet, which Drafts shows above its threads, and what a tap on one does. */
data class LocalOnlyRows(
    /** the drafts, newest first */
    val drafts: List<LocalDraft> = emptyList(),
    /** open one, by its local id, in the composer */
    val open: (String) -> Unit = {},
)

/** The text a local-only draft's row shows: its recipients, then its subject and first words. */
fun localDraftLines(draft: LocalDraft): Pair<String, String> {
    val to = draft.to.joinToString(", ") { it.name.ifEmpty { it.email } }
    val bcc = draft.bcc.joinToString(", ") { it.name.ifEmpty { it.email } }
    val recipients = when {
        to.isNotEmpty() -> "To $to"
        bcc.isNotEmpty() -> "To $bcc (Bcc)"
        else -> "(no recipients)"
    }
    val subject = draft.subject.ifEmpty { "(no subject)" }
    return recipients to if (draft.snippet.isEmpty()) subject else "$subject — ${draft.snippet}"
}

/** The card of local-only drafts at the top of Drafts: a heading, then a row each that opens it in the composer. */
@Composable
fun LocalDraftCard(rows: LocalOnlyRows) {
    Column {
        Text(
            "Not in Gmail yet",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = paper.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 18.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(paper.card),
        ) {
            rows.drafts.forEachIndexed { index, draft ->
                if (index > 0) Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(paper.separator))
                LocalDraftRow(draft) { rows.open(draft.draft.localId) }
            }
        }
    }
}

@Composable
private fun LocalDraftRow(draft: LocalDraft, onOpen: () -> Unit) {
    val (recipients, rest) = localDraftLines(draft)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClickLabel = "Edit draft", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(paper.accentSoft, CircleShape)) {
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = paper.accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(recipients, fontWeight = FontWeight.SemiBold, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                rest,
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
