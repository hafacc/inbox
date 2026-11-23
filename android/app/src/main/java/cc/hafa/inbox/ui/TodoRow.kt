package cc.hafa.inbox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.mail.SwipeSide
import cc.hafa.inbox.core.mail.TodoSwipeAction
import cc.hafa.inbox.core.mail.doneTodoDate
import cc.hafa.inbox.core.mail.findWebLinks
import cc.hafa.inbox.core.mail.formatListDate
import cc.hafa.inbox.core.mail.formatWakeTime
import cc.hafa.inbox.core.mail.isoToEpoch
import cc.hafa.inbox.core.mail.lapsedMarker
import cc.hafa.inbox.core.mail.todoSwipeAction
import cc.hafa.inbox.core.menu.HOLD_MS
import cc.hafa.inbox.core.menu.HOLD_TAP
import cc.hafa.inbox.core.menu.rowHoldWedges
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.Tags
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.isSnoozed
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.launch

/** What a todo row and the Todos view can ask for. */
class TodoActions(
    /** mark a todo done, or a done one not done */
    val toggleDone: (String) -> Unit,
    /** end a todo's snooze now */
    val wake: (String) -> Unit,
    /** open the snooze sheet for a todo */
    val snooze: (String) -> Unit,
    /** pin or unpin a todo */
    val togglePin: (String) -> Unit,
    /** delete a todo */
    val delete: (String) -> Unit,
    /** open a todo's text to edit it */
    val startEditing: (String) -> Unit,
    /** new text for the todo being edited */
    val edit: (String, String) -> Unit,
    /** the editor closed: the edit ends, saved, or deleted when cleared out */
    val finishEditing: (String) -> Unit,
    /** Enter in this todo, which has text: start the next one above it; false when it can't have one */
    val enter: (String) -> Boolean,
    /** whether Enter in this todo, once it has text, starts the next one above it */
    val continues: (String) -> Boolean = { false },
) {
    /** These actions, with [save] run before each one that acts on a todo. */
    fun savingFirst(save: () -> Unit): TodoActions = TodoActions(
        toggleDone = { save(); toggleDone(it) },
        wake = { save(); wake(it) },
        snooze = { save(); snooze(it) },
        togglePin = { save(); togglePin(it) },
        delete = { save(); delete(it) },
        startEditing = startEditing,
        edit = edit,
        finishEditing = finishEditing,
        enter = enter,
        continues = continues,
    )
}

/** What every todo row reads besides its todo: the clock, the todo being edited and the tags in use. */
@Immutable
internal data class TodoContext(
    /** the clock times are shown against */
    val now: Long,
    /** the todo whose text is open */
    val editingId: String?,
    /** every tag in use, for completing one */
    val tagCounts: Map<String, Tags.Known>,
    /** the todo opened from its reminder, highlighted for a moment */
    val highlightId: String? = null,
)

/** How each of [todoSwipeAction]'s actions is labeled and drawn. */
private enum class TodoSwipe(val label: String, val icon: ImageVector) {
    SNOOZE("Snooze", Icons.Rounded.Schedule),
    RESTORE("Restore", Icons.AutoMirrored.Rounded.Undo),
    DONE("Done", Icons.Rounded.Check),
    DELETE("Delete", Icons.Rounded.Delete),
}

@Composable
private fun todoSwipeLook(swipe: TodoSwipe): SwipeLook {
    val colors = MaterialTheme.colorScheme
    return when (swipe) {
        TodoSwipe.SNOOZE -> SwipeLook(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary, colors.onTertiary, swipe.icon, swipe.label)
        TodoSwipe.RESTORE -> SwipeLook(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary, swipe.icon, swipe.label)
        TodoSwipe.DONE -> SwipeLook(paper.doneSoft, paper.done, paper.done, colors.onSecondary, swipe.icon, swipe.label)
        TodoSwipe.DELETE -> SwipeLook(colors.errorContainer, colors.onErrorContainer, colors.error, colors.onError, swipe.icon, swipe.label)
    }
}

private fun todoSwipe(action: TodoSwipeAction): TodoSwipe = when (action) {
    TodoSwipeAction.SNOOZE -> TodoSwipe.SNOOZE
    TodoSwipeAction.REACTIVATE -> TodoSwipe.RESTORE
    TodoSwipeAction.DONE -> TodoSwipe.DONE
    TodoSwipeAction.DELETE -> TodoSwipe.DELETE
}

/**
 * One todo in a list: its select dot (which selects it when given a [selection], or else marks it
 * done; held, it offers Pin and Delete for this todo alone), its styled text (edited in place on a
 * tap), its repeat and its snooze. A swipe right snoozes (or makes a done or snoozed one active),
 * left marks it done (or deletes a done one), on this todo alone even while rows are selected. A
 * long press anywhere on the row opens the dot's hold menu, as holding the dot does; [selection]'s
 * select, run by the dot, joins the list's selection, and a selected row is tinted, with a bar down
 * its left edge.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TodoRow(
    todo: Todo,
    context: TodoContext,
    actions: TodoActions,
    selection: TodoSelection? = null,
) {
    val done = todo.state == TodoState.DONE
    val snoozed = todo.isSnoozed(context.now)
    val editing = context.editingId == todo.id
    val focusManager = LocalFocusManager.current
    // Clearing the editor's focus saves its text and closes it (TodoEditor's onBlur), and hides the keyboard.
    val saving = remember(actions, focusManager) { actions.savingFirst { focusManager.clearFocus() } }
    val acting = if (editing) saving else actions
    // Back with the keyboard up only hides it, which ends the editing too (TodoEditor).
    LayerBackHandler(open = editing) { focusManager.clearFocus() }
    // While rows are selected a swipe acts on this todo alone, as it does otherwise.
    val right = todoSwipe(todoSwipeAction(SwipeSide.START, done, snoozed))
    val left = todoSwipe(todoSwipeAction(SwipeSide.END, done, snoozed))
    val picking = if (editing && selection != null) selection.copy(select = { focusManager.clearFocus(); selection.select() }) else selection
    val run: (TodoSwipe) -> Unit = { swipe ->
        when (swipe) {
            TodoSwipe.SNOOZE -> acting.snooze(todo.id)
            TodoSwipe.RESTORE -> if (done) acting.toggleDone(todo.id) else acting.wake(todo.id)
            TodoSwipe.DONE -> acting.toggleDone(todo.id)
            TodoSwipe.DELETE -> acting.delete(todo.id)
        }
    }
    // The moves a swipe let go with stay under the row until it settles: the action changes the todo at once (Done would
    // turn the move under it into Delete).
    SwipeFrame(
        under = right to left,
        startEnabled = true,
        endEnabled = true,
        onSwipe = { side -> run(if (side == SwipeSide.START) right else left) },
        background = { state, (startMove, endMove) -> SwipeBackground(state, todoSwipeLook(startMove), todoSwipeLook(endMove)) },
    ) { swiping ->
        TodoRowBody(todo, context, acting, listOf(right, left), run, picking, swiping = swiping)
    }
}

@Composable
private fun TodoRowBody(
    todo: Todo,
    context: TodoContext,
    actions: TodoActions,
    swipes: List<TodoSwipe>,
    onSwipe: (TodoSwipe) -> Unit,
    selection: TodoSelection? = null,
    swiping: Boolean = false,
) {
    val done = todo.state == TodoState.DONE
    val snoozed = todo.isSnoozed(context.now)
    val woke = !done && !snoozed && todo.snoozeUntil != null
    val editing = context.editingId == todo.id
    val labelTagNames = LocalLabelTags.current.names
    val haptics = LocalHapticFeedback.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (done) TextDecoration.LineThrough else null)
    val textColor = if (done) paper.textSecondary else paper.text
    val selecting = selection?.selecting == true
    val selected = selection?.selected == true
    val selectLabel = if (selected) "Unselect" else "Select"
    val doneLabel = if (done) "Mark not done" else "Mark done"
    val highlighted = selected || context.highlightId == todo.id
    val menu = LocalHoldMenu.current
    // The hold menu this row opened, so the row stays tinted while it is open, as while editing.
    var shownMenu by remember { mutableStateOf<HoldMenuSpec?>(null) }
    val held = shownMenu != null && menu?.spec === shownMenu
    val fill by animateColorAsState(
        when {
            highlighted -> paper.accentSoft
            editing || held -> paper.hover
            else -> paper.card
        },
        label = "todoHighlight",
    )
    // The check fills before the todo is marked done, so the tick shows even on a row that then leaves the list.
    val tick = remember { Animatable(if (done) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(done) { tick.animateTo(if (done) 1f else 0f, tween(TICK_MS)) }
    val toggleDone = {
        if (done) {
            actions.toggleDone(todo.id)
        } else if (!tick.isRunning) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                tick.animateTo(1f, tween(TICK_MS))
                actions.toggleDone(todo.id)
            }
        }
    }
    var dot by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val openHold = rememberTodoHold(todo, done, actions, onShown = { shownMenu = it }) { dot }
    val holdLabel = when {
        done -> "Delete"
        todo.pinned -> "Unpin or delete"
        else -> "Pin or delete"
    }
    // Where in the text the last press landed, for the editor's cursor; null past the text.
    var pressCaret by remember { mutableStateOf<Int?>(null) }
    val androidContext = LocalContext.current
    // The text's links, whose actions join the row's own: the row's actions are all TalkBack lists.
    val linkActions = remember(todo.text, editing, selecting) {
        if (editing || selecting) emptyList() else linkAccessibilityActions(androidContext, findWebLinks(todo.text).map { it.href })
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressCaret = null
                }
            }
            .swipeRaised(swiping)
            .background(if (swiping) paper.raised else fill)
            .then(
                if (editing) {
                    Modifier
                } else {
                    // The menu key opens the hold menu, as a right click or the menu key does on the web.
                    Modifier.onKeyEvent { event ->
                        val press = event.type == KeyEventType.KeyUp && event.key == Key.Menu
                        press && openHold(true, null)
                    }.clickable(onClickLabel = if (selecting) selectLabel else "Edit") {
                        if (selecting) selection.select() else actions.startEditing(todo.id)
                    }
                },
            )
            .then(
                if (editing || menu == null) {
                    Modifier
                } else {
                    // Inside the click, so a hold that opened the menu takes the finger's lift from it.
                    Modifier
                        .onGloballyPositioned { placed = it }
                        .pointerInput(menu) {
                            holdMenuGestures(menu, haptics, claim = false, onTap = null, open = { openHold(false, it) }) { placed }
                        }
                        .semantics { onLongClick(holdLabel) { openHold(true, null) } }
                },
            )
            .semantics {
                customActions = swipes.distinct().map { swipe ->
                    CustomAccessibilityAction(swipe.label) {
                        onSwipe(swipe)
                        true
                    }
                } + listOfNotNull(
                    CustomAccessibilityAction(if (todo.pinned) "Unpin" else "Pin") {
                        actions.togglePin(todo.id)
                        true
                    }.takeIf { !done },
                    CustomAccessibilityAction("Delete") {
                        actions.delete(todo.id)
                        true
                    }.takeIf { !done },
                ) + linkActions
            }
            .padding(start = 14.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // In a list the dot selects, as a thread's avatar does; done is a swipe, the row's action or the selection bar.
        SelectDot(
            label = if (selection != null) selectLabel else doneLabel,
            holdLabel = holdLabel,
            onTap = { selection?.select?.invoke() ?: toggleDone() },
            onPlaced = { dot = it },
            openHold = openHold,
        ) {
            TodoCheck(tick.value, snoozed && !done, todo.pinned && !done, selected, label = null)
        }
        // At least the check's height, so a single line sits centered beside it.
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 10.dp),
        ) {
            if (editing) {
                TodoEditor(
                    initial = todo.text,
                    onChange = { actions.edit(todo.id, it) },
                    onEnter = { text -> text.isNotBlank() && actions.enter(todo.id) },
                    onBlur = { actions.finishEditing(todo.id) },
                    continues = actions.continues(todo.id),
                    suggest = { text, prefix -> Tags.suggest(context.tagCounts, text, prefix, offered = labelTagNames) },
                    style = textStyle,
                    color = textColor,
                    label = "Todo",
                    caret = pressCaret,
                )
            } else {
                // Selecting, a tap on a link selects the row as a tap anywhere on it does.
                TodoText(todo.text.ifEmpty { "​" }, textStyle, textColor, onPress = { pressCaret = it }, links = !selecting)
            }
            val repeat = todo.repeat
            if (!done && repeat != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Repeat, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(14.dp))
                    Text(
                        repeat.text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val until = todo.snoozeUntil?.let { isoToEpoch(it) }
            // A woken todo's marker sits under its text, as a woken thread's does.
            if (woke && until != null) SnoozedMarker(lapsedMarker(until, context.now), Modifier.padding(top = 4.dp))
        }
        Box(Modifier.padding(top = 14.dp)) {
            val until = todo.snoozeUntil?.let { isoToEpoch(it) }
            when {
                snoozed && until != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = paper.onSnoozeSoft, modifier = Modifier.size(14.dp))
                    Text(
                        formatWakeTime(until, context.now),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = paper.onSnoozeSoft,
                    )
                }
                done -> Text(formatListDate(doneTodoDate(todo), context.now), style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
            }
        }
    }
}

/**
 * A todo's mark: a small dot, a ring with a dot in it while selected, a clock while snoozed, a pin
 * while pinned, and a green check filling from its middle as [filled] goes from 0 to 1 (done).
 * [label] is read out, unless the mark sits in a control that says it.
 */
@Composable
private fun TodoCheck(filled: Float, snoozed: Boolean, pinned: Boolean, selected: Boolean, label: String?) {
    val described = if (label != null) Modifier.semantics { contentDescription = label } else Modifier
    when {
        selected -> Box(contentAlignment = Alignment.Center, modifier = described.size(22.dp).background(paper.accent, CircleShape)) {
            Box(Modifier.size(8.dp).background(paper.onAccent, CircleShape))
        }
        filled == 0f && snoozed -> Icon(Icons.Outlined.Schedule, contentDescription = label, tint = paper.snooze, modifier = Modifier.size(MARK_SIZE))
        filled == 0f && pinned -> Icon(Icons.Outlined.PushPin, contentDescription = label, tint = paper.accent, modifier = Modifier.size(MARK_SIZE))
        filled == 0f -> Box(contentAlignment = Alignment.Center, modifier = described.size(22.dp)) {
            Box(Modifier.size(8.dp).background(paper.borderStrong, CircleShape))
        }
        else -> Box(contentAlignment = Alignment.Center, modifier = described.size(22.dp)) {
            Box(Modifier.size(MARK_SIZE).scale(filled).background(paper.done, CircleShape))
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.size(14.dp).graphicsLayer { alpha = filled },
            )
        }
    }
}

// A todo's done, snoozed or pinned mark; the selected ring is 22 dp.
private val MARK_SIZE = 20.dp

/**
 * How to open [todo]'s hold menu, around a press at a root point, or on its select dot ([dot]) for
 * TalkBack and the menu key: Pin (or Unpin; not on a done todo) and Delete for this todo alone,
 * placed by [rowHoldWedges]. The returned function opens it, waiting for a tap when sticky, and
 * says whether it opened; [onShown] gets the menu it shows.
 */
@Composable
private fun rememberTodoHold(
    todo: Todo,
    done: Boolean,
    actions: TodoActions,
    onShown: (HoldMenuSpec) -> Unit,
    dot: () -> LayoutCoordinates?,
): (sticky: Boolean, pressedAt: Offset?) -> Boolean {
    val menu = LocalHoldMenu.current
    val shownTo by rememberUpdatedState(onShown)
    val density = LocalDensity.current
    val acts by rememberUpdatedState(actions)
    val shown by rememberUpdatedState(todo)
    val isDone by rememberUpdatedState(done)
    val place by rememberUpdatedState(dot)
    return remember(menu, density) {
        { sticky, pressedAt ->
            val placed = place()
            if (menu != null && placed != null && placed.isAttached) {
                val center = pressedAt ?: placed.localToRoot(Offset(placed.size.width / 2f, placed.size.height / 2f))
                val screen = placed.findRootCoordinates().size
                val scale = density.density
                val current = shown
                val items = listOf(
                    HoldMenuItem("pin", if (current.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin),
                    HoldMenuItem("delete", "Delete", Icons.Outlined.Delete, HoldTone.DANGER),
                )
                val spec = HoldMenuSpec(
                    center = center,
                    wedges = holdWedges(
                        items,
                        rowHoldWedges(listOf("pin".takeUnless { isDone }, "delete"), center.x / scale, center.y / scale, screen.width / scale),
                    ),
                    pill = current.text.lineSequence().firstOrNull().orEmpty().ifBlank { "Todo" },
                    url = false,
                    label = "Todo actions",
                    onPick = { item -> if (item.id == "pin") acts.togglePin(current.id) else acts.delete(current.id) },
                )
                menu.show(spec, sticky = sticky)
                shownTo(spec)
                true
            } else {
                false
            }
        }
    }
}

/**
 * Each press in turn: a tap runs [onTap] (when given; otherwise it is left to a click around it), a
 * drag or a lost finger is left to the list and the row's swipe, and a hold of [HOLD_MS] runs
 * [open], then moves [menu] with the finger until it lets go, the list and the swipe no longer
 * seeing it; [open] gets where the press went down, in root pixels (null when this input is no longer
 * placed). Letting go where the press began keeps the menu open for a tap. [claim] takes every
 * press from the list at once, so it never scrolls. [throughChildren] also holds on a press a
 * button inside took (a row's avatar), as the web's row does; a hold there doesn't tap it. [takes]
 * says whether a press going down there (local pixels) is this input's at all; one it doesn't take
 * is left alone. [pass] is the pass the press is first seen in, before the hold: [PointerEventPass.Initial]
 * sees it before what is inside. [placed] is where this input sits, to put the finger on the screen.
 */
internal suspend fun PointerInputScope.holdMenuGestures(
    menu: HoldMenuState,
    haptics: HapticFeedback,
    claim: Boolean,
    onTap: (() -> Unit)?,
    open: (pressedAt: Offset?) -> Boolean,
    throughChildren: Boolean = false,
    takes: (Offset) -> Boolean = { true },
    pass: PointerEventPass = PointerEventPass.Main,
    placed: () -> LayoutCoordinates?,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = !throughChildren, pass = pass)
        if (!takes(down.position)) return@awaitEachGesture
        if (claim) down.consume()
        // True for a tap, false for a drag or a lost finger, null once the hold lasted.
        val before = withTimeoutOrNull(HOLD_MS) {
            var outcome: Boolean? = null
            while (outcome == null) {
                val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id }
                // A gesture the system cancels ends in an up that comes already consumed.
                outcome = when {
                    change == null || change.isConsumed -> false
                    !change.pressed -> {
                        if (onTap != null) change.consume()
                        true
                    }
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                    else -> null
                }
            }
            outcome
        }
        when (before) {
            true -> onTap?.invoke()
            false -> Unit
            null -> {
                val coordinates = placed()
                val pressedAt = coordinates?.takeIf { it.isAttached }?.localToRoot(down.position)
                if (open(pressedAt)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // The disc may sit away from the finger (on the view's name): only a slide from the press picks.
                    var slid = false
                    val tapRadius = HOLD_TAP * density
                    while (true) {
                        // Taken before anything inside sees it (a row's avatar, a todo's text), so the
                        // lift ends the hold rather than also tapping what is under the finger.
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || coordinates == null || pressedAt == null || !coordinates.isAttached) {
                            menu.cancel()
                            break
                        }
                        change.consume()
                        val at = coordinates.localToRoot(change.position)
                        val near = (at - pressedAt).getDistance() < tapRadius
                        slid = slid || !near
                        if (change.pressed) {
                            if (slid) menu.move(at)
                        } else {
                            menu.release(if (slid) at else menu.spec?.center ?: at, nearPress = near)
                            break
                        }
                    }
                }
            }
        }
    }
}

/**
 * The select dot left of a todo in a list: a tap runs [onTap]; a hold of [HOLD_MS] runs
 * [openHold] at the finger, as a long press anywhere on the row does, TalkBack's on the dot. A press
 * here never scrolls the list.
 */
@Composable
private fun SelectDot(
    label: String,
    holdLabel: String,
    onTap: () -> Unit,
    onPlaced: (LayoutCoordinates) -> Unit,
    openHold: (sticky: Boolean, pressedAt: Offset?) -> Boolean,
    mark: @Composable () -> Unit,
) {
    val menu = LocalHoldMenu.current
    val haptics = LocalHapticFeedback.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tap by rememberUpdatedState(onTap)
    val open by rememberUpdatedState(openHold)
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .onGloballyPositioned {
                coordinates = it
                onPlaced(it)
            }
            // Reachable by keyboard, as a button: Enter or Space taps it.
            .onKeyEvent { event ->
                val press = event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Spacebar || event.key == Key.NumPadEnter)
                if (press) tap()
                press
            }
            .focusable(interactionSource = interactions)
            .indication(interactions, ripple(bounded = false, radius = 20.dp))
            .pointerInput(menu) {
                if (menu != null) {
                    holdMenuGestures(menu, haptics, claim = true, onTap = { tap() }, open = { open(false, it) }) { coordinates }
                } else {
                    detectTapGestures { tap() }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    tap()
                    true
                }
                if (menu != null) {
                    onLongClick(holdLabel) { open(true, null) }
                }
            },
    ) {
        mark()
    }
}

// How long a todo's check takes to fill.
private const val TICK_MS = 200

/**
 * An email's todo as one line: its text, which TalkBack reads as a todo. Under the snippet of the email's row
 * it has no actions of its own, as the row's are the email's, and a tap falls through to the row. At
 * the top of the open email ([actions] given) its text opens to edit on a tap; cleared out, the todo
 * is deleted with undo ([TodoActions.finishEditing]), and a new one never saved just goes.
 */
@Composable
internal fun EmailTodoLine(
    todo: EmailTodo,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    tagCounts: Map<String, Tags.Known> = emptyMap(),
    actions: TodoActions? = null,
) {
    val focusManager = LocalFocusManager.current
    val labelTagNames = LocalLabelTags.current.names
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp)
    LayerBackHandler(open = editing) { focusManager.clearFocus() }
    var pressCaret by remember { mutableStateOf<Int?>(null) }
    Row(modifier = modifier.fillMaxWidth()) {
        val textModifier = Modifier.weight(1f)
        // TalkBack hears it as a todo, as the web's screen readers do.
        val heard = Modifier.semantics { contentDescription = "Todo: ${todo.text}" }
        when {
            editing && actions != null -> TodoEditor(
                initial = todo.text,
                onChange = { actions.edit(todo.id, it) },
                onEnter = { false },
                onBlur = { actions.finishEditing(todo.id) },
                suggest = { text, prefix -> Tags.suggest(tagCounts, text, prefix, offered = labelTagNames) },
                style = textStyle,
                color = paper.text,
                label = "Todo",
                modifier = textModifier,
                caret = pressCaret,
            )
            actions != null -> TodoText(
                todo.text.ifEmpty { "\u200B" },
                textStyle,
                paper.text,
                textModifier.then(heard).clickable(onClickLabel = "Edit todo") { actions.startEditing(todo.id) },
                onPress = { pressCaret = it },
                links = true,
            )
            else -> TodoText(todo.text.ifEmpty { "\u200B" }, textStyle, paper.text, textModifier.then(heard), maxLines = 1)
        }
    }
}

/** A todo row's part in the list's selection: whether it is selected, whether any row is, and how to toggle it. */
@Immutable
internal data class TodoSelection(
    /** whether this todo is selected */
    val selected: Boolean,
    /** whether any row is selected, so a tap selects rather than edits */
    val selecting: Boolean,
    /** select this todo, or unselect it */
    val select: () -> Unit,
)
