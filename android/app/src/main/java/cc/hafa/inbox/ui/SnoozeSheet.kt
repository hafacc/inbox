package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NextWeek
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.snooze.QuickTime
import cc.hafa.inbox.core.snooze.RowIcon
import cc.hafa.inbox.core.snooze.SnoozeRow
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import cc.hafa.inbox.core.todo.Repeat
import cc.hafa.inbox.core.todo.RepeatDraft
import cc.hafa.inbox.core.todo.RepeatUnit
import cc.hafa.inbox.data.SnoozeSource
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/** Where a time picker sheet is: its menu, the date picker or the clock. */
internal enum class SheetStep { MENU, DATE, TIME }

/** Where the snooze sheet is: a time picker sheet's steps, then a todo's repeat rule and its clock. */
private enum class SnoozeStep { MENU, DATE, TIME, REPEAT, REPEAT_TIME }

/** One menu row: a learned suggestion, the last custom pick, the date picker, or a todo's repeat rule. */
private sealed class MenuRow {
    data class Suggestion(val row: SnoozeRow) : MenuRow()
    data class Last(val epochMillis: Long) : MenuRow()
    data object Custom : MenuRow()
    data object Repeat : MenuRow()
}

private val WEEKDAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

// latr's starting rule: every week on today's weekday (or day of the month), at the strongest quick time.
private fun initialDraft(repeat: Repeat?, today: LocalDate, quickTimes: List<QuickTime>): RepeatDraft =
    if (repeat != null) {
        RepeatDraft(repeat.every, repeat.unit, repeat.weekday, repeat.monthDay, repeat.at)
    } else {
        RepeatDraft(
            every = 1,
            unit = RepeatUnit.WEEK,
            weekday = today.dayOfWeek.value,
            monthDay = today.dayOfMonth,
            at = quickTimes.maxByOrNull { it.weight }?.let { clockText(it.clockMinutes) } ?: "09:00",
        )
    }

private val SHEET_CLOCK_TICK = 30.seconds

/** Remember the current instant, moved on every half minute while the sheet is open. */
@Composable
internal fun rememberSheetClock(): MutableState<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SHEET_CLOCK_TICK)
            now.value = Instant.now()
        }
    }
    return now
}

/** Remember a date picker that offers today and later, starting on the default custom date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberFutureDatePickerState(now: Instant, zone: ZoneId): DatePickerState = rememberDatePickerState(
    initialSelectedDateMillis = pickerMillis(SnoozeSuggestions.defaultCustomDate(LocalDateTime.ofInstant(now, zone))),
    selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = !pickerDate(utcTimeMillis).isBefore(LocalDate.now(zone))
    },
)

/** How a sheet closes after a pick, once: it slides away, then the dismiss removes it. */
internal class SheetCloser(private val start: () -> Unit) {
    /** whether the sheet is already sliding away, when further picks are ignored */
    var closing = false
        private set

    /** Start closing, unless it already is. */
    operator fun invoke() {
        if (!closing) {
            closing = true
            start()
        }
    }
}

/** Remember how a sheet closes after a pick: it slides away, then [onDismiss] removes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetCloser(sheetState: SheetState, onDismiss: () -> Unit): SheetCloser {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    return remember(sheetState) {
        SheetCloser { scope.launch { sheetState.hide() }.invokeOnCompletion { dismiss() } }
    }
}

/** The steps a sheet moves through slide sideways: forward to the left, back to the right. */
internal fun <S : Enum<S>> AnimatedContentTransitionScope<S>.sheetStepTransition(): ContentTransform =
    sheetSlide(targetState.ordinal > initialState.ordinal)

/** A sheet step's sideways slide, forward or back. */
internal fun sheetSlide(forward: Boolean): ContentTransform =
    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())

/** Back steps from the clock to the date, and from the date to the menu. */
@Composable
internal fun SheetBackHandler(step: SheetStep, onStep: (SheetStep) -> Unit) {
    LayerBackHandler(open = step != SheetStep.MENU) {
        onStep(if (step == SheetStep.TIME) SheetStep.DATE else SheetStep.MENU)
    }
}

/** The icon for a suggestion row's kind. */
internal fun rowIconVector(icon: RowIcon): ImageVector = when (icon) {
    RowIcon.OFFSET -> Icons.Rounded.Schedule
    RowIcon.TODAY_DAY -> Icons.Rounded.LightMode
    RowIcon.TODAY_NIGHT -> Icons.Rounded.Nightlight
    RowIcon.TOMORROW -> Icons.Rounded.Today
    RowIcon.DAYS -> Icons.Rounded.DateRange
    RowIcon.WEEKDAY -> Icons.AutoMirrored.Rounded.NextWeek
    RowIcon.MONTHLY -> Icons.Rounded.Event
}

/** A time picker sheet's menu row: icon tile, label, and the time it picks (a chevron when it opens a picker). */
@Composable
internal fun SheetRow(icon: ImageVector, label: String, time: String?, tile: Color, ink: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(tile, CircleShape)) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = paper.text, modifier = Modifier.weight(1f))
        if (time != null) {
            Text(time, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = paper.textSecondary)
        } else {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = paper.textSecondary)
        }
    }
}

/** Material's DatePicker speaks in UTC-midnight millis, whatever the device zone. */
internal fun pickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun pickerDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun clockText(clockMinutes: Int): String = LocalTime.of(clockMinutes / 60, clockMinutes % 60).toString()

private fun buildMenuRows(partitions: List<SnoozeStatsSnapshot>, deep: List<SnoozeRow>, now: Instant, repeats: Boolean): List<MenuRow> {
    val suggestions = SnoozeSuggestions.shownRows(deep)
    val rows = mutableListOf<MenuRow>()
    rows += suggestions.map { MenuRow.Suggestion(it) }
    SnoozeSuggestions.lastRow(partitions, now, suggestions)?.let { rows += MenuRow.Last(it) }
    rows += MenuRow.Custom
    if (repeats) rows += MenuRow.Repeat
    return rows
}

/**
 * latr's snooze sheet: learned suggestions, the last custom time, then a date and time picker.
 *
 * [onSnooze] gets the time, its source (`suggestion`, `last` or `custom`) and the picked row's key,
 * and returns false if the time already passed; the sheet then stays open with fresh rows. With
 * [onRepeat] (a todo), a last row edits its [repeat] rule, as latr's does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(
    title: String,
    stats: SnoozeStatsSnapshot,
    onDismiss: () -> Unit,
    onSnooze: (epochMillis: Long, source: SnoozeSource, pickedKey: String?) -> Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
    repeat: Repeat? = null,
    onRepeat: ((RepeatDraft) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var now by rememberSheetClock()
    val partitions = listOf(stats)
    val deep = remember(now, stats, zone) { SnoozeSuggestions.rankDeep(partitions, now, zone) }
    val rows = remember(deep, stats, now) { buildMenuRows(partitions, deep, now, onRepeat != null) }
    val quickTimes = remember(now, stats) { SnoozeSuggestions.quickTimes(partitions, now) }
    var step by rememberSaveable { mutableStateOf(SnoozeStep.MENU) }
    val today = LocalDate.ofInstant(now, zone)
    val datePickerState = rememberFutureDatePickerState(now, zone)
    var draft by rememberSaveable(stateSaver = repeatDraftSaver) { mutableStateOf(initialDraft(repeat, today, quickTimes)) }
    val close = rememberSheetCloser(sheetState, onDismiss)

    // A tap on the sheet while it slides away doesn't act again.
    val pick = { epochMillis: Long, source: SnoozeSource, pickedKey: String? ->
        if (!close.closing) {
            if (onSnooze(epochMillis, source, pickedKey)) close() else now = Instant.now()
        }
    }
    val saveRepeat = { at: String ->
        if (!close.closing) {
            onRepeat?.invoke(draft.copy(at = at))
            close()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        LayerBackHandler(open = step != SnoozeStep.MENU) {
            step = when (step) {
                SnoozeStep.TIME -> SnoozeStep.DATE
                SnoozeStep.REPEAT_TIME -> SnoozeStep.REPEAT
                else -> SnoozeStep.MENU
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = { sheetStepTransition() },
            label = "snoozeStep",
        ) { current ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                when (current) {
                    // Scrolls when the rows don't fit (landscape, large text).
                    SnoozeStep.MENU -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SheetHeader("Snooze", title)
                        rows.forEach { row ->
                            MenuRowItem(row, repeat, now, zone) {
                                when (row) {
                                    is MenuRow.Custom -> step = SnoozeStep.DATE
                                    is MenuRow.Repeat -> step = SnoozeStep.REPEAT
                                    is MenuRow.Suggestion -> pick(row.row.epochMillis, SnoozeSource.SUGGESTION, row.row.key)
                                    is MenuRow.Last -> pick(row.epochMillis, SnoozeSource.LAST, null)
                                }
                            }
                        }
                    }
                    SnoozeStep.DATE -> DateStep(
                        datePickerState = datePickerState,
                        title = title,
                        quickTimes = quickTimes,
                        now = now,
                        zone = zone,
                        onBack = { step = SnoozeStep.MENU },
                        onQuickTime = { epochMillis -> pick(epochMillis, SnoozeSource.CUSTOM, null) },
                        onOtherTime = { step = SnoozeStep.TIME },
                    )
                    SnoozeStep.TIME -> TimeStep(
                        selectedDate = datePickerState.selectedDateMillis?.let(::pickerDate) ?: today,
                        title = title,
                        quickTimes = quickTimes,
                        now = now,
                        zone = zone,
                        onBack = { step = SnoozeStep.DATE },
                        onConfirm = { epochMillis -> pick(epochMillis, SnoozeSource.CUSTOM, null) },
                    )
                    SnoozeStep.REPEAT -> RepeatStep(
                        draft = draft,
                        onDraftChange = { draft = it },
                        title = title,
                        quickTimes = quickTimes,
                        onBack = { step = SnoozeStep.MENU },
                        onQuickTime = saveRepeat,
                        onOtherTime = { step = SnoozeStep.REPEAT_TIME },
                    )
                    SnoozeStep.REPEAT_TIME -> RepeatTimeStep(
                        at = draft.at,
                        title = title,
                        onBack = { step = SnoozeStep.REPEAT },
                        onSave = saveRepeat,
                    )
                }
            }
        }
    }
}

/** A sheet step's heading, with the item it's about below and an optional back button. */
@Composable
internal fun SheetHeader(heading: String, title: String, onBack: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack == null) 24.dp else 8.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Column {
            Text(heading, style = MaterialTheme.typography.titleLarge, color = paper.text)
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QuickTimeButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        val timeStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
        Text(text, style = timeStyle, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = timeStyle.fontSize))
    }
}

/** The date step of a time picker sheet: a calendar, learned quick times, and "Other time". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateStep(
    datePickerState: DatePickerState,
    title: String,
    quickTimes: List<QuickTime>,
    now: Instant,
    zone: ZoneId,
    onBack: () -> Unit,
    onQuickTime: (epochMillis: Long) -> Unit,
    onOtherTime: () -> Unit,
) {
    val selectedDate = datePickerState.selectedDateMillis?.let(::pickerDate)
    SheetHeader("Pick a date", title, onBack)
    DatePicker(
        state = datePickerState,
        showModeToggle = false,
        title = null,
        headline = null,
        colors = DatePickerDefaults.colors(containerColor = paper.card),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        quickTimes.forEach { quickTime ->
            val epoch = selectedDate?.let { SnoozeSuggestions.quickTimeEpoch(it, quickTime.clockMinutes, zone) }
            QuickTimeButton(
                text = clockText(quickTime.clockMinutes),
                enabled = epoch != null && epoch > now.toEpochMilli(),
                onClick = { epoch?.let(onQuickTime) },
                modifier = Modifier.weight(1f),
            )
        }
        if (quickTimes.isEmpty()) Box(Modifier.weight(1f))
        TextButton(onClick = onOtherTime, enabled = selectedDate != null) { Text("Other time") }
    }
}

/** The time step of a time picker sheet: a 24-hour clock and a confirm button labeled [confirmLabel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeStep(
    selectedDate: LocalDate,
    title: String,
    quickTimes: List<QuickTime>,
    now: Instant,
    zone: ZoneId,
    onBack: () -> Unit,
    onConfirm: (epochMillis: Long) -> Unit,
    confirmLabel: String = "Snooze",
) {
    val initialTime = remember(selectedDate) {
        val best = quickTimes.maxByOrNull { it.weight }
        if (best != null && SnoozeSuggestions.quickTimeEpoch(selectedDate, best.clockMinutes, zone) > now.toEpochMilli()) {
            LocalTime.of(best.clockMinutes / 60, best.clockMinutes % 60)
        } else {
            SnoozeSuggestions.nextQuarterHour(LocalTime.ofInstant(now, zone))
        }
    }
    val timePickerState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
    val selectedEpoch = LocalDateTime.of(selectedDate, LocalTime.of(timePickerState.hour, timePickerState.minute))
        .atZone(zone).toInstant().toEpochMilli()
    SheetHeader("Pick a time", title, onBack)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        TimePicker(state = timePickerState)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onConfirm(selectedEpoch) },
                enabled = selectedEpoch > now.toEpochMilli(),
                colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
            ) { Text(confirmLabel) }
        }
    }
}

/** latr's repeat editor: every 1–7 days, weeks or months, on a weekday or day of the month, at a time. */
@Composable
private fun RepeatStep(
    draft: RepeatDraft,
    onDraftChange: (RepeatDraft) -> Unit,
    title: String,
    quickTimes: List<QuickTime>,
    onBack: () -> Unit,
    onQuickTime: (at: String) -> Unit,
    onOtherTime: () -> Unit,
) {
    SheetHeader("Repeat", title, onBack)
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        RepeatSection("Every") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (count in 1..MAX_EVERY) {
                    ChoiceButton(
                        label = count.toString(),
                        selected = draft.every == count,
                        onClick = { onDraftChange(draft.copy(every = count)) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RepeatUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = draft.unit == unit,
                        onClick = { onDraftChange(draft.copy(unit = unit)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RepeatUnit.entries.size),
                    ) {
                        Text(if (draft.every > 1) "${unit.wire}s" else unit.wire)
                    }
                }
            }
        }
        when (draft.unit) {
            RepeatUnit.WEEK -> RepeatSection("On") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    WEEKDAY_NAMES.forEachIndexed { index, name ->
                        val isoDay = index + 1
                        ChoiceButton(
                            label = name.take(1),
                            description = name,
                            selected = isoDay == draft.weekday,
                            onClick = { onDraftChange(draft.copy(weekday = isoDay)) },
                            modifier = Modifier.weight(1f).height(48.dp),
                        )
                    }
                }
            }
            RepeatUnit.MONTH -> RepeatSection("On") {
                val weeks = (1..MONTH_GRID_DAYS).chunked(WEEKDAY_NAMES.size) + listOf(listOf(29, 30, 31, Repeat.LAST_DAY))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (week in weeks) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            for (day in week) {
                                val last = day == Repeat.LAST_DAY
                                ChoiceButton(
                                    label = if (last) "Last day" else day.toString(),
                                    selected = draft.monthDay == day,
                                    bordered = last,
                                    onClick = { onDraftChange(draft.copy(monthDay = day)) },
                                    modifier = Modifier.weight(if (last) LAST_DAY_WEIGHT else 1f).height(48.dp),
                                )
                            }
                        }
                    }
                }
            }
            RepeatUnit.DAY -> Unit
        }
        RepeatSection("At") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (quickTime in quickTimes) {
                    val at = clockText(quickTime.clockMinutes)
                    QuickTimeButton(at, enabled = true, onClick = { onQuickTime(at) }, modifier = Modifier.weight(1f))
                }
                if (quickTimes.isEmpty()) Box(Modifier.weight(1f))
                TextButton(onClick = onOtherTime) { Text("Other time") }
            }
        }
    }
}

// Every 1 to 7; the month grid's full weeks, then 29–31 and "Last day" four days wide.
private const val MAX_EVERY = 7
private const val MONTH_GRID_DAYS = 28
private const val LAST_DAY_WEIGHT = 4f

@Composable
private fun RepeatSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = paper.textSecondary, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    bordered: Boolean = true,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) paper.accent else Color.Transparent,
        contentColor = if (selected) paper.onAccent else paper.textSecondary,
        border = if (selected || !bordered) null else BorderStroke(1.dp, paper.border),
        modifier = modifier.semantics { if (description != null) contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/** The repeat rule's clock, a 24-hour time picker that saves the rule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatTimeStep(at: String, title: String, onBack: () -> Unit, onSave: (at: String) -> Unit) {
    val initialTime = remember(at) { LocalTime.parse(at) }
    val timePickerState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
    SheetHeader("Pick a time", title, onBack)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        TimePicker(state = timePickerState)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onSave(LocalTime.of(timePickerState.hour, timePickerState.minute).toString()) },
                colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun MenuRowItem(row: MenuRow, repeat: Repeat?, now: Instant, zone: ZoneId, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val (label, time) = when (row) {
        is MenuRow.Suggestion ->
            SnoozeSuggestions.labelText(row.row.key, row.row.epochMillis, now, zone) to
                SnoozeSuggestions.formatClock(row.row.epochMillis, zone)
        is MenuRow.Last -> SnoozeSuggestions.lastText(row.epochMillis, now, zone) to SnoozeSuggestions.formatClock(row.epochMillis, zone)
        is MenuRow.Custom -> "Pick a date & time" to null
        is MenuRow.Repeat -> (repeat?.text() ?: "Repeat…") to null
    }
    val (tile, ink) = when (row) {
        is MenuRow.Suggestion -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        else -> colorScheme.surfaceContainerHigh to colorScheme.onSurfaceVariant
    }
    SheetRow(row.icon(now, zone), label, time, tile, ink, onClick)
}

private fun MenuRow.icon(now: Instant, zone: ZoneId): ImageVector = when (this) {
    is MenuRow.Suggestion -> rowIconVector(SnoozeSuggestions.rowIcon(row.key, row.epochMillis, now, zone))
    is MenuRow.Last -> Icons.Rounded.History
    is MenuRow.Custom -> Icons.Rounded.CalendarMonth
    is MenuRow.Repeat -> Icons.Rounded.Repeat
}

// Keeps the Repeat steps' choices across a turn of the phone, as the step itself is kept.
private val repeatDraftSaver: Saver<RepeatDraft, Any> = listSaver(
    save = { listOf(it.every, it.unit.name, it.weekday, it.monthDay, it.at) },
    restore = { RepeatDraft(it[0] as Int, RepeatUnit.valueOf(it[1] as String), it[2] as Int, it[3] as Int, it[4] as String) },
)
