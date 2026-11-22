package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.search.Candidate
import cc.hafa.inbox.core.search.Chip
import cc.hafa.inbox.core.search.ChipSpot
import cc.hafa.inbox.core.search.CompletionContext
import cc.hafa.inbox.core.search.CompletionOptions
import cc.hafa.inbox.core.search.OPERATORS
import cc.hafa.inbox.core.search.OPERATOR_DETAILS
import cc.hafa.inbox.core.search.OPERATOR_EXAMPLES
import cc.hafa.inbox.core.search.OWN_TERMS
import cc.hafa.inbox.core.search.TAG_CHIP
import cc.hafa.inbox.core.search.addRecentSearch
import cc.hafa.inbox.core.search.applyCompletion
import cc.hafa.inbox.core.search.awaitsValue
import cc.hafa.inbox.core.search.completions
import cc.hafa.inbox.core.search.formatChip
import cc.hafa.inbox.core.search.queryText
import cc.hafa.inbox.core.search.removeRecentSearch
import cc.hafa.inbox.core.search.typeFree
import cc.hafa.inbox.core.search.unchip
import cc.hafa.inbox.core.search.withoutOperator
import cc.hafa.inbox.core.todo.Tags
import cc.hafa.inbox.data.RecentSearchStore
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.delay
import java.time.ZoneId

// How long typing must pause before completions open.
private const val SUGGEST_DELAY_MS = 300L

// The soft tint behind a `#tag` chip, as todos' tags have it.
private const val TAG_SEARCH_CHIP_ALPHA = 0.12f

// Kept before the free text while chips show, so a soft keyboard's backspace at the start has
// something to delete (and the field can tell it happened).
private const val SENTINEL = "​"

/**
 * What the search field holds: finished chips, the free text, what the cursor completes, and
 * where the last chip formed (so it turns back into text when the next word is a top-level OR).
 */
private data class SearchInput(
    val chips: List<Chip>,
    val textValue: TextFieldValue,
    val context: CompletionContext?,
    val spot: ChipSpot? = null,
) {
    val free: String get() = textValue.text.removePrefix(SENTINEL)

    /** where the cursor sits in [free] */
    val cursor: Int get() = (textValue.selection.start - (textValue.text.length - free.length)).coerceIn(0, free.length)

    companion object {
        fun of(chips: List<Chip>, free: String, cursor: Int, context: CompletionContext?, spot: ChipSpot? = null): SearchInput {
            val prefix = if (chips.isEmpty()) "" else SENTINEL
            return SearchInput(chips, TextFieldValue(prefix + free, TextRange(prefix.length + cursor)), context, spot)
        }

        /** [free] parsed after [chips]; with [tags], finished `#tags` turn into chips too. */
        fun parse(chips: List<Chip>, free: String, cursor: Int?, tags: Boolean, spot: ChipSpot? = null): SearchInput {
            val typed = typeFree(chips, spot, free, cursor, tags)
            val parsed = typed.parsed
            return of(typed.chips, parsed.free, parsed.cursor, parsed.context, typed.spot)
        }
    }
}

/**
 * The search field: operators turn into chips as they finish (and, while todos show, `#tags`, a tag
 * naming a label in its color), a row of completions opens under it once typing pauses (tapping one
 * completes, as Tab does on the web; a `#tag` being typed completes from [tagCounts] and the labels
 * as tags while todos show), and backspace at the start turns the last chip back into text. The search action on an
 * operator with no value yet (`in:`) completes its first suggestion instead of searching. Opened empty,
 * it lists this device's recent searches. [onSearch] gets the whole query.
 */
@Composable
fun SearchBar(
    initial: String,
    labels: List<String>,
    contacts: List<Person>,
    now: Long,
    onSearch: (String) -> Unit,
    onClose: () -> Unit,
    tagCounts: Map<String, Tags.Known>? = null,
) {
    val tagChips = tagCounts != null
    val labelTags = LocalLabelTags.current
    val tagColors = tagColors()
    // Kept as chips, free text and cursor, so a turn of the phone keeps what was typed.
    val inputSaver = remember(tagChips) {
        listSaver<SearchInput, Any>(
            save = { listOf(queryText(it.chips, ""), it.free, it.cursor) },
            restore = { (chipText, free, cursor) ->
                SearchInput.parse(SearchInput.parse(emptyList(), chipText as String, null, tagChips).chips, free as String, cursor as Int, tagChips)
            },
        )
    }
    var input by rememberSaveable(initial, stateSaver = inputSaver) { mutableStateOf(SearchInput.parse(emptyList(), initial, null, tagChips)) }
    var focused by remember { mutableStateOf(false) }
    // Completions open once typing pauses for SUGGEST_DELAY_MS and stay open, updating, until focus leaves.
    var suggesting by remember { mutableStateOf(false) }
    var edits by remember { mutableIntStateOf(0) }
    LaunchedEffect(edits) {
        if (edits > 0 && !suggesting) {
            delay(SUGGEST_DELAY_MS)
            suggesting = true
        }
    }
    var helping by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var recent by remember { mutableStateOf(RecentSearchStore.load(context)) }

    fun keepRecent(next: List<String>) {
        recent = next
        RecentSearchStore.save(context, next)
    }

    fun run(query: String) {
        keepRecent(addRecentSearch(recent, query))
        focusManager.clearFocus()
        onSearch(query)
    }
    LaunchedEffect(initial) { if (initial.isEmpty()) focus.requestFocus() }
    LayerBackHandler(onBack = onClose)

    fun change(value: TextFieldValue) {
        val current = input
        // Selecting text (select all, handles, a double tap) changes only the selection: keep it as it is.
        if (value.text == current.textValue.text) {
            input = current.copy(textValue = value)
            return
        }
        edits += 1
        input = if (current.chips.isNotEmpty() && !value.text.startsWith(SENTINEL)) {
            val rest = value.text.removePrefix(SENTINEL)
            val back = unchip(current.chips, current.chips.lastIndex, rest)
            SearchInput.parse(back.chips, back.free, back.cursor, tagChips)
        } else {
            val offset = if (current.chips.isEmpty()) 0 else SENTINEL.length
            val free = value.text.removePrefix(SENTINEL)
            val cursor = (value.selection.start - offset).coerceIn(0, free.length)
            SearchInput.parse(current.chips, free, cursor, tagChips, current.spot)
        }
    }

    val cursor = input.cursor
    val typed = tagCounts?.let { Tags.typing(input.free, cursor) }

    fun complete(candidate: Candidate) {
        if (typed != null) {
            val (text, caret) = Tags.complete(input.free, typed, cursor, candidate.value)
            input = SearchInput.parse(input.chips, text, caret, tagChips, input.spot)
            return
        }
        val context = input.context ?: return
        val edit = applyCompletion(input.free, context, candidate)
        input = SearchInput.parse(input.chips, edit.text, edit.cursor, tagChips, input.spot)
    }


    fun submit(free: String = input.free) {
        val query = queryText(input.chips, free)
        if (query.isNotEmpty()) run(query) else onClose()
    }

    val options = remember(now, labels, contacts) { CompletionOptions(now, ZoneId.systemDefault().id, labels, contacts) }
    val candidates = remember(input.context, options, typed, tagCounts, labelTags) {
        if (typed != null) {
            Tags.suggest(tagCounts, "", typed.prefix, offered = labelTags.names).map { Candidate("#$it", it, null) }
        } else {
            input.context?.let { completions(it, options) }.orEmpty()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val frameTaps = remember { MutableInteractionSource() }

    // A tap on the frame outside the text (past its end, beside or on a chip) puts the cursor at the end.
    fun focusAtEnd() {
        edits += 1
        input = SearchInput.of(input.chips, input.free, input.free.length, input.context, input.spot)
        focus.requestFocus()
        keyboard?.show()
    }

    fun removeChip(index: Int) {
        val chips = input.chips.filterIndexed { other, _ -> other != index }
        edits += 1
        input = SearchInput.of(chips, input.free, input.free.length, input.context, input.spot)
        focus.requestFocus()
        keyboard?.show()
    }

    // The cross empties the field and keeps search open, ready to type again.
    fun clear() {
        input = SearchInput.of(emptyList(), "", 0, null)
        focus.requestFocus()
        keyboard?.show()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close search", tint = paper.text)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .fieldFrame(shape = RoundedCornerShape(20.dp))
                    .clickable(interactionSource = frameTaps, indication = null, onClickLabel = "Search mail") { focusAtEnd() }
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                input.chips.forEachIndexed { index, chip -> SearchChip(chip, tagColors) { removeChip(index) } }
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.widthIn(min = 120.dp)) {
                    if (input.free.isEmpty() && input.chips.isEmpty()) {
                        Text("Search mail", color = paper.textSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = input.textValue,
                        onValueChange = ::change,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
                        cursorBrush = SolidColor(paper.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                // An operator with no value yet isn't a query to search: finish it with the first
                                // suggestion, else search without it.
                                val context = input.context
                                if (typed == null && context != null && awaitsValue(context)) {
                                    candidates.firstOrNull()?.let(::complete) ?: submit(withoutOperator(input.free, context))
                                } else {
                                    submit()
                                }
                            },
                        ),
                        modifier = Modifier
                            .widthIn(min = 120.dp)
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                focused = state.isFocused
                                if (!state.isFocused) suggesting = false
                            }
                            .onPreviewKeyEvent { event ->
                                val atStart = input.textValue.selection.collapsed && input.textValue.selection.start <= SENTINEL.length
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && atStart && input.chips.isNotEmpty()) {
                                    val back = unchip(input.chips, input.chips.lastIndex, input.free)
                                    edits += 1
                                    input = SearchInput.parse(back.chips, back.free, back.cursor, tagChips)
                                    true
                                } else {
                                    false
                                }
                            }
                            .semantics { contentDescription = "Search mail" },
                    )
                }
            }
            if (input.chips.isNotEmpty() || input.free.isNotEmpty()) {
                IconButton(onClick = ::clear) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = paper.textSecondary)
                }
            } else {
                IconButton(onClick = { helping = true }) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "All search operators", tint = paper.textSecondary)
                }
            }
        }
        if (focused && suggesting && candidates.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                items(candidates, key = { "${it.label}|${it.value}" }) { candidate ->
                    Suggestion(candidate, if (typed != null) tagColors.of(candidate.value) else null) { complete(candidate) }
                }
                if (input.free.isEmpty()) {
                    item(key = "help") { Suggestion(Candidate("All search operators", "", null)) { helping = true } }
                }
            }
        }
        if (focused && candidates.isEmpty() && input.chips.isEmpty() && input.free.isEmpty() && recent.isNotEmpty()) {
            RecentSearches(recent, onRun = ::run, onRemove = { keepRecent(removeRecentSearch(recent, it)) })
        }
    }
    if (helping) SearchHelpSheet(onDismiss = { helping = false })
}

/** The recent searches under an empty field: a tap runs one, its cross forgets it. */
@Composable
private fun RecentSearches(recent: List<String>, onRun: (String) -> Unit, onRemove: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
        Text(
            "Recent searches",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = paper.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        for (query in recent) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Search") { onRun(query) }
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
                    Text(query, style = MaterialTheme.typography.bodyLarge, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(query) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove $query from recent searches", tint = paper.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Every search operator with what it matches and an example, from the list both apps share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Text(
            "Search operators",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = paper.text,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            "Type one in the search box; put - in front to leave matches out.",
            style = MaterialTheme.typography.bodyMedium,
            color = paper.textSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(OPERATORS, key = { it }) { operator ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$operator:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = paper.text)
                        Text(OPERATOR_DETAILS[operator].orEmpty(), style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                    }
                    Text(
                        OPERATOR_EXAMPLES[operator].orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = paper.accent,
                    )
                }
            }
            item(key = "own") {
                Text(
                    "Ours, not Gmail's",
                    style = MaterialTheme.typography.titleSmall,
                    color = paper.text,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(OWN_TERMS, key = { "own:${it.first}" }) { (term, detail) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text(term, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = paper.accent)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                }
            }
        }
    }
}

/**
 * A finished operator in the field: a tap on it puts the cursor at the end of the field (the
 * frame's tap), and only its cross removes it.
 */
@Composable
private fun SearchChip(chip: Chip, tagColors: TagColors, onRemove: () -> Unit) {
    if (chip.operator == TAG_CHIP) {
        val color = tagColors.of(chip.value)
        ChipFrame(chip, color, Modifier.background(color.copy(alpha = color.alpha * TAG_SEARCH_CHIP_ALPHA), RoundedCornerShape(14.dp)), onRemove) {
            Text(formatChip(chip), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
    } else {
        val frame = Modifier.background(paper.card, RoundedCornerShape(14.dp)).border(1.dp, paper.border, RoundedCornerShape(14.dp))
        ChipFrame(chip, paper.faint, frame, onRemove) {
            Text(
                (if (chip.negated) "-" else "") + chip.operator + ":",
                style = MaterialTheme.typography.labelMedium,
                color = paper.textSecondary,
            )
            Text(chip.value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = paper.text, maxLines = 1)
        }
    }
}

/** A search chip's pill drawn by [modifier]: [content], then its cross in [crossTint], which takes a 48 dp tap. */
@Composable
private fun ChipFrame(chip: Chip, crossTint: Color, modifier: Modifier, onRemove: () -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.height(28.dp).then(modifier).padding(start = 10.dp, end = 2.dp),
    ) {
        content()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .tapArea(CHIP_CROSS)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = CHIP_CROSS / 2),
                    role = Role.Button,
                    onClick = onRemove,
                )
                .semantics { contentDescription = "Remove ${formatChip(chip)}" },
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = crossTint, modifier = Modifier.size(14.dp))
        }
    }
}

// The drawn size of a search chip's cross.
private val CHIP_CROSS = 24.dp

/** A completion; [tint] colors a `#tag` as a todo's tag is, null for any other. */
@Composable
private fun Suggestion(candidate: Candidate, tint: Color? = null, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .background(paper.card, RoundedCornerShape(12.dp))
            .border(1.dp, paper.border, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Add to search", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(candidate.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint ?: paper.text, maxLines = 1)
        candidate.detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = paper.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
