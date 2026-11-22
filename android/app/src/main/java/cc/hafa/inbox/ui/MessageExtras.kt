package cc.hafa.inbox.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cc.hafa.inbox.core.calendar.Attendee
import cc.hafa.inbox.core.calendar.CalendarEvent
import cc.hafa.inbox.core.calendar.EventTime
import cc.hafa.inbox.core.compose.buildProblem
import cc.hafa.inbox.core.filters.ConditionGroup
import cc.hafa.inbox.core.filters.FilterConditions
import cc.hafa.inbox.core.filters.FilterEditor
import cc.hafa.inbox.core.filters.FilterField
import cc.hafa.inbox.core.filters.FilterOperator
import cc.hafa.inbox.core.filters.FilterRow
import cc.hafa.inbox.core.filters.GroupMatch
import cc.hafa.inbox.core.filters.NO_ACTIONS
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.UnsubscribeMethod
import cc.hafa.inbox.core.mail.parseUnsubscribe
import cc.hafa.inbox.core.snooze.SnoozeSuggestions.LABEL_LOCALE
import cc.hafa.inbox.mail.InviteCard
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.mail.UnsubscribeStep
import cc.hafa.inbox.ui.theme.paper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", LABEL_LOCALE)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", LABEL_LOCALE)
private val MONTH = DateTimeFormatter.ofPattern("MMM", LABEL_LOCALE)

/** Open a web page in a Custom Tab (the browser when there is none); [onNoApp] hears why when nothing can. */
fun openWebPage(
    context: Context,
    url: String,
    onNoApp: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (_: ActivityNotFoundException) {
        onNoApp("No app opens this link")
    }
}

/** The filter editor started for a sender: mail from them, skipping the inbox. */
fun senderFilter(email: String): FilterEditor = FilterEditor(
    FilterConditions.Rows(listOf(ConditionGroup(GroupMatch.ALL, listOf(FilterRow(FilterField.FROM, FilterOperator.CONTAINS, email))))),
    NO_ACTIONS.copy(archive = true),
)

/** The quiet bar over a message whose remote images are blocked. */
@Composable
fun ImagesBar(onShow: () -> Unit, onAlways: () -> Unit) {
    FlowRow(
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .heightIn(min = 36.dp)
            .background(paper.muted, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Outlined.HideImage, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
        Text(
            "Images hidden to block tracking",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
        QuietLink("Show images", onShow)
        Text(" · ", style = MaterialTheme.typography.bodySmall, color = paper.faint)
        QuietLink("Always from this sender", onAlways)
    }
}

@Composable
private fun QuietLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = paper.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

/**
 * "Unsubscribe" for a message with a List-Unsubscribe header, in a banner like the hidden-images one:
 * it shows the request working, then how it went.
 */
@Composable
fun UnsubscribeControl(state: MailState, message: MessageView) {
    val context = LocalContext.current
    val step = state.unsubscribes.stepOf(message.id)
    val open = { url: String -> openWebPage(context, url, state::showNotice) }
    val start = { state.unsubscribes.run(message, open) }
    // A failed one-click unsubscribe can still be done on the sender's page.
    val fallbackPage = remember(message.id) {
        parseUnsubscribe(message.listUnsubscribe, message.listUnsubscribePost)
            .takeIf { it.preferred == UnsubscribeMethod.ONE_CLICK }?.url
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .heightIn(min = 36.dp)
            .background(paper.muted, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        when (step) {
            UnsubscribeStep.IDLE -> {
                Icon(Icons.Outlined.Unsubscribe, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
                QuietLink(step.label, start)
            }
            UnsubscribeStep.WORKING -> {
                CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text(step.label, style = MaterialTheme.typography.labelLarge, color = paper.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
            }
            UnsubscribeStep.UNSUBSCRIBED, UnsubscribeStep.REQUESTED -> {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = paper.done, modifier = Modifier.size(16.dp))
                Text(step.label, style = MaterialTheme.typography.labelLarge, color = paper.done, modifier = Modifier.padding(vertical = 6.dp))
            }
            UnsubscribeStep.FAILED, UnsubscribeStep.SIGN_IN, UnsubscribeStep.OFFLINE -> {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = paper.danger, modifier = Modifier.size(16.dp))
                Text(step.label, style = MaterialTheme.typography.labelLarge, color = paper.danger, modifier = Modifier.padding(vertical = 6.dp))
                if (fallbackPage != null) {
                    Text("·", style = MaterialTheme.typography.labelLarge, color = paper.faint)
                    QuietLink("Open page") { open(fallbackPage) }
                }
                Text("·", style = MaterialTheme.typography.labelLarge, color = paper.faint)
                QuietLink("Retry", start)
            }
        }
    }
}

private fun startDate(time: EventTime, zone: ZoneId): LocalDate = when (time) {
    is EventTime.DateOnly -> LocalDate.parse(time.date)
    is EventTime.DateTime -> Instant.ofEpochMilli(time.epochMs).atZone(zone).toLocalDate()
}

/** When an event happens, in the device zone, 24-hour: `Thu, Nov 5 · 10:00–10:30`. */
private fun eventWhen(event: CalendarEvent, zone: ZoneId = ZoneId.systemDefault()): String {
    val start = event.start
    val end = event.end
    return when {
        start is EventTime.DateOnly -> {
            val first = LocalDate.parse(start.date)
            val last = (end as? EventTime.DateOnly)?.let { LocalDate.parse(it.date).minusDays(1) } ?: first
            if (!last.isAfter(first)) "${DAY.format(first)} · All day" else "${DAY.format(first)} – ${DAY.format(last)}"
        }
        start is EventTime.DateTime && end is EventTime.DateTime -> {
            val from = Instant.ofEpochMilli(start.epochMs).atZone(zone)
            val to = Instant.ofEpochMilli(end.epochMs).atZone(zone)
            if (from.toLocalDate() == to.toLocalDate()) {
                "${DAY.format(from)} · ${CLOCK.format(from)}–${CLOCK.format(to)}"
            } else {
                "${DAY.format(from)} ${CLOCK.format(from)} – ${DAY.format(to)} ${CLOCK.format(to)}"
            }
        }
        start is EventTime.DateTime -> {
            val from = Instant.ofEpochMilli(start.epochMs).atZone(zone)
            "${DAY.format(from)} · ${CLOCK.format(from)}"
        }
        else -> ""
    }
}

private val ANSWER_WORDS = mapOf("ACCEPTED" to "accepted", "TENTATIVE" to "said maybe", "DECLINED" to "declined")

/** The invite card: date, title, time and place, conflicts, and Yes / Maybe / No. */
@Composable
fun InviteCardView(card: InviteCard, canAnswer: Boolean, onError: (String) -> Unit, onAnswer: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val event = card.event
    val zone = ZoneId.systemDefault()
    val day = startDate(event.start, zone)
    val canceled = card.invite.method == "CANCEL" || event.status == "CANCELLED"
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .border(1.dp, paper.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(44.dp)
                .background(paper.accentSoft, RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp),
        ) {
            Text(MONTH.format(day), style = MaterialTheme.typography.labelSmall, color = paper.accent)
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = paper.accent)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.summary.ifEmpty { "(no title)" },
                fontWeight = FontWeight.SemiBold,
                color = if (canceled) paper.textSecondary else paper.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val place = event.location.trim()
            Text(
                listOf(eventWhen(event, zone), place).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = paper.textSecondary,
            )
            event.recurrence?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary) }
            event.organizer?.let { organizer ->
                Text(
                    "Organizer: ${organizer.name.ifEmpty { organizer.email }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.conflicts.isNotEmpty() && !canceled) {
                Text(
                    "Overlaps " + card.conflicts.joinToString(", ") { "‘$it’" },
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.snooze,
                )
            }
            when {
                canceled -> Text("Canceled", style = MaterialTheme.typography.labelLarge, color = paper.textSecondary)
                card.invite.method == "REPLY" -> {
                    val who = event.attendees.firstOrNull()
                    Text(
                        replyLine(who),
                        style = MaterialTheme.typography.labelLarge,
                        color = paper.text,
                    )
                }
                canAnswer -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    for ((partstat, label) in listOf("ACCEPTED" to "Yes", "TENTATIVE" to "Maybe", "DECLINED" to "No")) {
                        val chosen = card.answer == partstat
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(32.dp)
                                .width(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (chosen) paper.accent else paper.card)
                                .border(1.dp, if (chosen) paper.accent else paper.border, RoundedCornerShape(16.dp))
                                .clickable(enabled = busy == null) {
                                    busy = partstat
                                    scope.launch {
                                        try {
                                            onAnswer(partstat)
                                        } catch (failure: CancellationException) {
                                            throw failure
                                        } catch (error: Exception) {
                                            onError("Couldn't answer: ${buildProblem(error) ?: error.message ?: "error"}")
                                        } finally {
                                            busy = null
                                        }
                                    }
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    selected = chosen
                                },
                        ) {
                            if (busy == partstat) {
                                CircularProgressIndicator(color = if (chosen) paper.onAccent else paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            } else {
                                Text(label, style = MaterialTheme.typography.labelLarge, color = if (chosen) paper.onAccent else paper.text)
                            }
                        }
                    }
                }
                card.answer != null -> Text("You ${ANSWER_WORDS[card.answer]}", style = MaterialTheme.typography.labelLarge, color = paper.textSecondary)
            }
        }
    }
}

private fun replyLine(who: Attendee?): String =
    if (who == null) {
        "Replied"
    } else {
        "${who.name.ifEmpty { who.email }} ${ANSWER_WORDS[who.partstat] ?: "replied"}"
    }
