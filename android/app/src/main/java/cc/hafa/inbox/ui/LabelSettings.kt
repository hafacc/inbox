package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.label.LABEL_SWATCHES
import cc.hafa.inbox.core.label.LabelIcons
import cc.hafa.inbox.core.label.LabelPrefs
import cc.hafa.inbox.core.label.NO_ICON
import cc.hafa.inbox.core.label.labelNameError
import cc.hafa.inbox.core.label.leafName
import cc.hafa.inbox.core.label.nestedName
import cc.hafa.inbox.core.label.parentName
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.theme.labelTone
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import cc.hafa.inbox.ui.theme.rowHighlight

private val DEFAULT_PREFS = LabelPrefs(sidebar = true, bundle = true, icon = null)

/** A label's mark: its chosen icon, or a dot, in a soft circle of its color. */
@Composable
fun LabelMark(label: GmailLabel?, icon: String?, size: Dp, plain: Boolean = false) {
    val color = labelColor(label)
    val vector = icon?.let { LABEL_ICON_VECTORS[it] }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(if (plain) Modifier else Modifier.background(color.copy(alpha = 0.18f).compositeOver(paper.card), CircleShape)),
    ) {
        if (vector != null) {
            Icon(vector, contentDescription = null, tint = color, modifier = Modifier.size(if (plain) size else size * 0.5f))
        } else {
            Box(Modifier.size(if (plain) size * 0.7f else size * 0.28f).background(color, CircleShape))
        }
    }
}

/**
 * One label's settings in a sheet: its name, color and icon (user labels only; Gmail's categories
 * keep theirs), shown in the menu, and bundled in the inbox.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabelSettingsSheet(state: MailState, label: GmailLabel, prefs: LabelPrefs?, onDismiss: () -> Unit) {
    val current = prefs ?: DEFAULT_PREFS
    val editable = !LabelIcons.isCategory(label.id)
    var draftName by remember(label.name) { mutableStateOf(leafName(label.name)) }
    var error by remember(label.name) { mutableStateOf<String?>(null) }
    fun rename() {
        val full = nestedName(parentName(label.name), draftName)
        if (full == label.name) return
        error = labelNameError(state.model.value.labels, full, label.id)
        if (error == null) state.renameLabel(label.id, full)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelMark(label, current.icon, 36.dp)
                Text(label.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = paper.text)
            }
            if (editable) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .fieldFrame(shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                ) {
                    BasicTextField(
                        value = draftName,
                        onValueChange = {
                            draftName = it
                            error = null
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
                        cursorBrush = SolidColor(paper.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { rename() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) rename() }
                            .semantics { contentDescription = "Label name" },
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = paper.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                val chosenTone = labelTone(label.color?.backgroundColor)
                FlowRow(modifier = Modifier.padding(top = 8.dp)) {
                    for ((tone, swatch) in LABEL_SWATCHES) {
                        val chosen = chosenTone == tone
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .selectable(selected = chosen, role = Role.RadioButton) { state.colorLabel(label.id, swatch) }
                                .semantics { contentDescription = tone.replaceFirstChar(Char::uppercase) },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(30.dp).background(paper.labelTones.getValue(tone), CircleShape),
                            ) {
                                if (chosen) Icon(Icons.Outlined.Check, contentDescription = null, tint = paper.card, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(top = 12.dp)) {
                SwitchRow("Show in menu", current.sidebar, { state.setLabelPrefs(label.id, mapOf("sidebar" to it)) })
                SwitchRow(
                    "Bundle in inbox",
                    current.bundle,
                    { state.setLabelPrefs(label.id, mapOf("bundle" to it)) },
                    detail = "Three or more threads fold into one row, listed with the newest.",
                )
            }
            if (editable) IconPicker(current.icon) { state.setLabelPrefs(label.id, mapOf("icon" to it)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(chosen: String?, onPick: (String) -> Unit) {
    SectionHeading("Icon", Modifier.padding(top = 16.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconChoice("No icon", chosen == null, { onPick(NO_ICON) }) {
            Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        for (choice in LabelIcons.icons) {
            val vector = LABEL_ICON_VECTORS[choice.id] ?: continue
            IconChoice(choice.name, chosen == choice.id, { onPick(choice.id) }) {
                Icon(vector, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun IconChoice(name: String, selected: Boolean, onPick: () -> Unit, icon: @Composable () -> Unit) {
    val tint = if (selected) paper.onAccent else paper.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) paper.accent else paper.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = name },
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) { icon() }
    }
}

/** Settings › Labels: every label with its menu and bundle switches, and a tap for its icon. */
@Composable
fun LabelsSettingsScreen(state: MailState, model: MailModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    ScreenBackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().background(paper.page)) {
        PlainTopBar("Labels", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                "Choose which labels the menu lists, which fold into bundles in the inbox, and each one's icon.",
                style = MaterialTheme.typography.bodyMedium,
                color = paper.textSecondary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
            )
            if (model.listLabels.isEmpty()) {
                Text("No labels yet.", color = paper.faint, modifier = Modifier.padding(16.dp))
            }
            SettingsCard {
                model.listLabels.forEachIndexed { index, label ->
                    if (index > 0) HorizontalDivider(color = paper.separator, modifier = Modifier.padding(start = 64.dp))
                    val prefs = model.labelPrefs[label.id] ?: DEFAULT_PREFS
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable(onClickLabel = "Change icon") { onOpen(label.id) }
                            .padding(start = 16.dp, end = 4.dp),
                    ) {
                        LabelMark(label, prefs.icon, 36.dp)
                        Text(
                            label.name,
                            color = paper.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        CompactSwitch("Menu", "Show ${label.name} in the menu", prefs.sidebar) {
                            state.setLabelPrefs(label.id, mapOf("sidebar" to it))
                        }
                        CompactSwitch("Bundle", "Bundle ${label.name} in the inbox", prefs.bundle) {
                            state.setLabelPrefs(label.id, mapOf("bundle" to it))
                        }
                        IconButton(onClick = { onOpen(label.id) }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "${label.name} settings", tint = paper.textSecondary)
                        }
                    }
                }
            }
            UnlabeledTagsCard(state, model)
        }
    }
}

// The tags of the todos a label could bundle (of their own, not done) that name no label yet, each
// with a button that makes its label; nothing while todos are hidden, before the labels have loaded,
// or when every tag names one.
@Composable
private fun UnlabeledTagsCard(state: MailState, model: MailModel) {
    val tags = remember(model.todosOn, model.todos, model.labels, model.labelsLoaded) {
        if (model.todosOn && model.labelsLoaded) {
            LabelTags.unlabeledTags(model.todos.filter { it.state != TodoState.DONE }.map { it.text }, model.labels)
        } else {
            emptyList()
        }
    }
    if (tags.isEmpty()) return
    SectionHeading("Tags without a label", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp))
    Text(
        "Make a label from a tag to bundle its todos with its mail.",
        style = MaterialTheme.typography.bodyMedium,
        color = paper.textSecondary,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
    )
    SettingsCard {
        tags.forEachIndexed { index, tag ->
            if (index > 0) HorizontalDivider(color = paper.separator, modifier = Modifier.padding(start = 16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
            ) {
                Text(
                    "#${tag.tag}",
                    color = paper.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.createLabel(tag.label, null) }) {
                    Text("Make label", color = paper.accentStrong)
                }
            }
        }
    }
}

// A label row's switch with its caption presses and highlights in a rounded shape, as the theme choices do.
private val COMPACT_SWITCH_SHAPE = RoundedCornerShape(14.dp)

@Composable
private fun CompactSwitch(caption: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(COMPACT_SWITCH_SHAPE)
            .rowHighlight(COMPACT_SWITCH_SHAPE)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = paper.accent, checkedThumbColor = paper.onAccent),
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = paper.textSecondary)
    }
}

/** A settings card: rounded, raised a little off the page, its rows inside. */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(paper.card),
    ) { content() }
}
