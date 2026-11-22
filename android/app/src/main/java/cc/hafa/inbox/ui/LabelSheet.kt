package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.gmail.GmailLabelColor
import cc.hafa.inbox.core.label.CATEGORY_CHOICE_ICONS
import cc.hafa.inbox.core.label.CATEGORY_CHOICE_TONES
import cc.hafa.inbox.core.label.CATEGORY_IDS
import cc.hafa.inbox.core.label.LABEL_SWATCHES
import cc.hafa.inbox.core.label.labelNameError
import cc.hafa.inbox.core.label.leafName
import cc.hafa.inbox.core.label.nestedName
import cc.hafa.inbox.core.label.parentName
import cc.hafa.inbox.core.mail.CATEGORY_NAMES
import cc.hafa.inbox.core.mail.LabelState
import cc.hafa.inbox.core.mail.SelectedThread
import cc.hafa.inbox.core.mail.SetAction
import cc.hafa.inbox.core.mail.labelChoice
import cc.hafa.inbox.core.mail.selectionCategory
import cc.hafa.inbox.core.theme.labelTone
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import cc.hafa.inbox.ui.theme.rowHighlight

/** What the label sheet shows: the list, or the editor for a new label or an existing one. */
private sealed interface LabelStep {
    data object List : LabelStep

    class Edit(val label: GmailLabel?) : LabelStep
}

/**
 * The label sheet for threads: apply or remove labels (one only some of them carry shows a dash and
 * adds to the rest), create, rename, color and nest them, or move the threads to another of Gmail's
 * categories. [onAction] hears each change applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    state: MailState,
    threads: List<SelectedThread>,
    labels: List<GmailLabel>,
    onDismiss: () -> Unit,
    onAction: (SetAction) -> Unit = {},
) {
    val threadIds = threads.map { it.id }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var step by remember { mutableStateOf<LabelStep>(LabelStep.List) }
    var managing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val shown = remember(labels, filter) {
        val query = filter.trim()
        if (query.isEmpty()) labels else labels.filter { it.name.contains(query, ignoreCase = true) }
    }
    val close = rememberSheetCloser(sheetState, onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        AnimatedContent(targetState = step, transitionSpec = { sheetSlide(targetState is LabelStep.Edit) }, label = "labelStep") { current ->
            Column(modifier = Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp)) {
                when (current) {
                    LabelStep.List -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)) {
                            Text("Label as", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = paper.text, modifier = Modifier.weight(1f))
                            TextButton(onClick = { managing = !managing }) {
                                Text(if (managing) "Done" else "Manage labels", color = paper.accent)
                            }
                        }
                        if (labels.size >= FILTER_MIN_LABELS) LabelFilter(filter) { filter = it }
                        Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            val filtering = filter.isNotBlank()
                            if (filtering && shown.isEmpty()) {
                                Text(
                                    "No labels match",
                                    color = paper.textSecondary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                            for (label in shown) {
                                val choice = labelChoice(threads, label.id)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .then(
                                            if (managing) {
                                                Modifier.clickable(role = Role.Button, onClickLabel = "Edit") { step = LabelStep.Edit(label) }
                                            } else {
                                                Modifier.triStateToggleable(state = toggleableState(choice.state), role = Role.Checkbox) {
                                                    state.changeLabels(choice.remove, emptyList(), listOf(label.id))
                                                    state.changeLabels(choice.add, listOf(label.id), emptyList())
                                                    onAction(SetAction.LABEL)
                                                }
                                            },
                                        )
                                        .padding(horizontal = 24.dp),
                                ) {
                                    // While filtering, matches show their whole path, unindented.
                                    val depth = if (filtering) 0 else label.name.count { it == '/' }
                                    Box(modifier = Modifier.padding(start = (depth * 16).dp).size(12.dp).background(labelColor(label), CircleShape))
                                    Text(
                                        if (filtering) label.name else label.name.substringAfterLast('/'),
                                        color = paper.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    when {
                                        managing -> Icon(Icons.Outlined.Edit, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
                                        choice.state == LabelState.ALL -> Icon(Icons.Outlined.Check, contentDescription = null, tint = paper.accent, modifier = Modifier.size(20.dp))
                                        choice.state == LabelState.SOME -> Icon(Icons.Outlined.Remove, contentDescription = null, tint = paper.accent, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                            if (!managing && !filtering) {
                                CategoryChoice(
                                    current = selectionCategory(threads),
                                    onChoose = { category ->
                                        if (!close.closing) {
                                            state.changeCategory(threadIds, category)
                                            onAction(SetAction.CATEGORY)
                                            close()
                                        }
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = paper.separator, modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).clickable { step = LabelStep.Edit(null) }.padding(horizontal = 24.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
                            Text("New label…", color = paper.text)
                        }
                    }
                    is LabelStep.Edit -> LabelEditor(
                        label = current.label,
                        labels = labels,
                        allLabels = { state.model.value.labels },
                        onCancel = { step = LabelStep.List },
                        onSave = { fullName, color ->
                            val existing = current.label
                            if (existing == null) {
                                val created = state.createLabel(fullName, color)
                                state.changeLabels(threadIds, listOf(created.id), emptyList())
                                onAction(SetAction.LABEL)
                            } else {
                                state.renameLabel(existing.id, fullName)
                                if (color != null && color != existing.color) state.colorLabel(existing.id, color)
                            }
                            step = LabelStep.List
                        },
                    )
                }
            }
        }
    }
}

/** The search box over a long label list. */
@Composable
private fun LabelFilter(text: String, onChange: (String) -> Unit) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(48.dp)
            .fieldFrame(shape = CircleShape)
            .padding(horizontal = 20.dp),
    ) {
        if (text.isEmpty()) Text("Filter labels", color = paper.faint)
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
            cursorBrush = SolidColor(paper.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter labels" },
        )
    }
}

// A list this long gets the filter box.
private const val FILTER_MIN_LABELS = 8

/** The sheet's Category group: Gmail's categories, Primary first, the threads' own checked when they share one; one at a time. */
@Composable
private fun CategoryChoice(current: String?, onChoose: (String) -> Unit) {
    HorizontalDivider(color = paper.separator, modifier = Modifier.padding(vertical = 4.dp))
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        color = paper.textSecondary,
        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
    )
    Column(modifier = Modifier.selectableGroup()) {
        for (category in CATEGORY_IDS) {
            val selected = category == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onChoose(category) }
                    .padding(horizontal = 24.dp),
            ) {
                val vector = CATEGORY_CHOICE_ICONS[category]?.let { LABEL_ICON_VECTORS[it] }
                val tint = paper.labelTones.getValue(CATEGORY_CHOICE_TONES[category] ?: "gray")
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(12.dp)) {
                    if (vector != null) Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.requiredSize(20.dp))
                }
                Text(CATEGORY_NAMES[category] ?: "Primary", color = paper.text, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = paper.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun toggleableState(state: LabelState): ToggleableState = when (state) {
    LabelState.ALL -> ToggleableState.On
    LabelState.SOME -> ToggleableState.Indeterminate
    LabelState.NONE -> ToggleableState.Off
}

@Composable
private fun LabelEditor(
    label: GmailLabel?,
    labels: List<GmailLabel>,
    allLabels: () -> List<GmailLabel>,
    onCancel: () -> Unit,
    onSave: (fullName: String, color: GmailLabelColor?) -> Unit,
) {
    val initialName = label?.name ?: ""
    var name by remember(label) { mutableStateOf(leafName(initialName)) }
    var parent by remember(label) { mutableStateOf(parentName(initialName)) }
    var error by remember(label) { mutableStateOf<String?>(null) }
    var color by remember(label) { mutableStateOf(label?.color?.takeIf { it.backgroundColor != null }) }
    var parentMenu by remember { mutableStateOf(false) }
    val parents = labels.map { it.name }.filter { label == null || (it != initialName && !it.lowercase().startsWith("${initialName.lowercase()}/")) }
    // Set once saved or canceled: the editor stays on screen while it slides away, and mustn't save twice.
    var finished by remember(label) { mutableStateOf(false) }
    fun problem(): String? = nestedName(parent, name).let { if (label != null && it == initialName) null else labelNameError(allLabels(), it, label?.id) }
    val save = {
        val fullName = nestedName(parent, name)
        error = problem()
        if (!finished && error == null) {
            finished = true
            onSave(fullName, color)
        }
    }
    val cancel = {
        if (!finished) {
            finished = true
            onCancel()
        }
    }
    // Back keeps a usable change to an existing label, as Save does; a new label needs Create, so Back drops it, as Cancel does.
    LayerBackHandler(open = !finished) {
        val changed = name != leafName(initialName) ||
            parent != parentName(initialName) ||
            color != label?.color?.takeIf { it.backgroundColor != null }
        if (label != null && changed && problem() == null) save() else cancel()
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (label == null) "New label" else "Edit label", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = paper.text)
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .fieldFrame(shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
        ) {
            if (name.isEmpty()) Text("Name", color = paper.faint)
            BasicTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
                cursorBrush = SolidColor(paper.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Label name" },
            )
        }
        error?.let { Text(it, color = paper.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, paper.border, RoundedCornerShape(8.dp))
                    .clickable { parentMenu = true }
                    .padding(horizontal = 12.dp),
            ) {
                Text("Nest under", color = paper.textSecondary, modifier = Modifier.weight(1f))
                Text(parent ?: "Nothing", color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }, containerColor = paper.raised) {
                DropdownMenuItem(text = { Text("Nothing", color = paper.text) }, onClick = {
                    parent = null
                    error = null
                    parentMenu = false
                })
                for (option in parents) {
                    DropdownMenuItem(text = { Text(option, color = paper.text) }, onClick = {
                        parent = option
                        error = null
                        parentMenu = false
                    })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val chosenTone = color?.backgroundColor?.let(::labelTone)
            for ((tone, option) in LABEL_SWATCHES) {
                val chosen = chosenTone == tone
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .rowHighlight(CircleShape)
                        .selectable(selected = chosen, role = Role.RadioButton) { color = option }
                        .semantics { contentDescription = tone.replaceFirstChar(Char::uppercase) },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(32.dp).background(paper.labelTones.getValue(tone), CircleShape),
                    ) {
                        if (chosen) Icon(Icons.Outlined.Check, contentDescription = null, tint = paper.card, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = cancel) { Text("Cancel", color = paper.textSecondary) }
            TextButton(onClick = save, enabled = name.isNotBlank()) {
                Text(if (label == null) "Create" else "Save", color = paper.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
