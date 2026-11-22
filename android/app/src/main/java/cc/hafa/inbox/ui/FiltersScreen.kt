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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.filters.ConditionGroup
import cc.hafa.inbox.core.filters.FilterActions
import cc.hafa.inbox.core.filters.FilterConditions
import cc.hafa.inbox.core.filters.FilterEditor
import cc.hafa.inbox.core.filters.FilterField
import cc.hafa.inbox.core.filters.FilterOperator
import cc.hafa.inbox.core.filters.FilterRow
import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.filters.GroupMatch
import cc.hafa.inbox.core.filters.Importance
import cc.hafa.inbox.core.filters.NO_ACTIONS
import cc.hafa.inbox.core.filters.actionFromGmail
import cc.hafa.inbox.core.filters.conditionsToSearch
import cc.hafa.inbox.core.filters.criteriaToSearch
import cc.hafa.inbox.core.filters.filterIsWhole
import cc.hafa.inbox.core.filters.filterToEditor
import cc.hafa.inbox.core.filters.labelChanges
import cc.hafa.inbox.core.filters.rowError
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.delay

private const val PREVIEW_DELAY_MS = 600L

private val FIELD_NAMES = linkedMapOf(
    FilterField.FROM to "From",
    FilterField.TO to "To",
    FilterField.SUBJECT to "Subject",
    FilterField.HAS_WORDS to "Has the words",
    FilterField.DOES_NOT_HAVE to "Doesn't have",
    FilterField.SIZE to "Size",
    FilterField.HAS_ATTACHMENT to "Has attachment",
)

private val MATCH_NAMES = linkedMapOf(GroupMatch.ALL to "All of these", GroupMatch.ANY to "Any of these", GroupMatch.NOT to "None of these")

private fun newRow(field: FilterField = FilterField.FROM): FilterRow = when (field) {
    FilterField.SIZE -> FilterRow(field, FilterOperator.LARGER, "")
    FilterField.HAS_ATTACHMENT -> FilterRow(field, FilterOperator.IS, "")
    else -> FilterRow(field, FilterOperator.CONTAINS, "")
}

/** What a filter does, in a few words, for the list. */
private fun actionsLine(actions: FilterActions, labels: Map<String, GmailLabel>): String = buildList {
    actions.labelId?.let { add(labels[it]?.name ?: "Label") }
    if (actions.archive) add("Skip inbox")
    if (actions.markRead) add("Mark as read")
    if (actions.star) add("Pin")
    if (actions.neverSpam) add("Never spam")
    when (actions.important) {
        Importance.ALWAYS -> add("Mark important")
        Importance.NEVER -> add("Never important")
        null -> Unit
    }
    if (actions.trash) add("Delete")
    actions.forward?.let { add("Forward to $it") }
}.joinToString(" · ").ifEmpty { "Does nothing" }

/** Settings › Filters: the account's Gmail filters, with edit and delete. */
@Composable
fun FiltersScreen(state: MailState, labels: List<GmailLabel>, onBack: () -> Unit, onEdit: (GmailFilter?) -> Unit) {
    val filters by state.filters.filters.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<GmailFilter?>(null) }
    val byId = labels.associateBy { it.id }
    LaunchedEffect(Unit) { state.filters.load() }
    ScreenBackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().background(paper.page)) {
        PlainTopBar("Filters", onBack) {
            TextButton(onClick = { onEdit(null) }) { Text("New filter", color = paper.accent, fontWeight = FontWeight.SemiBold) }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "These are your Gmail filters, so they sort mail everywhere you read it. Saving an edit replaces the filter in Gmail.",
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            when {
                filters.loading && filters.filters.isEmpty() -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    CircularProgressIndicator(color = paper.accent)
                }
                filters.error != null -> Text(filters.error ?: "", color = paper.textSecondary, modifier = Modifier.padding(4.dp))
                filters.loaded && filters.filters.isEmpty() -> Text("No filters yet.", color = paper.textSecondary, modifier = Modifier.padding(4.dp))
            }
            for (filter in filters.filters) {
                val criteria = filter.criteria
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(paper.card)
                        .border(1.dp, paper.border, RoundedCornerShape(12.dp))
                        .clickable { onEdit(filter) }
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    Text(
                        criteria?.let(::criteriaToSearch).orEmpty().ifEmpty { "(matches everything)" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = paper.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        actionsLine(filter.action?.let(::actionFromGmail) ?: NO_ACTIONS, byId),
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onEdit(filter) }) { Text("Edit", color = paper.accent) }
                        TextButton(onClick = { deleting = filter }) { Text("Delete", color = paper.textSecondary) }
                    }
                }
            }
        }
    }
    deleting?.let { filter ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = paper.card,
            title = { Text("Delete this filter?", color = paper.text) },
            text = { Text("Gmail stops applying it everywhere. Mail it already sorted stays where it is.", color = paper.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    filter.id?.let(state::deleteFilter)
                }) { Text("Delete", color = paper.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = paper.textSecondary) } },
        )
    }
}

/**
 * The filter editor: condition groups of rows (all / any / none), the Gmail search they make,
 * what the filter does, and a live count of the conversations it matches. Filters that don't
 * parse into rows edit as raw search text. Back saves a changed filter, as Save does.
 */
@Composable
fun FilterEditorScreen(
    state: MailState,
    labels: List<GmailLabel>,
    filter: GmailFilter?,
    start: FilterEditor?,
    onDone: () -> Unit,
    onShow: (String) -> Unit,
) {
    val filters by state.filters.filters.collectAsStateWithLifecycle()
    val initial = remember(filter, start) {
        start ?: filter?.let(::filterToEditor) ?: FilterEditor(FilterConditions.Rows(listOf(ConditionGroup(GroupMatch.ALL, listOf(newRow())))), NO_ACTIONS)
    }
    var conditions by rememberSaveable(initial, stateSaver = jsonSaver<FilterConditions>()) { mutableStateOf(initial.conditions) }
    var actions by rememberSaveable(initial, stateSaver = jsonSaver<FilterActions>()) { mutableStateOf(initial.actions) }
    var preview by remember { mutableStateOf<String?>(null) }
    var applyExisting by rememberSaveable { mutableStateOf(true) }
    val query = conditionsToSearch(conditions)
    val whole = filterIsWhole(FilterEditor(conditions, actions))
    LaunchedEffect(Unit) { if (!filters.loaded) state.filters.load() }
    LaunchedEffect(query) {
        preview = null
        delay(PREVIEW_DELAY_MS)
        preview = runSuspendCatching { state.filters.previewCount(query) }.getOrNull()
    }
    // Set once saved or left: the screen stays while it slides away, and mustn't save twice.
    var finished by remember { mutableStateOf(false) }

    fun save() {
        if (finished) return
        finished = true
        state.saveFilter(FilterEditor(conditions, actions), filter?.id)
        if (applyExisting) {
            val (add, remove) = labelChanges(actions)
            state.applyToMatching(query, add, remove)
        }
        onDone()
    }

    // Back keeps what was changed, as Save does; a filter that matches or does nothing can't be saved.
    fun leave() {
        val changed = conditions != initial.conditions || actions != initial.actions
        if (changed && whole) {
            save()
        } else if (!finished) {
            finished = true
            onDone()
        }
    }
    ScreenBackHandler(onBack = ::leave)
    Column(modifier = Modifier.fillMaxSize().background(paper.page).imePadding()) {
        PlainTopBar(if (filter == null) "New filter" else "Edit filter", ::leave) {
            TextButton(enabled = whole, onClick = ::save) {
                Text("Save", color = paper.accent, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            FilterHeading("Match")
            when (val current = conditions) {
                is FilterConditions.Raw -> {
                    Text(
                        "This filter doesn't fit into rows, so it is edited as Gmail search text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                    )
                    TextBox(current.query, "Gmail search", monospace = true) { conditions = FilterConditions.Raw(it) }
                }
                is FilterConditions.Rows -> {
                    current.groups.forEachIndexed { groupIndex, group ->
                        GroupCard(
                            group = group,
                            removable = current.groups.size > 1,
                            onChange = { changed ->
                                conditions = FilterConditions.Rows(current.groups.mapIndexed { index, old -> if (index == groupIndex) changed else old })
                            },
                            onRemove = { conditions = FilterConditions.Rows(current.groups.filterIndexed { index, _ -> index != groupIndex }) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { conditions = FilterConditions.Rows(current.groups + ConditionGroup(GroupMatch.ANY, listOf(newRow(FilterField.SUBJECT)))) }) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = paper.accent, modifier = Modifier.size(18.dp))
                            Text("Add group", color = paper.accent, modifier = Modifier.padding(start = 4.dp))
                        }
                        TextButton(onClick = { conditions = FilterConditions.Raw(query) }) {
                            Text("Edit as text", color = paper.textSecondary)
                        }
                    }
                }
            }
            FilterHeading("Gmail search")
            Text(
                query.ifEmpty { "Add a condition" },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (query.isEmpty()) paper.faint else paper.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(paper.muted, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            FilterHeading("Then")
            ActionsCard(actions, labels, filters.forwarding) { actions = it }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
            ) {
                Text(
                    when {
                        query.isBlank() -> "Matches nothing yet"
                        preview == null -> "Counting matches…"
                        preview == "1" -> "Matches 1 conversation"
                        else -> "Matches $preview conversations"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = paper.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (query.isNotBlank()) TextButton(onClick = { onShow(query) }) { Text("Show", color = paper.accent) }
            }
            SwitchRow(applyExistingText(preview), applyExisting, { applyExisting = it }, padding = PaddingValues(horizontal = 4.dp))
        }
    }
}

// The "apply to existing" switch's title, with the preview's count ("12", or "100+" past a page) once known.
private fun applyExistingText(preview: String?): String {
    val count = preview?.removeSuffix("+")?.toIntOrNull() ?: 0
    return if (count > 0) {
        val about = if (preview?.endsWith("+") == true) "about " else ""
        "Also apply to the $about$count existing conversation${if (count == 1) "" else "s"}"
    } else {
        "Also apply to existing conversations"
    }
}

@Composable
private fun FilterHeading(text: String) {
    SectionHeading(text, Modifier.padding(start = 4.dp, top = 12.dp))
}

@Composable
private fun GroupCard(group: ConditionGroup, removable: Boolean, onChange: (ConditionGroup) -> Unit, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(paper.card)
            .border(1.dp, paper.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picker(MATCH_NAMES, group.match, Modifier.weight(1f)) { onChange(group.copy(match = it)) }
            if (removable) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remove group", tint = paper.textSecondary) }
            }
        }
        group.rows.forEachIndexed { rowIndex, row ->
            ConditionRow(
                row = row,
                onChange = { changed -> onChange(group.copy(rows = group.rows.mapIndexed { index, old -> if (index == rowIndex) changed else old })) },
                onRemove = if (group.rows.size > 1) ({ onChange(group.copy(rows = group.rows.filterIndexed { index, _ -> index != rowIndex })) }) else null,
            )
        }
        TextButton(onClick = { onChange(group.copy(rows = group.rows + newRow(group.rows.lastOrNull()?.field ?: FilterField.FROM))) }) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = paper.accent, modifier = Modifier.size(18.dp))
            Text("Add condition", color = paper.accent, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ConditionRow(row: FilterRow, onChange: (FilterRow) -> Unit, onRemove: (() -> Unit)?) {
    val error = rowError(row)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(FIELD_NAMES, row.field, Modifier.width(132.dp)) { field -> onChange(newRow(field).copy(value = if (field == FilterField.HAS_ATTACHMENT) "" else row.value)) }
            when (row.field) {
                FilterField.HAS_ATTACHMENT -> Text("yes", color = paper.textSecondary, modifier = Modifier.weight(1f))
                FilterField.SIZE -> {
                    Picker(linkedMapOf(FilterOperator.LARGER to "larger than", FilterOperator.SMALLER to "smaller than"), row.operator, Modifier.width(120.dp)) {
                        onChange(row.copy(operator = it))
                    }
                    Box(modifier = Modifier.weight(1f)) { TextBox(row.value, "5MB") { onChange(row.copy(value = it)) } }
                }
                else -> Box(modifier = Modifier.weight(1f)) { TextBox(row.value, "contains") { onChange(row.copy(value = it)) } }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove condition", tint = paper.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (error != null && row.value.isNotEmpty()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = paper.snooze, modifier = Modifier.padding(start = 140.dp, top = 2.dp))
        }
    }
}

@Composable
private fun TextBox(value: String, placeholder: String, monospace: Boolean = false, onChange: (String) -> Unit) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .fieldFrame(shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = paper.faint, style = MaterialTheme.typography.bodyMedium)
        val style = MaterialTheme.typography.bodyMedium.copy(color = paper.text)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = if (monospace) style.copy(fontFamily = FontFamily.Monospace) else style,
            cursorBrush = SolidColor(paper.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

@Composable
private fun <Option> Picker(options: Map<Option, String>, selected: Option, modifier: Modifier = Modifier, onPick: (Option) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(paper.muted)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
        ) {
            Text(options[selected] ?: selected.toString(), style = MaterialTheme.typography.bodyMedium, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = paper.raised) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(label, color = paper.text) }, onClick = {
                    open = false
                    onPick(value)
                })
            }
        }
    }
}

@Composable
private fun ActionsCard(actions: FilterActions, labels: List<GmailLabel>, forwarding: List<String>, onChange: (FilterActions) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(paper.card)
            .border(1.dp, paper.border, RoundedCornerShape(12.dp)),
    ) {
        val labelOptions = linkedMapOf("" to "No label") + labels.associate { it.id to it.name }
        PickerRow("Label", labelOptions, actions.labelId ?: "") { onChange(actions.copy(labelId = it.ifEmpty { null })) }
        ToggleRow("Skip inbox", actions.archive) { onChange(actions.copy(archive = it)) }
        ToggleRow("Mark as read", actions.markRead) { onChange(actions.copy(markRead = it)) }
        ToggleRow("Pin", actions.star) { onChange(actions.copy(star = it)) }
        ToggleRow("Never send to spam", actions.neverSpam) { onChange(actions.copy(neverSpam = it)) }
        PickerRow(
            "Importance",
            linkedMapOf(null to "Leave to Gmail", Importance.ALWAYS to "Always important", Importance.NEVER to "Never important"),
            actions.important,
        ) { onChange(actions.copy(important = it)) }
        ToggleRow("Delete", actions.trash) { onChange(actions.copy(trash = it)) }
        if (forwarding.isNotEmpty() || actions.forward != null) {
            val options = linkedMapOf("" to "Don't forward") + (forwarding + listOfNotNull(actions.forward)).distinct().associateWith { it }
            PickerRow("Forward to", options, actions.forward ?: "") { onChange(actions.copy(forward = it.ifEmpty { null })) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(title, checked, onChange, minHeight = 52.dp, padding = PaddingValues(horizontal = 16.dp))
    HorizontalDivider(color = paper.separator)
}

@Composable
private fun <Option> PickerRow(title: String, options: Map<Option, String>, selected: Option, onPick: (Option) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
    ) {
        Text(title, color = paper.text)
        Picker(options, selected, Modifier.weight(1f), onPick)
    }
    HorizontalDivider(color = paper.separator)
}
