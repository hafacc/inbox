package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.mail.formatWakeTime
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.send.VarySetting
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import cc.hafa.inbox.data.SnoozeSource
import cc.hafa.inbox.ui.theme.paper
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A time picked in the schedule sheet: when it goes, what feeds the stats, and how it was picked. */
data class ScheduledPick(
    /** `outbox.sendAt`: the shown (maybe shifted) time */
    val sendAt: Long,
    /** `outbox.chosenAt`: the unshifted suggestion, or null for a typed time */
    val chosenAt: Long?,
    /** the time the stats learn from */
    val learnAt: Long,
    /** `suggestion`, `last` or `custom` */
    val source: SnoozeSource,
    /** the suggestion row's key */
    val keyId: String?,
)

private val seeds = SecureRandom()

/**
 * "Send at": learned send times (shifted by up to the setting's minutes when [vary] is on, with
 * one seed per opening so the times hold still), the last custom time, then a date and time picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(
    stats: SnoozeStatsSnapshot,
    vary: VarySetting,
    onDismiss: () -> Unit,
    onPick: (ScheduledPick) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var now by rememberSheetClock()
    val seed = remember { seeds.nextInt().toLong() and 0xFFFFFFFFL }
    val partitions = listOf(stats)
    val rows = remember(stats, now) { SendSuggestions.sendRows(partitions, now, zone) }
    val shifted = remember(rows, vary, seed) {
        val times = rows.map { it.time }
        if (vary.enabled) {
            SendSuggestions.varySendTimes(times, now.toEpochMilli(), SendSuggestions.seededRandom(seed), vary.maxMinutes)
        } else {
            SendSuggestions.unvariedSendTimes(times)
        }
    }
    val last = remember(rows, stats, now) { SendSuggestions.lastRow(partitions, now, rows) }
    val quickTimes = remember(stats, now) { SnoozeSuggestions.quickTimes(partitions, now) }
    var step by rememberSaveable { mutableStateOf(SheetStep.MENU) }
    val datePickerState = rememberFutureDatePickerState(now, zone)
    var picked by remember { mutableStateOf<ScheduledPick?>(null) }
    // The send closes the composer, and the sheet with it, so it goes once the sheet has slid away.
    val close = rememberSheetCloser(sheetState) {
        picked?.let(onPick)
        onDismiss()
    }
    val pick = { result: ScheduledPick ->
        if (!close.closing) {
            if (result.sendAt > System.currentTimeMillis()) {
                picked = result
                close()
            } else {
                now = Instant.now()
            }
        }
    }
    val custom = { epoch: Long -> pick(ScheduledPick(epoch, null, epoch, SnoozeSource.CUSTOM, null)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        SheetBackHandler(step) { step = it }
        AnimatedContent(targetState = step, transitionSpec = { sheetStepTransition() }, label = "scheduleStep") { current ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                when (current) {
                    // Scrolls when the rows don't fit (landscape, large text).
                    SheetStep.MENU -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Send at",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = paper.text,
                            modifier = Modifier.padding(horizontal = 24.dp).height(48.dp).padding(top = 12.dp),
                        )
                        rows.forEachIndexed { index, row ->
                            val time = shifted[index]
                            ScheduleRow(rowIconVector(row.icon), row.text, formatWakeTime(time.sendAt, now.toEpochMilli(), zone), highlighted = row.learned) {
                                pick(ScheduledPick(time.sendAt, time.chosenAt, time.chosenAt, SnoozeSource.SUGGESTION, row.keyId))
                            }
                        }
                        if (last != null) {
                            ScheduleRow(Icons.Rounded.History, SnoozeSuggestions.lastText(last, now, zone), formatWakeTime(last, now.toEpochMilli(), zone)) {
                                pick(ScheduledPick(last, last, last, SnoozeSource.LAST, null))
                            }
                        }
                        ScheduleRow(Icons.Rounded.CalendarMonth, SendSuggestions.PICK_DATE_TIME_LABEL, null) { step = SheetStep.DATE }
                    }
                    SheetStep.DATE -> DateStep(
                        datePickerState = datePickerState,
                        title = "Send at",
                        quickTimes = quickTimes,
                        now = now,
                        zone = zone,
                        onBack = { step = SheetStep.MENU },
                        onQuickTime = custom,
                        onOtherTime = { step = SheetStep.TIME },
                    )
                    SheetStep.TIME -> TimeStep(
                        selectedDate = datePickerState.selectedDateMillis?.let(::pickerDate) ?: LocalDate.now(zone),
                        title = "Send at",
                        quickTimes = quickTimes,
                        now = now,
                        zone = zone,
                        onBack = { step = SheetStep.DATE },
                        onConfirm = custom,
                        confirmLabel = "Schedule",
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(icon: ImageVector, label: String, time: String?, highlighted: Boolean = false, onClick: () -> Unit) {
    SheetRow(
        icon = icon,
        label = label,
        time = time,
        tile = if (highlighted) paper.accentSoft else paper.muted,
        ink = if (highlighted) paper.accent else paper.textSecondary,
        onClick = onClick,
    )
}
