package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelImportant
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.FocusSource
import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.LayoutEntry
import cc.hafa.inbox.core.mail.LayoutItem
import cc.hafa.inbox.core.mail.ListAnchor
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.ListRow
import cc.hafa.inbox.core.mail.ListState
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.Row as MailRow
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.BundleSwipeAction
import cc.hafa.inbox.core.mail.SwipeSide
import cc.hafa.inbox.core.mail.ThreadAction
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.ThreadSwipeAction
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.bundleCounts
import cc.hafa.inbox.core.mail.bundleLine
import cc.hafa.inbox.core.mail.bundleRowKey
import cc.hafa.inbox.core.mail.bundleSwipeAction
import cc.hafa.inbox.core.mail.bundleSwipeLabel
import cc.hafa.inbox.core.mail.commitThreshold
import cc.hafa.inbox.core.mail.formatListDate
import cc.hafa.inbox.core.mail.formatWakeTime
import cc.hafa.inbox.core.mail.lapsedMarker
import cc.hafa.inbox.core.mail.rowHoldActions
import cc.hafa.inbox.core.mail.sendersLabel
import cc.hafa.inbox.core.mail.swipeProgress
import cc.hafa.inbox.core.mail.threadActions
import cc.hafa.inbox.core.mail.threadSwipeAction
import cc.hafa.inbox.core.mail.todoRowKey
import cc.hafa.inbox.core.menu.HoldArc
import cc.hafa.inbox.core.menu.rowHoldWedges
import cc.hafa.inbox.core.pgp.DecryptedPreview
import cc.hafa.inbox.core.pgp.OBSCURED_SUBJECT
import cc.hafa.inbox.core.search.searchPlan
import cc.hafa.inbox.core.theme.labelTone
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.listDate
import cc.hafa.inbox.core.todo.styledPlain
import cc.hafa.inbox.core.todo.TODO_PAGE
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.data.TodoSnapshot
import cc.hafa.inbox.ui.theme.Geist
import cc.hafa.inbox.ui.theme.paper
import cc.hafa.inbox.ui.theme.rowHighlight
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What a list row can ask for. */
class RowActions(
    /** tell the list what happened to it: a tap, focus, a pick, a bundle closing (see [ListEvent]) */
    val list: (ListEvent<MailRow, Todo>) -> Unit,
    /** a thread's row was tapped open, by row key */
    val tap: (String) -> Unit,
    /** archive threads */
    val done: (List<String>) -> Unit,
    /** open the snooze sheet for a thread */
    val snooze: (String) -> Unit,
    /** pin or unpin */
    val togglePin: (String) -> Unit,
    /** end a snooze now */
    val wakeNow: (String) -> Unit,
    /** fetch the next page of a bundle's inbox threads, by label id */
    val loadMoreBundle: (String) -> Unit,
    /** Done all on a bundle, by label id, with the threads it shows */
    val doneBundle: (String, List<String>) -> Unit,
    /** Done all on a section, by its key: its unpinned threads and todos */
    val doneSection: (String) -> Unit,
    /** open a label's settings */
    val labelSettings: (String) -> Unit,
    /** fetch the next page */
    val loadMore: () -> Unit,
    /** reload the list from Gmail, returning once done */
    val refresh: suspend () -> Unit = {},
    /** note whether the end of a bundle's open list, by label id, is on screen */
    val bundleEndShown: (String, Boolean) -> Unit = { _, _ -> },
    /** let the list say where it sits (its first row and how far it is scrolled past), or stop with null */
    val trackAnchor: ((() -> ListAnchor?)?) -> Unit = {},
    /** put a thread that is neither in the inbox nor snoozed back in the inbox */
    val moveToInbox: (String) -> Unit = {},
    /** take a thread out of spam and back to the inbox */
    val notSpam: (String) -> Unit = {},
    /** show another page of the list's todos */
    val showMoreTodos: () -> Unit = {},
    /** run an action from a thread's round menu, by thread id */
    val act: (String, ThreadAction) -> Unit = { _, _ -> },
    /** open the snooze sheet for a bundle, by label id, with the threads it shows */
    val snoozeBundle: (String, List<String>) -> Unit = { _, _ -> },
    /** Pin all on a bundle, by label id, with the threads it shows */
    val pinBundle: (String, List<String>) -> Unit = { _, _ -> },
    /** Delete all on a bundle, by label id, with the threads it shows */
    val deleteBundle: (String, List<String>) -> Unit = { _, _ -> },
)

private val CARD_RADIUS = 18.dp
private val INNER_RADIUS = 17.dp
private val RAISED_RADIUS = 20.dp
private val RAISED_SHADOW = 10.dp
// How far the rest of the list fades while a bundle is open.
private const val DIMMED_ALPHA = 0.45f
private const val DIM_MS = 200
// Rows fade in and out, and the rest slide into place, over this long, as on web.
private const val LIST_MS = 200
private val LIST_FADE = tween<Float>(LIST_MS)
// Rows slide on a spring, so a slide whose end moves again on the way (an opening bundle's header
// folding its summary line) carries on rather than starting over.
private val ROW_MOVE = spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold)

/**
 * Put a swiped row back once a row its action removes has faded out with it; a row the action
 * leaves (Snooze's sheet, a label view after Done) jumps back then.
 */
internal suspend fun settleAfterSwipe(state: SwipeToDismissBoxState) {
    delay(LIST_MS.toLong())
    state.snapTo(SwipeToDismissBoxValue.Settled)
}

/** Which part of an open bundle a raised piece draws: all of it, its header, a row between, or its footer. */
private enum class RaisedPart { WHOLE, TOP, MIDDLE, BOTTOM }

/** The key of the open bundle's footer, after its last row. */
private fun bundleEndKey(labelId: String): String = "bundle-end:$labelId"

/** The key of the empty sticky item that ends the open bundle's header. */
private fun bundleStopKey(labelId: String): String = "bundle-stop:$labelId"

// A held header sits over the rows scrolled under it, which otherwise start right below it.
private fun headerHeld(state: LazyListState, key: String): Boolean {
    val items = state.layoutInfo.visibleItemsInfo
    val head = items.firstOrNull { it.key == key } ?: return false
    return items.any { it.index > head.index && it.offset < head.offset + head.size }
}

private fun cardShape(first: Boolean, last: Boolean, radius: Dp): Shape = RoundedCornerShape(
    topStart = if (first) radius else 0.dp,
    topEnd = if (first) radius else 0.dp,
    bottomStart = if (last) radius else 0.dp,
    bottomEnd = if (last) radius else 0.dp,
)

/**
 * One piece of a day's card: hairline border around, a separator above all but the first. While
 * a bundle is open the piece fades, and a tap on it closes the bundle instead of acting. A
 * [raised] piece is the open bundle, lifted out of the card with a little room around it, drawn
 * whole or as the [part] its header or its body makes.
 */
@Composable
private fun Piece(
    first: Boolean,
    last: Boolean,
    dim: Dim,
    raised: Boolean = false,
    part: RaisedPart = RaisedPart.WHOLE,
    shadow: Dp = RAISED_SHADOW,
    content: @Composable () -> Unit,
) {
    val dimmed = dim.dimmed && !raised
    val alpha by animateFloatAsState(if (dimmed) DIMMED_ALPHA else 1f, tween(DIM_MS), label = "dim")
    // The room around a raised piece grows with it rather than jumping in.
    val side by animateDpAsState(if (raised) 8.dp else 12.dp, tween(DIM_MS), label = "raisedSide")
    val gap by animateDpAsState(if (raised) 10.dp else 0.dp, tween(DIM_MS), label = "raisedGap")
    val frame = if (raised || gap > 0.dp) {
        val top = part == RaisedPart.WHOLE || part == RaisedPart.TOP
        val bottom = part == RaisedPart.WHOLE || part == RaisedPart.BOTTOM
        Modifier
            .then(if (top) Modifier else Modifier.withoutTopShadow(bottom))
            .padding(start = side, end = side, top = if (top) gap else 0.dp, bottom = if (bottom) gap else 0.dp)
            .fillMaxWidth()
            .shadow(shadow, cardShape(top, bottom, RAISED_RADIUS))
            .clip(cardShape(top, bottom, RAISED_RADIUS))
            .background(paper.border)
            .padding(start = 1.dp, end = 1.dp, top = if (top) 1.dp else 0.dp, bottom = if (bottom) 1.dp else 0.dp)
            .clip(cardShape(top, bottom, RAISED_RADIUS - 1.dp))
    } else {
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(cardShape(first, last, CARD_RADIUS))
            .background(paper.border)
            .padding(start = 1.dp, end = 1.dp, top = if (first) 1.dp else 0.dp, bottom = 1.dp)
            .clip(cardShape(first, last, INNER_RADIUS))
    }
    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }.then(frame).background(paper.card)) {
        Dimmable(dimmed, dim.close, content)
    }
}

// How far a raised piece's shadow may spread past its sides, more than it ever does.
private val SHADOW_SPREAD = 32.dp

/**
 * Draw without the part of the shadow that spills over the top edge, onto the piece above: each
 * piece of an open bundle casts its own shadow, and one piece's falling across the row above it
 * would show as a line between them. The sides keep theirs, and the bottom too when [bottom].
 */
private fun Modifier.withoutTopShadow(bottom: Boolean): Modifier = drawWithContent {
    val spread = SHADOW_SPREAD.toPx()
    clipRect(left = -spread, top = 0f, right = size.width + spread, bottom = if (bottom) size.height + spread else size.height) {
        this@drawWithContent.drawContent()
    }
}

/**
 * Content that, while [dimmed], is covered by a tap target that closes the top layer. Dimmed content is
 * hidden from TalkBack, which closes the bundle through the open bundle's own header.
 */
@Composable
private fun Dimmable(dimmed: Boolean, close: () -> Unit, content: @Composable () -> Unit) {
    Box {
        Box(if (dimmed) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        if (dimmed) {
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { testTag = DIMMED_COVER_TAG }
                    .clickable(interactionSource = null, indication = null, onClick = close),
            )
        }
    }
}

/** Test tag of the cover over a piece faded behind an open bundle. */
internal const val DIMMED_COVER_TAG = "dimmed-cover"

/** Whether a piece of the list is faded behind an open bundle, and how a tap on it closes the bundle. */
@Immutable
private data class Dim(
    /** whether it is faded */
    val dimmed: Boolean,
    /** close the open bundle */
    val close: () -> Unit,
)

/** Focus mode as the list shows it: whether it applies here, how many rows it hides, and how to turn it off. */
@Immutable
data class FocusInfo(
    /** whether focus mode filters this list */
    val on: Boolean = false,
    /** loaded rows it hides */
    val hidden: Int = 0,
    /** turn focus mode off */
    val turnOff: () -> Unit = {},
)

private fun hiddenText(hidden: Int): String =
    if (hidden == 1) "1 item that isn't pinned" else "$hidden items that aren't pinned"

/** The empty-list line for a view, as on web. */
private fun emptyText(view: View): String = when {
    view == View.INBOX -> "All done. Nothing in your inbox."
    view == View.Fixed(Mailbox.SNOOZED) -> "Nothing snoozed."
    view is View.Search && searchPlan(view.query).todosOnly -> "No todos match that search."
    view is View.Search -> "No mail matches that search."
    else -> "Nothing here."
}


/** What todo rows need from the list: their context and actions. */
@Immutable
private data class TodoRows(
    /** the clock, the todo being edited, the tags in use */
    val context: TodoContext,
    /** what a row can ask for */
    val actions: TodoActions,
)

/** What every row of a list reads from the model, so a row redraws only when one of these changes. */
@Immutable
private data class RowContext(
    /** the account's address, left out of sender lines */
    val email: String,
    /** the clock dates are shown against */
    val now: Long,
    /** whether the list is Snoozed */
    val inSnoozed: Boolean,
    /** decrypted subjects and snippets, by thread id */
    val previews: Map<String, DecryptedPreview>,
    /** snoozed threads with a newer message a filter kept out of the inbox */
    val skippedInbox: Set<String>,
    /** bundle labels Gmail has more inbox threads of than were fetched */
    val bundlesWithMore: Set<String> = emptySet(),
    /** bundle labels whose next page is being fetched */
    val bundlesLoading: Set<String> = emptySet(),
    /** why each bundle label's last next page failed to load */
    val bundleErrors: Map<String, String> = emptyMap(),
    /** the selected threads; while any are, a tap selects rather than opens */
    val selected: Set<String> = emptySet(),
)

/**
 * The current view as a single column of section cards, drawn from the list's rows
 * ([MailModel.list]): todos among the threads when [todos] are on, the open bundle lifted out of its
 * card, the Todos view's lists, and above Drafts' threads its new
 * messages not in Gmail yet ([localDrafts]). It scrolls only where the list
 * says: a row the keys reveal comes into view, a request from [toTop] (a tap on the view's name)
 * glides back to the top, and when a new layout takes away the row at the top of the screen, the row
 * the list kept in its place stays there.
 */
@Composable
fun ThreadList(
    model: MailModel,
    actions: RowActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    focus: FocusInfo = FocusInfo(),
    todos: TodoSnapshot = TodoSnapshot(),
    todoActions: TodoActions? = null,
    localDrafts: LocalOnlyRows = LocalOnlyRows(),
    toTop: TopRequests? = null,
) {
    val list = model.currentList
    val rowList = model.list
    val labels = model.listLabelsById
    val icons = remember(model.labels, model.labelPrefDocs) { model.labelPrefs.mapValues { it.value.icon } }
    val context = RowContext(
        model.email,
        model.now,
        model.view == View.Fixed(Mailbox.SNOOZED),
        model.rowPreviews,
        model.rowSkippedInbox,
        model.bundleLabelIds.filter(model::bundleHasMore).toSet(),
        model.bundleLabelIds.filter(model::bundleLoading).toSet(),
        model.bundleLabelIds.mapNotNull { labelId -> model.bundleError(labelId)?.let { labelId to it } }.toMap(),
        rowList.selection.toSet(),
    )
    // A page whose threads all filter out leaves no rows, so the next page is fetched rather than "Nothing here".
    val more = list.nextPageToken != null && list.error == null
    val lines = remember { SummaryLines() }
    val inTodos = model.view == View.Todos
    val todoContext = TodoContext(model.now, todos.editingId, todos.tagCounts, todos.highlightId)
    val rows = todoActions?.let { TodoRows(todoContext, it) }
    val items = remember(rowList.rows, todoActions != null) { rowItems(rowList.rows, todoActions != null) }
    val itemIndex = remember(items) { items.withIndex().associate { (index, item) -> item.key to index } }
    val rowKeys = remember(rowList.rows) { rowList.rows.mapTo(hashSetOf()) { it.key } }
    val keyboard = remember { KeyboardOverlap() }
    keyboard.Track()
    val editingKey = todos.editingId?.let(::todoRowKey)
    val listState = rememberListScroll(rowList, rowKeys, itemIndex, actions, keyboard, editingKey)
    toTop?.Follow(listState)
    // A todo opened from its reminder is scrolled to once its list shows it.
    val highlightIndex = todos.highlightId?.takeIf { inTodos }?.let { itemIndex[todoRowKey(it)] }
    LaunchedEffect(highlightIndex) { highlightIndex?.let { listState.animateScrollToItem(it) } }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    actions.refresh()
                } finally {
                    refreshing = false
                }
            }
        }
    }
    val pull = rememberPullToRefreshState()
    val haptics = LocalHapticFeedback.current
    // Material moves the indicator on its own as a refresh starts and ends; only a finger's pull ticks.
    ThresholdTicks(armed = { pull.distanceFraction >= 1f }, counts = { !pull.isAnimating && !refreshing })
    val frame = RowFrame(
        rowList,
        context,
        canDoneAll = model.view == View.INBOX,
        dim = Dim(dimmed = rowList.bundle != null, close = { actions.list(ListEvent.Dismiss) }),
        labels = labels,
        icons = icons,
        emailTodo = model::rowTodo,
        inTodos = inTodos,
    )
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            refresh()
        },
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing,
                containerColor = paper.card,
                color = paper.accent,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = modifier.fillMaxSize(),
    ) {
        // While a todo is edited the list ends at the keyboard, so even the last row can scroll above it.
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .then(if (editingKey != null) Modifier.imePadding() else Modifier)
                .onGloballyPositioned(keyboard::place),
        ) {
            if (inTodos && rowList.rows.isEmpty()) {
                item(key = "status", contentType = "status") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp)) {
                        if (!todos.loaded || model.readingTodoEmails) {
                            CircularProgressIndicator(color = paper.accent)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Nothing to do.", color = paper.text, textAlign = TextAlign.Center)
                                Text(
                                    "Snoozed and done todos are in Snoozed and Done.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = paper.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            val localShown = model.view == View.Fixed(Mailbox.DRAFTS) && localDrafts.drafts.isNotEmpty()
            if (localShown) {
                item(key = "local-drafts", contentType = "local-drafts") { LocalDraftCard(localDrafts) }
            }
            if (!inTodos && rowList.rows.isEmpty() && !more && !localShown) {
                item(key = "status", contentType = "status") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp)) {
                        when {
                            !list.loaded || (list.loading && list.ids.isEmpty()) -> CircularProgressIndicator(color = paper.accent)
                            list.error != null -> ListError(list.error.orEmpty(), onRetry = refresh)
                            focus.on -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    if (focus.hidden > 0) {
                                        "Nothing pinned right now. Focus is hiding ${hiddenText(focus.hidden)}."
                                    } else {
                                        "Nothing pinned right now."
                                    },
                                    color = paper.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = focus.turnOff) {
                                    Icon(
                                        Icons.Outlined.CenterFocusStrong,
                                        contentDescription = null,
                                        tint = paper.accentStrong,
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                    )
                                    Text("Turn off focus", color = paper.accentStrong, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            model.view == View.INBOX -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                EmptyInboxPicture()
                                Text(emptyText(model.view), color = paper.textSecondary, textAlign = TextAlign.Center)
                            }
                            else -> Text(emptyText(model.view), color = paper.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            listRows(items, frame, actions, listState, lines, rows)
            if (model.todosHidden > 0) {
                item(key = "todo-more", contentType = "todo-more") {
                    // The next page shows when this end comes into view, as on web; the button says so too.
                    LaunchedEffect(model.todoLimit) { actions.showMoreTodos() }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.animateItem(LIST_FADE, null, LIST_FADE).fillMaxWidth().padding(8.dp)) {
                        TextButton(onClick = actions.showMoreTodos) {
                            Text(moreTodosText(model.todosHidden), color = paper.accentStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (list.searchingEncrypted) {
                item(key = "encrypted-search", contentType = "encrypted-search") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        CircularProgressIndicator(color = paper.lock, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Text("Searching encrypted mail…", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                    }
                }
            }
            if (more) {
                item(key = "more", contentType = "more") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.animateItem(LIST_FADE, null, LIST_FADE).fillMaxWidth().padding(16.dp)) {
                        // Keyed by the request the next page is, not the list's length: a page that adds no
                        // new thread (the inbox's query changed and it starts over) still moves on. A page
                        // already on its way (Retry's) isn't asked for twice.
                        LaunchedEffect(list.query, list.nextPageToken, list.loading) { if (!list.loading) actions.loadMore() }
                        CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (list.error != null && rowList.rows.isNotEmpty()) {
                item(key = "more-error", contentType = "more-error") {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.animateItem(LIST_FADE, null, LIST_FADE).fillMaxWidth().padding(16.dp)) {
                        ListError(list.error.orEmpty(), onRetry = actions.loadMore)
                    }
                }
            }
            if (focus.on && focus.hidden > 0 && rowList.rows.isNotEmpty()) {
                item(key = "focus-hidden", contentType = "focus-hidden") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Focus is hiding ${hiddenText(focus.hidden)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = paper.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = focus.turnOff) {
                            Text("Show everything", color = paper.accentStrong, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The list's own scrolling, all of it, on the list state it returns: it says where it sits before each new layout,
 * keeps the row at the top of the screen in place when a layout took the one there away, and brings a row into view
 * only when the list reveals one (the keys moved focus to it). The todo being edited ([editingKey]) stays in view as
 * the keyboard comes up over the list, the list's height changes, or the row itself grows.
 */
@Composable
private fun rememberListScroll(
    list: ListState<MailRow, Todo>,
    rowKeys: Set<String>,
    itemIndex: Map<String, Int>,
    actions: RowActions,
    keyboard: KeyboardOverlap,
    editingKey: String?,
): LazyListState {
    // Where the list sat, by its first row's key (or at its very top), kept while it is out of composition: a thread
    // open over it, or another page. It comes back by that row rather than by position, as rows may have come and gone
    // meanwhile, with the focused row (the thread just closed, or a new todo started on another screen) brought into
    // view.
    val sat = rememberSaveable(saver = SatPlace.Saver) { SatPlace() }
    val listState = remember {
        val index = sat.key?.let(itemIndex::get)
        val focusIndex = list.focus?.let(itemIndex::get)
        when {
            sat.key == TOP_KEY -> LazyListState()
            focusIndex != null && index != null && focusIndex < index -> LazyListState(focusIndex)
            index != null -> LazyListState(index, sat.offset)
            else -> LazyListState()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
            if (listState.atTop()) TOP_KEY to 0 else (first?.key as? String) to listState.firstVisibleItemScrollOffset
        }.collect { (key, offset) ->
            if (key != null) {
                sat.key = key
                sat.offset = offset
            }
        }
    }
    val currentRows by rememberUpdatedState(rowKeys)
    DisposableEffect(listState, actions) {
        actions.trackAnchor {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
            (first?.key as? String)?.takeIf { it in currentRows && !listState.atTop() }?.let { ListAnchor(it, listState.firstVisibleItemScrollOffset) }
        }
        onDispose { actions.trackAnchor(null) }
    }
    // Asked for while composing the new layout, as requestScrollToItem is made for: the layout drawn next has it.
    val seen = remember { LayoutSeen() }
    if (seen.layout !== list.layout) {
        // A list at its very top stays there, so new mail above shows. Otherwise Compose keeps the
        // first row by its key; only when a layout took that row away does the row the list put in
        // its place need putting there.
        val (first, top) = Snapshot.withoutReadObservation {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }?.key to listState.atTop()
        }
        val anchor = list.anchor
        val index = anchor?.let { itemIndex[it.key] }
        if (seen.layout != null && top) {
            listState.requestScrollToItem(0, 0)
        } else if (seen.layout != null && first is String && first in seen.rows && first !in rowKeys && anchor != null && index != null) {
            listState.requestScrollToItem(index, anchor.offset)
        }
        seen.layout = list.layout
        seen.rows = rowKeys
    }
    val currentList by rememberUpdatedState(list)
    val currentIndex by rememberUpdatedState(itemIndex)
    suspend fun revealFocus() {
        val focus = currentList.focus ?: return
        currentIndex[focus]?.let { listState.reveal(it, keyboard.overlap(), heldOver = openBundleHeader(currentList, focus)) }
    }
    LaunchedEffect(listState) {
        var revealed = currentList.reveal
        // A scroll waits for the list's first layout, which the focused row is brought into.
        listState.scroll {}
        revealFocus()
        snapshotFlow { currentList.reveal }.collect { reveal ->
            if (reveal > revealed) revealFocus()
            revealed = reveal
        }
    }
    LaunchedEffect(editingKey) {
        if (editingKey != null) {
            snapshotFlow {
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == editingKey }?.size
                Triple(keyboard.overlap(), listState.layoutInfo.viewportEndOffset, size)
            }
                // Scrolled off by hand, the row isn't pulled back until something moves again.
                .filter { (_, _, size) -> size != null }
                .distinctUntilChanged()
                .collect { currentIndex[editingKey]?.let { listState.reveal(it, keyboard.overlap(), animate = false) } }
        }
    }
    return listState
}

/** Where a list sat, by its first row's key ([TOP_KEY] at its very top) and how far that row was scrolled. */
private class SatPlace(var key: String? = null, var offset: Int = 0) {
    companion object {
        /** Keeps the place across a turn of the phone and the app being stopped. */
        val Saver: Saver<SatPlace, Any> = listSaver(
            save = { listOf(it.key, it.offset) },
            restore = { saved -> SatPlace(saved[0] as String?, saved[1] as Int) },
        )
    }
}

// The saved key of a list scrolled to its very top.
private const val TOP_KEY = ""

/** How far the on-screen keyboard reaches up over the list's bottom, read without recomposing. */
private class KeyboardOverlap {
    private var ime: WindowInsets? = null
    private var density: Density? = null
    private var view: android.view.View? = null
    private var listBottom = 0f

    /** take the keyboard's insets and the window */
    @Composable
    fun Track() {
        ime = WindowInsets.ime
        density = LocalDensity.current
        view = LocalView.current
    }

    /** note where the list sits in the window */
    fun place(coordinates: LayoutCoordinates) {
        listBottom = coordinates.boundsInWindow().bottom
    }

    /** the overlap in pixels, 0 with the keyboard down */
    fun overlap(): Int {
        val keyboard = density?.let { ime?.getBottom(it) } ?: 0
        val windowHeight = view?.rootView?.height ?: return 0
        return (keyboard - (windowHeight - listBottom).toInt()).coerceAtLeast(0)
    }
}

// Whether the list is scrolled to its very top.
private fun LazyListState.atTop(): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** The layout a list last drew, and its row keys. */
private class LayoutSeen {
    /** the layout */
    var layout: Any? = null

    /** its row keys */
    var rows: Set<String> = emptySet()
}

// The key of the open bundle's header when row [key] is one of its threads or todos, whose header
// is held over the top of the list while they scroll under it.
private fun openBundleHeader(list: ListState<*, *>, key: String): String? {
    val labelId = when (val row = list.rows.firstOrNull { it.key == key }) {
        is ListRow.Thread<*> -> row.bundle
        is ListRow.Todo<*> -> row.bundle
        else -> null
    } ?: return null
    return bundleRowKey(labelId).takeIf { list.bundle?.labelId == labelId }
}

// Scroll item [index] just into view, below the held header [heldOver] (when it shows) and above a
// [keyboard] that many pixels high over the list's bottom: no move when it is already in full view.
private suspend fun LazyListState.reveal(index: Int, keyboard: Int = 0, animate: Boolean = true, heldOver: String? = null) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        if (animate) animateScrollToItem(index) else scrollToItem(index)
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val head = heldOver?.let { key -> info.visibleItemsInfo.firstOrNull { it.key == key && it.index < index } }
    val top = head?.let { maxOf(0, it.offset + it.size) } ?: 0
    val bottom = minOf(info.viewportEndOffset - info.afterContentPadding, info.viewportEndOffset - keyboard)
    val by = when {
        item.offset < top -> item.offset - top
        item.offset + item.size > bottom -> minOf(item.offset - top, item.offset + item.size - bottom)
        else -> 0
    }
    when {
        by == 0 -> Unit
        animate -> animateScrollBy(by.toFloat())
        else -> scrollBy(by.toFloat())
    }
}

/** Why a list, its next page, or a thread didn't load, and a Retry pill. */
@Composable
internal fun ListError(text: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = paper.textSecondary, textAlign = TextAlign.Center)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRetry),
        ) {
            Text(
                "Retry",
                color = paper.accentStrong,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(paper.accentSoft, CircleShape).padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

// The empty inbox's picture, in a 160 x 120 box: a half sun on a horizon line, its rays, two waves below.
private const val PICTURE_WIDTH = 160f
private const val PICTURE_HEIGHT = 120f
private const val HORIZON_Y = 88f
private const val SUN_RADIUS = 30f
private const val SUN_FILL_ALPHA = 0.2f
private const val RAY_START = 36f
private const val RAY_LENGTH = 10f
// Seven rays fanned evenly from 10° to 170° above the horizon.
private const val RAY_COUNT = 7
private const val RAY_FIRST_DEGREES = 10f
private const val RAY_LAST_DEGREES = 170f
private const val STROKE = 3f

/** The empty inbox's picture: a sun setting over a calm horizon, in theme colors (the same drawing as web's). */
@Composable
private fun EmptyInboxPicture() {
    val ink = paper.faint
    val sun = paper.accent
    Canvas(Modifier.size(PICTURE_WIDTH.dp, PICTURE_HEIGHT.dp)) {
        val unit = size.width / PICTURE_WIDTH
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = STROKE * unit
        val center = point(PICTURE_WIDTH / 2, HORIZON_Y)
        clipRect(bottom = HORIZON_Y * unit) {
            drawCircle(sun.copy(alpha = SUN_FILL_ALPHA), SUN_RADIUS * unit, center)
        }
        val step = (RAY_LAST_DEGREES - RAY_FIRST_DEGREES) / (RAY_COUNT - 1)
        for (index in 0 until RAY_COUNT) {
            val angle = (RAY_FIRST_DEGREES + index * step) * PI.toFloat() / 180f
            val direction = Offset(cos(angle), -sin(angle))
            drawLine(
                sun,
                center + direction * (RAY_START * unit),
                center + direction * ((RAY_START + RAY_LENGTH) * unit),
                stroke,
                StrokeCap.Round,
            )
        }
        drawLine(ink, point(16f, HORIZON_Y), point(144f, HORIZON_Y), stroke, StrokeCap.Round)
        drawLine(ink, point(52f, 100f), point(76f, 100f), stroke, StrokeCap.Round)
        drawLine(ink, point(86f, 108f), point(112f, 108f), stroke, StrokeCap.Round)
    }
}

/**
 * Whether each bundle's row shows its line of senders and subjects, kept outside the list's items:
 * opening a bundle swaps its row for another item, which would otherwise start without the line
 * rather than folding it away.
 */
@Stable
private class SummaryLines {
    private val states = mutableMapOf<String, MutableTransitionState<Boolean>>()

    /** the line of the bundle with this id */
    fun of(id: String, expanded: Boolean): MutableTransitionState<Boolean> = states.getOrPut(id) { MutableTransitionState(!expanded) }
}

/** What every row reads from the list and the model beside its own data. */
@Immutable
private data class RowFrame(
    /** the list */
    val list: ListState<MailRow, Todo>,
    /** what thread rows read from the model */
    val context: RowContext,
    /** whether the open bundle offers Done all */
    val canDoneAll: Boolean,
    /** whether the list is faded behind an open bundle */
    val dim: Dim,
    /** labels by id */
    val labels: Map<String, GmailLabel>,
    /** label icons by id */
    val icons: Map<String, String?>,
    /** the todo of the row with this id, shown inside it */
    val emailTodo: (String) -> EmailTodo?,
    /** whether this is the Todos view, where an email's row shows as its todo */
    val inTodos: Boolean = false,
)

/** One item of the list after its top: a row, or the open bundle's footer and the end of its header. */
private sealed interface RowItem {
    /** the item's key */
    val key: String

    /** A row, and where it sits in its card: first or last of a piece, or part of the lifted open bundle. */
    data class Of(
        /** the row */
        val row: ListRow<MailRow, Todo>,
        /** whether it starts a piece of its card */
        val first: Boolean,
        /** whether it ends a piece of its card */
        val last: Boolean,
        /** whether it is part of the open bundle */
        val raised: Boolean,
    ) : RowItem {
        override val key: String get() = row.key
    }

    /** The open bundle's footer: its settings and Done all. */
    data class End(
        /** the bundle's label */
        val labelId: String,
        /** its threads, shown or not */
        val threads: List<MailRow>,
    ) : RowItem {
        override val key: String get() = bundleEndKey(labelId)
    }

    /** Where the open bundle's sticky header stops. */
    data class Stop(
        /** the bundle's label */
        val labelId: String,
    ) : RowItem {
        override val key: String get() = bundleStopKey(labelId)
    }
}

// Whether a row is part of the open bundle, lifted out of its card.
private fun ListRow<*, *>.raised(): Boolean = when (this) {
    is ListRow.Bundle -> open
    is ListRow.Thread -> bundle != null
    is ListRow.Todo -> bundle != null
    is ListRow.More -> true
    else -> false
}

// The open bundle a row is part of, by label, or null.
private fun ListRow<*, *>.openBundle(): String? = when (this) {
    is ListRow.Bundle -> labelId.takeIf { open }
    is ListRow.Thread -> bundle
    is ListRow.Todo -> bundle
    is ListRow.More -> labelId
    else -> null
}

/** The list's items in order: its rows (todo rows only when they can draw), and the open bundle's end after its last. */
private fun rowItems(rows: List<ListRow<MailRow, Todo>>, withTodos: Boolean): List<RowItem> {
    val shown = rows.filter { withTodos || it !is ListRow.Todo }
    val items = mutableListOf<RowItem>()
    shown.forEachIndexed { index, row ->
        val before = shown.getOrNull(index - 1)
        val after = shown.getOrNull(index + 1)
        val raised = row.raised()
        items += RowItem.Of(
            row,
            first = before == null || before is ListRow.Section || before.raised(),
            last = after == null || after is ListRow.Section || after.raised(),
            raised = raised,
        )
        val labelId = row.openBundle()
        if (labelId != null && after?.openBundle() != labelId) {
            val header = shown.firstOrNull { it is ListRow.Bundle && it.labelId == labelId } as? ListRow.Bundle
            items += RowItem.End(labelId, header?.threads.orEmpty().map { it.data })
            items += RowItem.Stop(labelId)
        }
    }
    return items
}

/**
 * A row that takes focus: it holds the keyboard's focus whenever the list's focus is on it and
 * came from keys (its own highlight shows that), and tells the list when it takes focus itself
 * (Tab, or accessibility moving focus there).
 */
@Composable
private fun FocusRow(key: String, list: ListState<MailRow, Todo>, actions: RowActions, content: @Composable () -> Unit) {
    val requester = remember { FocusRequester() }
    val inputs = LocalInputModeManager.current
    val keyed = list.focus == key && list.focusBy == FocusSource.KEYBOARD
    LaunchedEffect(keyed, list.reveal) { if (keyed) requester.requestFocus() }
    Box(
        Modifier
            .focusRequester(requester)
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    val by = if (inputs.inputMode == InputMode.Keyboard) FocusSource.KEYBOARD else FocusSource.POINTER
                    actions.list(ListEvent.Focus(key, by))
                }
            },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun LazyListScope.listRows(
    items: List<RowItem>,
    frame: RowFrame,
    actions: RowActions,
    listState: LazyListState,
    lines: SummaryLines,
    todos: TodoRows?,
) {
    val list = frame.list
    val dim = frame.dim
    for (item in items) {
        when (item) {
            is RowItem.End -> item(key = item.key, contentType = "bundleEnd") {
                BundleEndWatch(listState, item.key, item.labelId, actions.bundleEndShown)
                Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) {
                    Piece(first = false, last = true, dim = dim, raised = true, part = RaisedPart.BOTTOM) {
                        BundleFooter(item.labelId, frame.labels[item.labelId]?.name ?: item.labelId, item.threads, frame.canDoneAll, actions)
                    }
                }
            }
            // The next header up pushes the open one off; this empty one ends the bundle for it.
            is RowItem.Stop -> stickyHeader(key = item.key, contentType = "bundleStop") { Spacer(Modifier.fillMaxWidth()) }
            is RowItem.Of -> when (val row = item.row) {
                is ListRow.Section -> item(key = row.key, contentType = "head") {
                    // The Todos view's headings show how many rows they hold, as on web.
                    val counted = row.section.startsWith("todos:")
                    val count = list.rows.count { it.section == row.section && it !is ListRow.Section }
                    Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) {
                        SectionHeader(
                            title = if (counted) "${row.title} · $count" else row.title,
                            onDoneAll = if (row.doneAll) ({ actions.doneSection(row.section) }) else null,
                            dim = dim,
                        )
                    }
                }
                is ListRow.Thread -> item(key = row.key, contentType = "thread") {
                    Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) {
                        if (item.raised) {
                            Piece(first = false, last = false, dim = dim, raised = true, part = RaisedPart.MIDDLE) {
                                Column {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(paper.separator))
                                    FocusRow(row.key, list, actions) {
                                        SwipeRow(
                                            row.key,
                                            row.data,
                                            frame.context,
                                            actions,
                                            emailTodo = if (todos != null) frame.emailTodo(row.id) else null,
                                        )
                                    }
                                }
                            }
                        } else {
                            Piece(item.first, item.last, dim) {
                                FocusRow(row.key, list, actions) {
                                    SwipeRow(
                                        row.key,
                                        row.data,
                                        frame.context,
                                        actions,
                                        emailTodo = if (todos != null) frame.emailTodo(row.id) else null,
                                        todoLook = frame.inTodos,
                                    )
                                }
                            }
                        }
                    }
                }
                is ListRow.Todo -> if (todos != null) {
                    item(key = row.key, contentType = "todo") {
                        val selection = TodoSelection(row.key in frame.context.selected, frame.context.selected.isNotEmpty()) {
                            actions.list(ListEvent.Pick(row.key, range = false))
                        }
                        val todoRow = @Composable {
                            FocusRow(row.key, list, actions) {
                                TodoRow(
                                    row.data,
                                    todos.context,
                                    todos.actions,
                                    selection = selection,
                                )
                            }
                        }
                        Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) {
                            // Inside the open bundle it sits among its threads, as one of them does.
                            if (item.raised) {
                                Piece(first = false, last = false, dim = dim, raised = true, part = RaisedPart.MIDDLE) {
                                    Column {
                                        Box(Modifier.fillMaxWidth().height(1.dp).background(paper.separator))
                                        todoRow()
                                    }
                                }
                            } else {
                                Piece(item.first, item.last, dim, content = todoRow)
                            }
                        }
                    }
                }
                is ListRow.Bundle -> {
                    val head = @Composable {
                        FocusRow(row.key, list, actions) {
                            val rows = row.threads.map { it.data }
                            val bundleTodos = row.todos.map { it.data }
                            BundleSwipe(row.labelId, rows, bundleTodos.size, row.open, frame.context, actions) { swiping, talkBackActions, hold ->
                                LabelGroupHead(
                                    row.key,
                                    frame.labels[row.labelId],
                                    frame.icons[row.labelId],
                                    row.labelId,
                                    rows,
                                    bundleTodos,
                                    row.items,
                                    row.open,
                                    lines.of(row.key, row.open),
                                    frame.context,
                                    actions,
                                    swiping,
                                    talkBackActions,
                                    hold,
                                )
                            }
                        }
                    }
                    // An open bundle's header stays at the top of the list while its threads scroll under it.
                    if (row.open) {
                        stickyHeader(key = row.key, contentType = "group") {
                            val held by remember(listState, row.key) { derivedStateOf { headerHeld(listState, row.key) } }
                            val shadow by animateDpAsState(if (held) RAISED_SHADOW else 0.dp, tween(DIM_MS), label = "heldShadow")
                            // Held, the room around it is filled with the page, so the threads scrolled under
                            // it end at its bottom edge rather than showing above and beside it.
                            Box(Modifier.zIndex(1f).then(if (held) Modifier.background(paper.page) else Modifier)) {
                                Piece(item.first, item.last, dim, raised = true, part = RaisedPart.TOP, shadow = shadow, content = head)
                            }
                        }
                    } else {
                        item(key = row.key, contentType = "group") {
                            Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) { Piece(item.first, item.last, dim, content = head) }
                        }
                    }
                }
                is ListRow.More -> item(key = row.key, contentType = "bundleMore") {
                    Box(Modifier.animateItem(LIST_FADE, ROW_MOVE, LIST_FADE)) {
                        Piece(first = false, last = false, dim = dim, raised = true, part = RaisedPart.MIDDLE) {
                            BundleMore(row, frame.context, actions)
                        }
                    }
                }
            }
        }
    }
}

// Reports whether open bundle [labelId]'s footer (item [id]) is on screen, or near it, while it is open.
@Composable
private fun BundleEndWatch(listState: LazyListState, id: String, labelId: String, report: (String, Boolean) -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { BUNDLE_END_MARGIN.roundToPx() }
    val shown by remember(listState, id, margin) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.key == id }?.let { it.offset + it.size <= info.viewportEndOffset + margin } == true
        }
    }
    LaunchedEffect(shown, labelId) { report(labelId, shown) }
    DisposableEffect(labelId) { onDispose { report(labelId, false) } }
}

// How near the end of an open bundle must come into view before more of it is fetched.
private val BUNDLE_END_MARGIN = 400.dp

@Composable
private fun SectionHeader(title: String, onDoneAll: (() -> Unit)?, dim: Dim) {
    val alpha by animateFloatAsState(if (dim.dimmed) DIMMED_ALPHA else 1f, tween(DIM_MS), label = "dimHead")
    Box(Modifier.graphicsLayer { this.alpha = alpha }) {
        Dimmable(dim.dimmed, dim.close) { SectionTitle(title, onDoneAll) }
    }
}

@Composable
private fun SectionTitle(title: String, onDoneAll: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 18.dp, end = 12.dp, bottom = if (onDoneAll == null) 8.dp else 0.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = paper.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (onDoneAll == null) 0.dp else 12.dp)
                .semantics { heading() },
        )
        if (onDoneAll != null) DoneAllButton(onDoneAll)
    }
}

@Composable
private fun DoneAllButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Done all", color = paper.textSecondary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** What a swipe does to one row: its label and icon. */
private enum class SwipeMove(val label: String, val icon: ImageVector) {
    DONE("Done", Icons.Rounded.Check),
    INBOX("Move to inbox", Icons.Rounded.MoveToInbox),
    SNOOZE("Snooze", Icons.Rounded.Schedule),
    WAKE("Wake now", Icons.Rounded.AlarmOff),
}

private fun swipeMove(action: ThreadSwipeAction?): SwipeMove? = when (action) {
    ThreadSwipeAction.DONE -> SwipeMove.DONE
    ThreadSwipeAction.SNOOZE -> SwipeMove.SNOOZE
    ThreadSwipeAction.WAKE -> SwipeMove.WAKE
    ThreadSwipeAction.INBOX -> SwipeMove.INBOX
    null -> null
}

/** A swipe background's soft and armed (past the threshold) fills, icon and label. */
internal data class SwipeLook(
    val soft: Color,
    val onSoft: Color,
    val armed: Color,
    val onArmed: Color,
    val icon: ImageVector?,
    val label: String,
)

@Composable
private fun swipeLook(move: SwipeMove?): SwipeLook {
    val colors = MaterialTheme.colorScheme
    return when (move) {
        // Material's secondary is done, tertiary snooze and primary the accent (Color.kt's materialColors).
        SwipeMove.DONE -> SwipeLook(paper.doneSoft, paper.done, paper.done, colors.onSecondary, move.icon, move.label)
        SwipeMove.INBOX -> SwipeLook(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary, move.icon, move.label)
        SwipeMove.SNOOZE -> SwipeLook(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary, colors.onTertiary, move.icon, move.label)
        SwipeMove.WAKE -> SwipeLook(colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.onPrimary, move.icon, move.label)
        null -> SwipeLook(Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent, null, "")
    }
}

// A row swipe needs this multiple of the base touch slop, so a drag must be clearly horizontal to beat the list's scroll.
private const val SWIPE_SLOP_MULTIPLIER = 2f

internal fun inflatedSlop(base: ViewConfiguration): ViewConfiguration =
    object : ViewConfiguration by base {
        override val touchSlop: Float get() = base.touchSlop * SWIPE_SLOP_MULTIPLIER
    }

/**
 * A row whose swipe directions do what [threadSwipeAction] says, with its email's todo ([emailTodo])
 * under its snippet, or ([todoLook], the Todos view's row) shown alone as a todo's row with a mail icon.
 */
@Composable
private fun SwipeRow(
    key: String,
    row: MailRow,
    context: RowContext,
    actions: RowActions,
    compact: Boolean = false,
    emailTodo: EmailTodo? = null,
    todoLook: Boolean = false,
) {
    val id = row.thread.id
    val spamOrTrash = Labels.SPAM in row.thread.labelIds || Labels.TRASH in row.thread.labelIds
    // While rows are selected a swipe acts on this thread alone, as it does otherwise.
    val swipeFor = { side: SwipeSide ->
        swipeMove(threadSwipeAction(side, row.status, row.thread.isInInbox, spamOrTrash, context.inSnoozed))
    }
    val rightMove = swipeFor(SwipeSide.START)
    val leftMove = swipeFor(SwipeSide.END)
    val run: (SwipeMove?) -> Unit = { move ->
        when (move) {
            SwipeMove.DONE -> actions.done(listOf(id))
            SwipeMove.INBOX -> actions.moveToInbox(id)
            SwipeMove.SNOOZE -> actions.snooze(id)
            SwipeMove.WAKE -> actions.wakeNow(id)
            null -> Unit
        }
    }
    // The moves a swipe let go with stay under the row until it settles: the action changes the thread at once (Move to
    // inbox gives it INBOX, which would turn the move under it into Done).
    SwipeFrame(
        under = rightMove to leftMove,
        startEnabled = rightMove != null,
        endEnabled = leftMove != null,
        onSwipe = { side -> run(if (side == SwipeSide.START) rightMove else leftMove) },
        modifier = Modifier.openOrigin(id),
        background = { state, (startMove, endMove) -> SwipeBackground(state, swipeLook(startMove), swipeLook(endMove)) },
    ) { swiping ->
        val moves = listOfNotNull(rightMove, leftMove).distinct()
        if (todoLook && emailTodo != null && emailTodo.text.isNotBlank()) {
            EmailTodoRow(key, row, context, moves, run, actions, emailTodo, swiping)
        } else {
            ThreadRow(key, row, context, moves, run, actions, compact, emailTodo, swiping)
        }
    }
}

/**
 * A list row's swipe, for threads, bundles and todos alike: a [SwipeToDismissBox] that takes a longer drag than a scroll
 * to start (so a list scroll wins), leaves the system's edges to Back, ticks and runs [onSwipe] with the side let go
 * toward, and springs back. [background] draws what is [under] the row while it moves, keeping what it was let go with
 * until it settles; [content] is the row, given whether it is being swiped.
 */
@Composable
internal fun <T> SwipeFrame(
    under: T,
    startEnabled: Boolean,
    endEnabled: Boolean,
    onSwipe: (SwipeSide) -> Unit,
    modifier: Modifier = Modifier,
    background: @Composable (SwipeToDismissBoxState, T) -> Unit,
    content: @Composable (swiping: Boolean) -> Unit,
) {
    var settling by remember { mutableStateOf(false) }
    var letGo by remember { mutableStateOf(under) }
    val dismissState = rememberSwipeToDismissBoxState(positionalThreshold = { distance -> commitThreshold(distance) })
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val baseViewConfig = LocalViewConfiguration.current
    val swipeViewConfig = remember(baseViewConfig) { inflatedSlop(baseViewConfig) }
    CompositionLocalProvider(LocalViewConfiguration provides swipeViewConfig) {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.systemEdgeGuard().then(modifier),
            enableDismissFromStartToEnd = startEnabled,
            enableDismissFromEndToStart = endEnabled,
            onDismiss = { value ->
                letGo = under
                settling = true
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> onSwipe(SwipeSide.START)
                    SwipeToDismissBoxValue.EndToStart -> onSwipe(SwipeSide.END)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    settleAfterSwipe(dismissState)
                    settling = false
                }
            },
            backgroundContent = {
                if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) background(dismissState, if (settling) letGo else under)
            },
        ) {
            CompositionLocalProvider(LocalViewConfiguration provides baseViewConfig) {
                content(dismissState.dismissDirection != SwipeToDismissBoxValue.Settled)
            }
        }
    }
}

/**
 * What shows under a row being swiped, as on the web: the action of the side the row moves toward, on its
 * soft color, turning to its strong color with the icon growing and a tick once letting go acts. Shown only
 * while the row is off its place ([SwipeFrame]), when its offset is known.
 */
@Composable
internal fun SwipeBackground(dismissState: SwipeToDismissBoxState, startLook: SwipeLook, endLook: SwipeLook) {
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableFloatStateOf(0f) }
    val offset = { dismissState.requireOffset() }
    val armed by remember { derivedStateOf { width > 0f && swipeProgress(offset(), width) >= 1f } }
    val towardEnd by remember { derivedStateOf { offset() >= 0f } }
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val look = if (towardEnd) startLook else endLook
    val motion = tween<Color>(ICON_GROW_MS, easing = LinearOutSlowInEasing)
    val fill by animateColorAsState(if (armed) look.armed else look.soft, motion, label = "swipeFill")
    val ink by animateColorAsState(if (armed) look.onArmed else look.onSoft, motion, label = "swipeInk")
    val iconScale by animateFloatAsState(
        if (armed) ARMED_ICON_SCALE else 1f,
        tween(ICON_GROW_MS, easing = LinearOutSlowInEasing),
        label = "swipeIcon",
    )
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.toFloat() }
            .drawBehind { drawRect(fill) }
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(if (towardEnd) Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            if (towardEnd) {
                look.icon?.let { SwipeIcon(it, ink, iconScale) }
                Text(look.label, color = ink, style = MaterialTheme.typography.labelLarge)
            } else {
                Text(look.label, color = ink, style = MaterialTheme.typography.labelLarge)
                look.icon?.let { SwipeIcon(it, ink, iconScale) }
            }
        }
    }
}

// How much a swipe's icon grows once letting go acts, and how fast its colors change.
private const val ARMED_ICON_SCALE = 1.25f
private const val ICON_GROW_MS = 150

@Composable
private fun SwipeIcon(icon: ImageVector, tint: Color, scale: Float) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).scale(scale))
}

/** The subject to show; an encrypted thread's travels inside, so it is the decrypted one once this phone opened it. */
internal fun shownSubject(thread: ThreadSummary, preview: DecryptedPreview?): String =
    when {
        thread.encrypted && thread.subject == OBSCURED_SUBJECT -> preview?.subject?.ifEmpty { null } ?: "Encrypted message"
        else -> thread.subject.ifEmpty { "(no subject)" }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    key: String,
    row: MailRow,
    context: RowContext,
    moves: List<SwipeMove>,
    onMove: (SwipeMove) -> Unit,
    actions: RowActions,
    compact: Boolean,
    emailTodo: EmailTodo?,
    swiping: Boolean = false,
) {
    val thread = row.thread
    val preview = context.previews[thread.id]
    val subject = shownSubject(thread, preview)
    val snippet = if (thread.encrypted) preview?.snippet.orEmpty() else thread.snippet
    val senders = sendersLabel(thread.senders, context.email)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val pick = rowPick(key, row, context, actions)
    val selecting = pick.selecting
    var avatar by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val avatarSize = if (compact) 32.dp else 36.dp
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) COMPACT_ROW_HEIGHT else ROW_HEIGHT)
            .threadRowFrame(key, row, pick, RowMoves(moves, onMove), actions, subject, "Open", swiping, hoverSource) { avatar }
            .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 10.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            SelectFlip(avatarSize, pick, pick.label, onPlaced = { avatar = it }) {
                Avatar(thread.senders.firstOrNull() ?: Person("", "?"), size = avatarSize)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            senders,
                            style = SENDER_TEXT,
                            fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Medium,
                            color = paper.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (thread.messageCount > 1) {
                            Text(
                                "${thread.messageCount}",
                                style = META_TEXT,
                                color = paper.faint,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (thread.isImportant) {
                            Icon(
                                Icons.AutoMirrored.Filled.LabelImportant,
                                contentDescription = "Important",
                                tint = paper.accent,
                                modifier = Modifier.padding(start = 8.dp).size(14.dp),
                            )
                        }
                        if (thread.isPinned) {
                            Icon(
                                Icons.Outlined.PushPin,
                                contentDescription = "Pinned",
                                tint = paper.accent,
                                modifier = Modifier.padding(start = 8.dp).size(14.dp),
                            )
                        }
                    }
                    val until = row.snoozeUntil
                    if (hovered && !selecting) {
                        // A mouse over the row offers its moves and pin in place of the date, as web does on hover.
                        val spam = Labels.SPAM in thread.labelIds
                        if (spam) {
                            HoverAction(Icons.Outlined.VerifiedUser, "Not spam", paper.text, paper.hover) { actions.notSpam(thread.id) }
                        }
                        for (move in moves) {
                            val (color, soft) = hoverTone(move)
                            HoverAction(move.icon, move.label, color, soft) { onMove(move) }
                        }
                        if (!spam && row.status != SnoozeStatus.SNOOZED) {
                            HoverAction(Icons.Outlined.PushPin, if (thread.isPinned) "Unpin" else "Pin", paper.accent, paper.accentSoft) {
                                actions.togglePin(thread.id)
                            }
                        }
                    } else if (row.status == SnoozeStatus.SNOOZED && until != null) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = paper.onSnoozeSoft, modifier = Modifier.size(14.dp))
                        Text(
                            formatWakeTime(until, context.now),
                            style = META_TEXT,
                            fontWeight = FontWeight.SemiBold,
                            color = paper.onSnoozeSoft,
                        )
                    } else {
                        Text(
                            formatListDate(thread.lastDate, context.now),
                            style = META_TEXT,
                            fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (thread.unread) paper.text else paper.faint,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (thread.encrypted) {
                        Icon(Icons.Outlined.Lock, contentDescription = "Encrypted", tint = paper.lock, modifier = Modifier.size(14.dp))
                    }
                    val subjectColor = if (thread.unread) paper.text else paper.textSecondary
                    val faint = paper.faint
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = if (thread.unread) FontWeight.SemiBold else FontWeight.Normal, color = subjectColor)) {
                                append(subject)
                            }
                            if (snippet.isNotEmpty() && !compact) {
                                withStyle(SpanStyle(color = faint)) { append("  $snippet") }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (emailTodo != null && emailTodo.text.isNotBlank()) {
                    val border = paper.border
                    EmailTodoLine(emailTodo, Modifier.padding(top = 2.dp).dashedTop(border).padding(top = 5.dp))
                }
                // A newer message a Gmail filter kept out of the inbox, which doesn't end a snooze.
                if (context.inSnoozed && row.status == SnoozeStatus.SNOOZED && thread.id in context.skippedInbox) {
                    Text(
                        "New message · still snoozed",
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                        maxLines = 1,
                    )
                }
                val until = row.snoozeUntil
                if (row.status == SnoozeStatus.LAPSED && until != null) {
                    SnoozedMarker(lapsedMarker(until, context.now), Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// The gap between a row's avatar column and its text.
private val ROW_GAP = 14.dp
// A thread row's least height, as on the web: a label group's shorter one, and any other.
private val COMPACT_ROW_HEIGHT = 64.dp
private val ROW_HEIGHT = 68.dp
// A thread row's sender line, and its count, date and wake time, at the web's sizes.
private val SENDER_TEXT = TextStyle(fontFamily = Geist, fontSize = 14.5.sp, lineHeight = 20.sp)
private val META_TEXT = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")

/** A 1 dp dashed line along the top edge, in [color]. */
internal fun Modifier.dashedTop(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val dash = 3.dp.toPx()
    drawLine(
        color,
        Offset(0f, stroke / 2),
        Offset(size.width, stroke / 2),
        strokeWidth = stroke,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
    )
}

/** A row lifted while it is swiped, as on the web: its shadow shows at the edge it moved off. */
internal fun Modifier.swipeRaised(swiping: Boolean): Modifier = if (swiping) shadow(RAISED_SHADOW) else this

/** Whether a thread's row is selected, and what picking it does. */
private data class RowPick(
    /** whether any rows are selected, when a tap selects rather than opens */
    val selecting: Boolean,
    /** whether this row is */
    val selected: Boolean,
    /** add it to the selection, or take it out */
    val pick: () -> Unit,
) {
    /** what picking it is called */
    val label: String get() = if (selected) "Unselect" else "Select"
}

private fun rowPick(key: String, row: MailRow, context: RowContext, actions: RowActions): RowPick =
    RowPick(context.selected.isNotEmpty(), row.thread.id in context.selected) { actions.list(ListEvent.Pick(key, range = false)) }

/** A thread row's swipe moves, which TalkBack offers as actions, and what runs one. */
private class RowMoves(val moves: List<SwipeMove>, val onMove: (SwipeMove) -> Unit)

/**
 * What a thread's row is, in the list or as an email's todo in Todos: raised while [swiping], tinted while selected, a tap
 * that opens it ([openLabel]) or picks it while rows are selected, a hold for its round menu (titled [title], around the
 * finger, or [avatar] for TalkBack), and its swipe moves and the menu's Pin and Delete as TalkBack actions.
 */
@Composable
private fun Modifier.threadRowFrame(
    key: String,
    row: MailRow,
    pick: RowPick,
    moves: RowMoves,
    actions: RowActions,
    title: String,
    openLabel: String,
    swiping: Boolean,
    hoverSource: MutableInteractionSource? = null,
    avatar: () -> LayoutCoordinates?,
): Modifier {
    val thread = row.thread
    val fill by animateColorAsState(if (pick.selected) paper.accentSoft else paper.card, tween(DIM_MS), label = "selectedFill")
    val rowMenu = LocalHoldMenu.current
    return swipeRaised(swiping)
        .background(if (swiping) paper.raised else fill)
        .then(if (hoverSource != null) Modifier.hoverable(hoverSource) else Modifier)
        .rowHighlight(hoverFill = !pick.selected)
        .clickable(onClickLabel = if (pick.selecting) pick.label else openLabel) { if (pick.selecting) pick.pick() else actions.tap(key) }
        .rowHold(rowMenu, thread.id, title, thread.labelIds, actions, avatar)
        .semantics {
            customActions = moves.moves.map { move ->
                CustomAccessibilityAction(move.label) {
                    moves.onMove(move)
                    true
                }
            } + rowHoldActions(threadActions(thread.labelIds, snoozed = false, unread = false, todo = null, hasSender = false)).map { action ->
                CustomAccessibilityAction(threadTool(action, snoozed = false).label) {
                    actions.act(thread.id, action)
                    true
                }
            }
        }
}

/**
 * A row's [front] (its avatar, or an email todo's mail icon), which selects its thread (a 48 dp tap), read as [label]:
 * it flips over to a check mark while selected.
 */
@Composable
private fun SelectFlip(size: Dp, pick: RowPick, label: String, onPlaced: (LayoutCoordinates) -> Unit, front: @Composable () -> Unit) {
    val turn by animateFloatAsState(if (pick.selected) FLIPPED_DEGREES else 0f, tween(FLIP_MS, easing = LinearOutSlowInEasing), label = "avatarFlip")
    val density = LocalDensity.current.density
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .onGloballyPositioned(onPlaced)
            .tapArea(size)
            .toggleable(
                value = pick.selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = size / 2),
                role = Role.Checkbox,
                onValueChange = { pick.pick() },
            )
            .semantics { contentDescription = label }
            .graphicsLayer {
                rotationY = turn
                cameraDistance = FLIP_CAMERA * density
            },
    ) {
        if (turn < FLIPPED_DEGREES / 2) {
            front()
        } else {
            // The back face, turned again so its check mark doesn't read mirrored.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(size).graphicsLayer { rotationY = FLIPPED_DEGREES }.background(paper.accent, CircleShape),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = paper.onAccent, modifier = Modifier.size(size * CHECK_SCALE))
            }
        }
    }
}

/** The Todos view's row for an email's todo: a mail icon and the todo's text; a tap opens the email. */
@Composable
private fun EmailTodoRow(
    key: String,
    row: MailRow,
    context: RowContext,
    moves: List<SwipeMove>,
    onMove: (SwipeMove) -> Unit,
    actions: RowActions,
    todo: EmailTodo,
    swiping: Boolean,
) {
    val pick = rowPick(key, row, context, actions)
    var avatar by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val title = todo.text.lineSequence().firstOrNull().orEmpty().ifBlank { "Todo" }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .threadRowFrame(key, row, pick, RowMoves(moves, onMove), actions, title, "Open the email", swiping) { avatar }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        SelectFlip(EMAIL_TODO_ICON, pick, "${pick.label} the email's todo", onPlaced = { avatar = it }) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(EMAIL_TODO_ICON).background(paper.accentSoft, CircleShape)) {
                Icon(Icons.Outlined.Email, contentDescription = null, tint = paper.accent, modifier = Modifier.size(18.dp))
            }
        }
        TodoText(todo.text, MaterialTheme.typography.bodyLarge, paper.text, Modifier.weight(1f).padding(vertical = 4.dp))
    }
}

private val EMAIL_TODO_ICON = 36.dp

private const val FLIPPED_DEGREES = 180f
private const val FLIP_MS = 300
private const val FLIP_CAMERA = 12f
private const val CHECK_SCALE = 0.5f

/** The amber pill on a thread whose snooze ended. */
@Composable
internal fun SnoozedMarker(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(24.dp)
            .background(paper.snoozeSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = paper.snooze, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = paper.onSnoozeSoft)
    }
}

/** A label's color as the theme shows it: Gmail's color folded into the nearest theme tone. */
@Composable
fun labelColor(label: GmailLabel?): Color = paper.labelTones.getValue(labelTone(label?.color?.backgroundColor))

/** A label's threads and todos folded into one row, drawn like a thread row; the header of the bundle when open. */
@Composable
private fun LabelGroupHead(
    key: String,
    label: GmailLabel?,
    icon: String?,
    labelId: String,
    rows: List<MailRow>,
    bundleTodos: List<Todo>,
    items: List<LayoutItem<MailRow, Todo>>,
    expanded: Boolean,
    line: MutableTransitionState<Boolean>,
    context: RowContext,
    actions: RowActions,
    swiping: Boolean,
    talkBackActions: List<CustomAccessibilityAction>,
    hold: (ThreadAction) -> Unit,
) {
    SideEffect { line.targetState = !expanded }
    val name = label?.name ?: labelId
    val rowMenu = LocalHoldMenu.current
    var mark by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val people = remember(rows, context.email) {
        rows.mapNotNull { it.thread.senders.firstOrNull() }.distinctBy { it.email }.joinToString(", ") { sendersLabel(listOf(it), context.email) }
    }
    // In the bundle's own order, newest first, todos among the emails by their time.
    val subjects = remember(items, context.previews) {
        bundleLine(
            items.map { item ->
                when (item) {
                    is LayoutEntry.Thread -> item.thread.data.thread.let { shownSubject(it, context.previews[it.id]) }
                    is LayoutEntry.Todo -> styledPlain(item.data.text)
                }
            },
        )
    }
    val unread = rows.count { it.thread.unread }
    val more = labelId in context.bundlesWithMore
    val counts = bundleCounts(rows.size, unread, more, bundleTodos.size)
    // An open bundle whose threads and todos are all gone stays until it closes, with no date to show.
    val newest = (rows.map { it.thread.lastDate } + bundleTodos.map { it.listDate(ZoneId.systemDefault()) }).maxOrNull()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        modifier = Modifier
            .fillMaxWidth()
            .swipeRaised(swiping)
            .then(if (swiping) Modifier.background(paper.raised) else Modifier)
            .rowHighlight()
            .clickable(onClickLabel = if (expanded) "Close" else "Open") { actions.list(ListEvent.Tap(key)) }
            .bundleHold(rowMenu, name, hold) { mark }
            .semantics { customActions = talkBackActions }
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Box(Modifier.onGloballyPositioned { mark = it }) { LabelMark(label, icon, 36.dp) }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    name,
                    fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = paper.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    counts.unread ?: counts.threads,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread > 0) paper.accentStrong else paper.faint,
                )
                Spacer(Modifier.weight(1f))
                if (newest != null) {
                    Text(
                        formatListDate(newest, context.now),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (unread > 0) paper.text else paper.faint,
                    )
                }
            }
            val faint = paper.faint
            val secondary = paper.textSecondary
            // Open, its threads below already show what this line sums up.
            AnimatedVisibility(
                visibleState = line,
                enter = expandVertically(tween(DIM_MS)) + fadeIn(tween(DIM_MS)),
                exit = shrinkVertically(tween(DIM_MS)) + fadeOut(tween(DIM_MS)),
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = secondary)) { append(people) }
                        if (subjects.isNotEmpty()) withStyle(SpanStyle(color = faint)) { append("  $subjects") }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * A bundle's row, swiped as a thread's row is ([bundleSwipeAction]): leftward marks every thread and
 * todo in it done, as its Done all does, rightward opens the snooze sheet for them all, the strip
 * under it saying how many, its [todoCount] todos included ("Done · 14"). An open bundle's header doesn't swipe. [content] draws the row,
 * given whether it is being swiped, the swipes (none while open) and its round menu's Pin all and
 * Delete all as TalkBack actions, and what runs a pick from that menu ([ThreadAction.PIN] or [ThreadAction.DELETE]).
 */
@Composable
private fun BundleSwipe(
    labelId: String,
    rows: List<MailRow>,
    todoCount: Int,
    open: Boolean,
    context: RowContext,
    actions: RowActions,
    content: @Composable (swiping: Boolean, talkBackActions: List<CustomAccessibilityAction>, hold: (ThreadAction) -> Unit) -> Unit,
) {
    val ids = rows.map { it.thread.id }
    val threads = bundleCounts(rows.size, 0, labelId in context.bundlesWithMore, todoCount).threads
    val run: (BundleSwipeAction?) -> Unit = { action ->
        when (action) {
            BundleSwipeAction.DONE -> actions.doneBundle(labelId, ids)
            BundleSwipeAction.SNOOZE -> actions.snoozeBundle(labelId, ids)
            null -> Unit
        }
    }
    val hold: (ThreadAction) -> Unit = { action ->
        if (action == ThreadAction.PIN) actions.pinBundle(labelId, ids) else actions.deleteBundle(labelId, ids)
    }
    val rightAction = bundleSwipeAction(SwipeSide.START, open)
    val leftAction = bundleSwipeAction(SwipeSide.END, open)
    // TalkBack can't swipe or hold; it offers the same as actions on the row: an open bundle's
    // header doesn't swipe, so only its menu's.
    val menuActions = listOf(
        CustomAccessibilityAction(BUNDLE_PIN_LABEL) {
            hold(ThreadAction.PIN)
            true
        },
        CustomAccessibilityAction(BUNDLE_DELETE_LABEL) {
            hold(ThreadAction.DELETE)
            true
        },
    )
    val talkBackActions = if (open) {
        menuActions
    } else {
        listOf(
            CustomAccessibilityAction("Done all") {
                run(BundleSwipeAction.DONE)
                true
            },
            CustomAccessibilityAction("Snooze all") {
                run(BundleSwipeAction.SNOOZE)
                true
            },
        ) + menuActions
    }
    // The strip keeps the count it was let go with while the row settles, though the threads go at once.
    SwipeFrame(
        under = threads,
        startEnabled = rightAction != null,
        endEnabled = leftAction != null,
        onSwipe = { side -> run(if (side == SwipeSide.START) rightAction else leftAction) },
        background = { state, count ->
            SwipeBackground(
                state,
                swipeLook(SwipeMove.SNOOZE).copy(label = bundleSwipeLabel(BundleSwipeAction.SNOOZE, count)),
                swipeLook(SwipeMove.DONE).copy(label = bundleSwipeLabel(BundleSwipeAction.DONE, count)),
            )
        },
    ) { swiping ->
        content(swiping, talkBackActions, hold)
    }
}

/** The row under an open bundle's threads: "Show N more" of those loaded, or Gmail's next page (loading, or Retry). */
@Composable
private fun BundleMore(row: ListRow.More, context: RowContext, actions: RowActions) {
    val labelId = row.labelId
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(paper.separator))
        when {
            row.hidden > 0 -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .rowHighlight()
                    .clickable(role = Role.Button) { actions.list(ListEvent.ShowAll) },
            ) {
                Text("Show ${row.hidden} more", color = paper.accentStrong, fontWeight = FontWeight.Medium)
            }
            labelId in context.bundleErrors && !row.loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .rowHighlight()
                    .clickable(role = Role.Button) { actions.loadMoreBundle(labelId) },
            ) {
                // Why, as on the web, before the Retry it offers.
                Text(context.bundleErrors[labelId].orEmpty(), color = paper.textSecondary)
                Text("Retry", color = paper.accentStrong, fontWeight = FontWeight.Medium)
            }
            else -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .rowHighlight()
                    .clickable(enabled = !row.loading, role = Role.Button) { actions.loadMoreBundle(labelId) },
            ) {
                Text(
                    if (row.loading) "Loading…" else "Show more",
                    color = if (row.loading) paper.faint else paper.accentStrong,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** An open bundle's footer: its label's settings and, in the inbox, Done all on its threads. */
@Composable
private fun BundleFooter(labelId: String, name: String, threads: List<MailRow>, canDoneAll: Boolean, actions: RowActions) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(paper.separator))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = { actions.labelSettings(labelId) }) {
                Icon(Icons.Outlined.Tune, contentDescription = "$name settings", tint = paper.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            if (canDoneAll) {
                DoneAllButton { actions.doneBundle(labelId, threads.filterNot { it.thread.isPinned }.map { it.thread.id }) }
            }
        }
    }
}

/** The color a row's hover action for [move] lights up in, and the light tint behind it, as web's. */
@Composable
private fun hoverTone(move: SwipeMove): Pair<Color, Color> = when (move) {
    SwipeMove.DONE -> paper.done to paper.doneSoft
    SwipeMove.SNOOZE -> paper.snooze to paper.snoozeSoft
    SwipeMove.INBOX, SwipeMove.WAKE -> paper.text to paper.hover
}

/** A small icon button a mouse hover shows on a row; hovered or pressed, its icon takes [color] on a circle of [soft]. */
@Composable
private fun HoverAction(icon: ImageVector, label: String, color: Color, soft: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit = hovered || pressed
    IconButton(
        onClick = onClick,
        interactionSource = interaction,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (lit) soft else Color.Transparent,
            contentColor = if (lit) color else paper.textSecondary,
        ),
        modifier = Modifier.size(28.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
    }
}

// The caption of the control that shows the next page of todos.
private fun moreTodosText(hidden: Int): String {
    val next = minOf(hidden, TODO_PAGE)
    return if (next == 1) "Show 1 more todo" else "Show $next more todos"
}

/**
 * A long press on an email row opens its round menu around the finger, TalkBack's long click on its
 * avatar ([avatar]), as a todo's does: [rowHoldActions] (Pin or Unpin, then Delete or Move to inbox)
 * placed by [rowHoldWedges], the email's [subject] in the pill, the pick run by [RowActions.act].
 * Inside the row's click, so a hold that opened the menu takes the finger's lift from it.
 */
internal fun Modifier.rowHold(
    menu: HoldMenuState?,
    id: String,
    subject: String,
    labelIds: List<String>,
    actions: RowActions,
    avatar: () -> LayoutCoordinates?,
): Modifier = rowMenuHold(menu, "Email actions", avatar) { center, place ->
    val offered = rowHoldActions(threadActions(labelIds, snoozed = false, unread = false, todo = null, hasSender = false))
    val items = offered.map { action ->
        val tool = threadTool(action, snoozed = false)
        HoldMenuItem(action.name, tool.label, tool.icon, holdTone(tool.tone))
    }
    HoldMenuSpec(
        center = center,
        wedges = holdWedges(items, place(offered.map { it.name })),
        pill = subject,
        url = false,
        label = "Email actions",
        onPick = { item -> actions.act(id, ThreadAction.valueOf(item.id)) },
    )
}

// What a bundle's Pin all and Delete all are called, in its round menu and to TalkBack.
private const val BUNDLE_PIN_LABEL = "Pin all"
private const val BUNDLE_DELETE_LABEL = "Delete all"

/**
 * A long press on a bundle's row, closed or the open bundle's header, opens its round menu around
 * the finger, TalkBack's long click on its label's mark ([mark]), as an email's does: Pin all and
 * Delete all placed by [rowHoldWedges], each run by [hold] ([ThreadAction.PIN] or
 * [ThreadAction.DELETE]), the bundle's [name] in the pill.
 */
private fun Modifier.bundleHold(
    menu: HoldMenuState?,
    name: String,
    hold: (ThreadAction) -> Unit,
    mark: () -> LayoutCoordinates?,
): Modifier =
    rowMenuHold(menu, "Bundle actions", mark) { center, place ->
        val items = listOf(
            HoldMenuItem(ThreadAction.PIN.name, BUNDLE_PIN_LABEL, Icons.Outlined.PushPin),
            HoldMenuItem(ThreadAction.DELETE.name, BUNDLE_DELETE_LABEL, Icons.Outlined.Delete, HoldTone.DANGER),
        )
        HoldMenuSpec(
            center = center,
            wedges = holdWedges(items, place(items.map { it.id })),
            pill = name,
            url = false,
            label = "Bundle actions",
            onPick = { item -> hold(ThreadAction.valueOf(item.id)) },
        )
    }

// A long press on a row opens the round menu [build] lays out around the finger, TalkBack's long
// click (named [label]) around [anchor]'s middle: [build] gets that point in root pixels and what
// places a menu's slots there ([rowHoldWedges]). It is read when the menu opens, so it sees the row
// as it is then.
private fun Modifier.rowMenuHold(
    menu: HoldMenuState?,
    label: String,
    anchor: () -> LayoutCoordinates?,
    build: (center: Offset, place: (List<String?>) -> List<HoldArc>) -> HoldMenuSpec,
): Modifier = composed {
    if (menu == null) return@composed this
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val findAnchor by rememberUpdatedState(anchor)
    val currentBuild by rememberUpdatedState(build)
    val open = remember(menu) {
        { sticky: Boolean, pressedAt: Offset? ->
            val dot = findAnchor()?.takeIf { it.isAttached }
            val root = dot?.findRootCoordinates()
            if (dot != null && root != null) {
                val center = pressedAt ?: dot.localToRoot(Offset(dot.size.width / 2f, dot.size.height / 2f))
                val place = { slots: List<String?> ->
                    rowHoldWedges(slots, center.x / density, center.y / density, root.size.width / density, root.size.height / density)
                }
                menu.show(currentBuild(center, place), sticky = sticky)
                true
            } else {
                false
            }
        }
    }
    this
        .onGloballyPositioned { placed = it }
        .pointerInput(menu) { holdMenuGestures(menu, haptics, claim = false, onTap = null, open = { open(false, it) }, throughChildren = true) { placed } }
        .semantics { onLongClick(label) { open(true, null) } }
}

