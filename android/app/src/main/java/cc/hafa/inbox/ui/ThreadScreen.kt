package cc.hafa.inbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelImportant
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelImportant
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.compose.ComposeKind
import cc.hafa.inbox.core.compose.ReplyParticipants
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.compose.offersReplyAll
import cc.hafa.inbox.core.compose.splitQuotedHtml
import cc.hafa.inbox.core.compose.splitQuotedText
import cc.hafa.inbox.core.filters.FilterEditor
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.CleanHtml
import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.OpenNeighbors
import cc.hafa.inbox.core.mail.PAGE_EDGE
import cc.hafa.inbox.core.mail.PageStep
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadAction
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.ThreadTodoAction
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.absoluteImages
import cc.hafa.inbox.core.mail.cleanMessageHtml
import cc.hafa.inbox.core.mail.darkenMessageHtml
import cc.hafa.inbox.core.mail.displayName
import cc.hafa.inbox.core.mail.fitWideLayouts
import cc.hafa.inbox.core.mail.formatMessageDate
import cc.hafa.inbox.core.mail.hasOwnBackground
import cc.hafa.inbox.core.mail.messageDocument
import cc.hafa.inbox.core.mail.missingPartLabel
import cc.hafa.inbox.core.mail.openNeighbors
import cc.hafa.inbox.core.mail.pageOffset
import cc.hafa.inbox.core.mail.pageStarts
import cc.hafa.inbox.core.mail.pageStep
import cc.hafa.inbox.core.mail.parseUnsubscribe
import cc.hafa.inbox.core.mail.plainTextHtml
import cc.hafa.inbox.core.mail.threadActionPlaces
import cc.hafa.inbox.core.mail.threadActions
import cc.hafa.inbox.core.mail.threadOpenState
import cc.hafa.inbox.core.mail.userLabelsOf
import cc.hafa.inbox.core.pgp.LockState
import cc.hafa.inbox.core.pgp.MessageLock
import cc.hafa.inbox.core.pgp.SignatureStatus
import cc.hafa.inbox.core.pgp.mismatchWarning
import cc.hafa.inbox.core.theme.ColorMode
import cc.hafa.inbox.core.theme.ThemePalette
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.rowEmailTodo
import cc.hafa.inbox.data.ImagePolicy
import cc.hafa.inbox.data.TodoSnapshot
import cc.hafa.inbox.mail.ActOrigin
import cc.hafa.inbox.mail.InviteCard
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.mail.ThreadItem
import cc.hafa.inbox.mail.threadItems
import cc.hafa.inbox.ui.theme.LocalDarkTheme
import cc.hafa.inbox.ui.theme.LocalThemeId
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// The gap between the open thread and the ones drawn beside it.
private val PAGE_GAP = 12.dp

// How much farther a sideways drag must go before it moves threads when it starts on an email's
// body, so a body that scrolls sideways claims it first.
private const val WEB_SLOP_MULTIPLIER = 3f

private const val PAGE_SETTLE_MS = 200

/**
 * The open thread: Back, its subject and the important marker on top, its messages with the last
 * one expanded, and its actions on the round corner button (see [threadActionPlaces]). Pulling
 * well past the top or end, starting there, closes it as Back does, with a tick as the pull crosses
 * the point where letting go closes. A sideways swipe, started away from the screen's edges, slides
 * in the thread after it in the list (leftward) or before it (rightward), which stay drawn either
 * side from the device's saved copy; [onPage] opens the one it lands on, and says whether it did.
 */
@Composable
fun ThreadScreen(
    state: MailState,
    model: MailModel,
    onSnooze: (String) -> Unit,
    onLabels: (String) -> Unit,
    onFilter: (FilterEditor) -> Unit,
    onUnlock: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    todos: TodoSnapshot = TodoSnapshot(),
    todoActions: TodoActions? = null,
    onPage: (String) -> Boolean = { false },
) {
    val id = model.openId ?: return
    val summary = model.rowSummary(id)
    val detail = model.detail?.takeIf { it.id == id }
    val threadTodos = ThreadTodos(todos, todoActions?.takeIf { todos.enabled })
    val sender = detail?.messages?.lastOrNull()?.from?.email?.takeIf { it.isNotEmpty() }
    val snoozedNow = summary != null && model.filtered(summary, model.rowStatus(id)).snoozed
    // Every action the thread takes anywhere else, so the bar, the marker and the line together have it.
    val places = threadActionPlaces(
        summary?.let {
            val todo = when {
                threadTodos.actions == null -> null
                state.emailTodoOf(model.threadsOf(id)) != null -> ThreadTodoAction.EDIT
                else -> ThreadTodoAction.ADD
            }
            threadActions(it.labelIds, snoozedNow, it.unread, todo, sender != null)
        }.orEmpty(),
    )
    val runAction: (ThreadAction) -> Unit = { action ->
        when (action) {
            ThreadAction.DONE -> state.done(listOf(id), origin = ActOrigin.THREAD)
            ThreadAction.INBOX -> state.moveToInbox(listOf(id), ActOrigin.THREAD)
            ThreadAction.SNOOZE -> onSnooze(id)
            ThreadAction.WAKE -> state.wakeNow(id)
            ThreadAction.PIN, ThreadAction.UNPIN -> state.togglePin(id, ActOrigin.THREAD)
            ThreadAction.ADD_TODO, ThreadAction.EDIT_TODO -> state.editThreadTodo(id)
            ThreadAction.LABEL -> onLabels(id)
            ThreadAction.IMPORTANT -> state.setImportant(id, true)
            ThreadAction.UNIMPORTANT -> state.setImportant(id, false)
            ThreadAction.READ -> state.setUnread(listOf(id), false, ActOrigin.THREAD)
            ThreadAction.UNREAD -> state.setUnread(listOf(id), true, ActOrigin.THREAD)
            ThreadAction.SPAM -> state.reportSpam(listOf(id), ActOrigin.THREAD)
            ThreadAction.NOT_SPAM -> state.notSpam(listOf(id), ActOrigin.THREAD)
            ThreadAction.DELETE -> state.delete(listOf(id), ActOrigin.THREAD)
            ThreadAction.UNTRASH -> state.untrash(listOf(id), ActOrigin.THREAD)
            ThreadAction.FILTER -> sender?.let { onFilter(senderFilter(it)) }
        }
    }
    ScreenBackHandler(onBack = state::close)
    val pull = rememberEdgePull(state::close)
    val neighbors = openNeighbors(model.list)
    // The saved copies of the threads either side, and of the open one until its own detail shows;
    // a key held with null has no saved copy.
    val previews = remember { mutableStateMapOf<String, ThreadDetail?>() }
    LaunchedEffect(id, neighbors) {
        val wanted = setOfNotNull(id, neighbors.previous, neighbors.next)
        previews.keys.retainAll(wanted)
        for (neighbor in listOfNotNull(neighbors.previous, neighbors.next)) {
            if (neighbor !in previews) previews[neighbor] = state.savedPreview(neighbor)
        }
    }
    val offset = remember(id) { Animatable(0f) }
    // Whether the messages have scrolled under the top bar, which then casts its shadow on them.
    var scrolledUnder by remember(id) { mutableStateOf(false) }
    val wide = remember { WebRegions() }
    var pageWidth by remember { mutableIntStateOf(0) }
    val shift = pageWidth + with(LocalDensity.current) { PAGE_GAP.toPx() }
    val subject = (detail?.subject ?: summary?.subject).orEmpty().ifEmpty { "(no subject)" }
    Box(modifier = modifier.fillMaxSize().background(paper.page)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The messages' padding already clears the navigation bar; their imePadding pads only the rest.
                .consumeWindowInsets(WindowInsets.navigationBars)
                .clipToBounds()
                .onSizeChanged { pageWidth = it.width }
                .threadPager(offset, neighbors, wide, shift) { step ->
                    (if (step == PageStep.NEXT) neighbors.next else neighbors.previous)?.let(onPage) == true
                },
        ) {
            neighbors.previous?.let { previous ->
                NeighborPage(model, previous, previews[previous], Modifier.graphicsLayer { translationX = offset.value - shift })
            }
            neighbors.next?.let { next ->
                NeighborPage(model, next, previews[next], Modifier.graphicsLayer { translationX = offset.value + shift })
            }
            Column(modifier = Modifier.fillMaxSize().background(paper.page).graphicsLayer { translationX = offset.value }) {
                ThreadTopBar(subject, places.marker, onBack = state::close, onMarker = runAction, scrolledUnder = scrolledUnder && detail != null)
                Box(Modifier.fillMaxSize().clipToBounds().nestedScroll(pull)) {
                    Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull.offset }) {
                        val preview = previews[id]
                        CompositionLocalProvider(LocalWebRegions provides wide) {
                            when {
                                detail != null -> MessageList(
                                    state,
                                    model,
                                    detail,
                                    contentPadding,
                                    onUnlock,
                                    threadTodos,
                                    MessageLine(places.line, snoozedNow, runAction),
                                    onScrolledUnder = { scrolledUnder = it },
                                )
                                model.detailError != null -> Box(
                                    contentAlignment = Alignment.TopCenter,
                                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp),
                                ) {
                                    ListError(model.detailError ?: "", state::retryDetail)
                                }
                                // A thread just swiped to shows its saved copy until it opens.
                                preview != null -> PreviewMessages(model, preview)
                                else -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 64.dp)) {
                                    CircularProgressIndicator(color = paper.accent)
                                }
                            }
                        }
                    }
                    PullIndicator(pull, "Release to close")
                }
            }
        }
        if (summary != null && places.inner.size + places.outer.size > 0) {
            CornerButton(
                label = "Thread actions",
                icon = Icons.Outlined.MoreVert,
                menu = {
                    val item = { action: ThreadAction ->
                        val tool = threadTool(action, snoozedNow)
                        HoldMenuItem(action.name, tool.label, tool.icon, holdTone(tool.tone))
                    }
                    CornerMenu(places.inner.map(item), places.outer.map(item)) { picked -> runAction(ThreadAction.valueOf(picked.id)) }
                },
                modifier = Modifier
                    .align(cornerAlignment(LocalCornerPlacement.current.side))
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

/** What the line above an open thread's messages offers besides its labels: Wake now, Filter mail like this. */
@Immutable
private data class MessageLine(
    /** the actions, in order */
    val actions: List<ThreadAction>,
    /** whether the thread is snoozed now */
    val snoozed: Boolean,
    /** run one */
    val run: (ThreadAction) -> Unit,
)

/** How an open thread's toolbar draws one action. */
internal enum class ToolTone {
    /** plain */
    PLAIN,

    /** in the done color */
    DONE,

    /** in the accent color, for a state that is on: pinned, unread */
    ON,

    /** in the danger color */
    DANGER,
}

/** An open thread's action as a button: its name, icon and tone. */
internal data class ThreadTool(val label: String, val icon: ImageVector, val tone: ToolTone = ToolTone.PLAIN)

/** How the open thread shows [action], given whether it is [snoozed]. */
internal fun threadTool(action: ThreadAction, snoozed: Boolean): ThreadTool = when (action) {
    ThreadAction.DONE -> ThreadTool("Done", Icons.Outlined.Check, ToolTone.DONE)
    ThreadAction.INBOX -> ThreadTool("Move to inbox", Icons.Outlined.MoveToInbox)
    ThreadAction.SNOOZE -> ThreadTool(if (snoozed) "Change snooze time" else "Snooze", Icons.Outlined.Schedule)
    ThreadAction.WAKE -> ThreadTool("Wake now", Icons.Outlined.AlarmOff)
    ThreadAction.PIN -> ThreadTool("Pin", Icons.Outlined.PushPin)
    ThreadAction.UNPIN -> ThreadTool("Unpin", Icons.Filled.PushPin, ToolTone.ON)
    ThreadAction.ADD_TODO -> ThreadTool("Add todo", Icons.Outlined.AddTask)
    ThreadAction.EDIT_TODO -> ThreadTool("Edit todo", Icons.Outlined.Edit)
    ThreadAction.LABEL -> ThreadTool("Label", Icons.AutoMirrored.Outlined.Label)
    ThreadAction.IMPORTANT -> ThreadTool("Mark important", Icons.AutoMirrored.Outlined.LabelImportant)
    ThreadAction.UNIMPORTANT -> ThreadTool("Mark not important", Icons.AutoMirrored.Filled.LabelImportant, ToolTone.ON)
    ThreadAction.READ -> ThreadTool("Mark read", Icons.Outlined.MarkEmailRead)
    ThreadAction.UNREAD -> ThreadTool("Mark unread", Icons.Outlined.MarkEmailUnread)
    ThreadAction.SPAM -> ThreadTool("Report spam", Icons.Outlined.Report, ToolTone.DANGER)
    ThreadAction.NOT_SPAM -> ThreadTool("Not spam", Icons.Outlined.VerifiedUser)
    ThreadAction.DELETE -> ThreadTool("Delete", Icons.Outlined.Delete, ToolTone.DANGER)
    ThreadAction.UNTRASH -> ThreadTool("Move to inbox", Icons.Outlined.MoveToInbox)
    ThreadAction.FILTER -> ThreadTool("Filter mail like this", Icons.Outlined.FilterList)
}

@Composable
private fun toneColor(tone: ToolTone): Color = when (tone) {
    ToolTone.PLAIN -> paper.textSecondary
    ToolTone.DONE -> paper.done
    ToolTone.ON -> paper.accent
    ToolTone.DANGER -> paper.danger
}

/**
 * Back, the subject in up to two lines (a tap shows all of a longer one, another cuts it again), and
 * the important marker beside it, on the card color as on the web; once the messages have scrolled
 * under it ([scrolledUnder]) it casts a soft shadow on them.
 */
@Composable
private fun ThreadTopBar(
    subject: String,
    marker: ThreadAction?,
    onBack: () -> Unit,
    onMarker: (ThreadAction) -> Unit,
    scrolledUnder: Boolean = false,
) {
    var whole by rememberSaveable(subject) { mutableStateOf(false) }
    var cut by remember(subject) { mutableStateOf(false) }
    val shadowAlpha by animateFloatAsState(if (scrolledUnder) 1f else 0f, tween(TOP_BAR_SHADOW_MS), label = "topBarShadow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .zIndex(1f)
            .fillMaxWidth()
            .dropShadow(RectangleShape) {
                // The web's 0 6px 18px -12px rgb(0 0 0 / 0.35), which shows only under the bar.
                radius = TOP_BAR_SHADOW_BLUR.toPx()
                spread = TOP_BAR_SHADOW_SPREAD.toPx()
                offset = Offset(0f, TOP_BAR_SHADOW_DROP.toPx())
                color = Color.Black
                alpha = TOP_BAR_SHADOW_ALPHA * shadowAlpha
            }
            .background(paper.card)
            .heightIn(min = 56.dp)
            .padding(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = paper.textSecondary, modifier = Modifier.size(20.dp))
        }
        Text(
            subject,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em),
            fontWeight = FontWeight.SemiBold,
            color = paper.text,
            maxLines = if (whole) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!whole) cut = it.hasVisualOverflow },
            modifier = Modifier
                .weight(1f)
                .clickable(
                    enabled = whole || cut,
                    interactionSource = null,
                    indication = null,
                    onClickLabel = if (whole) "Shorten the subject" else "Show the whole subject",
                ) { whole = !whole }
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )
        if (marker != null) {
            val tool = threadTool(marker, snoozed = false)
            IconButton(onClick = { onMarker(marker) }) {
                Icon(tool.icon, contentDescription = tool.label, tint = toneColor(tool.tone), modifier = Modifier.size(20.dp))
            }
        }
    }
}

// The top bar's shadow once messages scroll under it, as the web's, and how fast it shows.
private val TOP_BAR_SHADOW_BLUR = 18.dp
private val TOP_BAR_SHADOW_SPREAD = (-12).dp
private val TOP_BAR_SHADOW_DROP = 6.dp
private const val TOP_BAR_SHADOW_ALPHA = 0.35f
private const val TOP_BAR_SHADOW_MS = 200

/** A tone as a wedge's colors. */
internal fun holdTone(tone: ToolTone): HoldTone = when (tone) {
    ToolTone.PLAIN -> HoldTone.PLAIN
    ToolTone.DONE -> HoldTone.DONE
    ToolTone.ON -> HoldTone.ON
    ToolTone.DANGER -> HoldTone.DANGER
}

/** A small pill on the line above the messages: Wake now, Filter mail like this. */
@Composable
private fun LinePill(tool: ThreadTool, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .height(32.dp)
                .border(1.dp, paper.border, CircleShape)
                .padding(horizontal = 12.dp),
        ) {
            Icon(tool.icon, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
            Text(tool.label, style = MaterialTheme.typography.labelLarge, color = paper.text)
        }
    }
}

/**
 * Thread [id] drawn beside the open one, for a sideways swipe to slide in: its subject and its saved
 * copy (else its row's sender and snippet). It reads nothing, marks nothing read and takes no input.
 */
@Composable
private fun NeighborPage(model: MailModel, id: String, saved: ThreadDetail?, modifier: Modifier = Modifier) {
    val summary = model.rowSummary(id)
    val subject = (saved?.subject ?: summary?.subject).orEmpty().ifEmpty { "(no subject)" }
    Column(modifier = modifier.fillMaxSize().background(paper.page).clearAndSetSemantics {}) {
        val marker = summary?.let { if (Labels.IMPORTANT in it.labelIds) ThreadAction.UNIMPORTANT else ThreadAction.IMPORTANT }
        ThreadTopBar(subject, marker, onBack = {}, onMarker = {})
        // Opened below its first message, the thread has its labels scrolled off, so they aren't drawn.
        val atTop = saved == null || threadOpenState(saved.messages).scrollTo == saved.messages.firstOrNull()?.id
        if (summary != null && atTop) NeighborLabels(model, summary)
        val sender = summary?.senders?.lastOrNull()
        when {
            saved != null -> PreviewMessages(model, saved)
            sender != null -> Column(
                modifier = Modifier
                    .padding(horizontal = MESSAGE_CARD_INSET, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(paper.card),
            ) {
                LightMessage(sender, summary.snippet, model.email)
            }
        }
    }
}

/** An open thread's label chip: its color dot and name on a 24 dp pill. */
@Composable
private fun ThreadLabelChip(labelId: String, label: GmailLabel?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(24.dp)
            .background(paper.muted, CircleShape)
            .padding(horizontal = 10.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(labelColor(label), CircleShape))
        Text(label?.name ?: labelId, style = MaterialTheme.typography.labelMedium, color = paper.textSecondary)
    }
}

/** A neighbor's label chips, where the open thread has them, without taking taps. */
@Composable
private fun NeighborLabels(model: MailModel, summary: ThreadSummary) {
    val chips = userLabelsOf(summary, model.listLabels.map { label -> label.id })
    if (chips.isEmpty()) return
    val labels = model.listLabelsById
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (labelId in chips) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(48.dp)) { ThreadLabelChip(labelId, labels[labelId]) }
        }
    }
}

/** A message drawn as light text, as on the web: its sender's avatar and name, and its plain text under the name. */
@Composable
private fun LightMessage(person: Person, text: String, email: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Avatar(person, size = 36.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                displayName(person, email),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = paper.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
        }
    }
}

/**
 * A saved thread drawn cheaply, from where it opens (its first unread message, else its last): each
 * message's header, and the text of those that open expanded. No web view, images or invites.
 */
@Composable
private fun PreviewMessages(model: MailModel, saved: ThreadDetail) {
    val opening = remember(saved) { threadOpenState(saved.messages) }
    val from = saved.messages.indexOfFirst { it.id == opening.scrollTo }.coerceAtLeast(0)
    val shown = saved.messages.drop(from)
    Column(
        modifier = Modifier
            .padding(horizontal = MESSAGE_CARD_INSET, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(paper.card),
    ) {
        shown.forEachIndexed { index, message ->
            if (index > 0) HorizontalDivider(color = paper.separator)
            val open = message.id in opening.expanded
            MessageHeader(message, model, open) {}
            if (open) {
                val text = when (message.lock?.state) {
                    null, LockState.OPEN -> message.body.text ?: message.snippet
                    else -> "Encrypted message"
                }
                Text(
                    text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = paper.text,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = BODY_GUTTER, end = BODY_GUTTER, bottom = 16.dp),
                )
            }
        }
    }
}

// How many lines of an expanded message a thread drawn beside the open one shows.
private const val PREVIEW_LINES = 40

/**
 * Where the open thread's email bodies sit, so a sideways drag starting on one lets the body scroll
 * first, and which are pinch-zoomed, where a sideways drag only pans.
 */
private class WebRegions {
    private val regions = mutableMapOf<String, LayoutCoordinates>()
    private val zoomed = mutableSetOf<String>()

    fun place(id: String, coordinates: LayoutCoordinates) {
        regions[id] = coordinates
    }

    fun zoom(id: String, zoomedIn: Boolean) {
        if (zoomedIn) zoomed += id else zoomed -= id
    }

    fun remove(id: String) {
        regions.remove(id)
        zoomed -= id
    }

    /** Whether [rootPosition] is on a body still on screen. */
    fun covers(rootPosition: Offset): Boolean = regions.values.any { it.isAttached && it.boundsInRoot().contains(rootPosition) }

    /** Whether [rootPosition] is on a body still on screen that is zoomed in. */
    fun zoomedAt(rootPosition: Offset): Boolean =
        zoomed.any { id -> regions[id]?.let { it.isAttached && it.boundsInRoot().contains(rootPosition) } == true }
}

private val LocalWebRegions = staticCompositionLocalOf<WebRegions?> { null }

/** Report this email body to the open thread's swipe between threads. */
@Composable
private fun Modifier.webRegion(id: String): Modifier {
    val regions = LocalWebRegions.current ?: return this
    DisposableEffect(regions, id) { onDispose { regions.remove(id) } }
    return onGloballyPositioned { regions.place(id, it) }
}

/** Tell the open thread's swipe between threads whether email body [id] is zoomed in. */
@Composable
private fun bodyZoomReporter(id: String): (Boolean) -> Unit {
    val regions = LocalWebRegions.current
    return remember(regions, id) { { zoomedIn -> regions?.zoom(id, zoomedIn) } }
}

/**
 * A sideways drag between threads: it starts away from the screen's edges (the system's back
 * gesture area, at least [PAGE_EDGE]), locks sideways past the touch slop (more on an email body),
 * moves the pages by [offset] (see [pageOffset]), and on release slides a whole [shift] over and
 * calls [onStep], or springs back (see [pageStep]); it springs back too when [onStep] opened
 * nothing. A drag something inside takes first (a vertical scroll, an email body scrolling its own
 * wide content) is left to it, and so is any drag that starts on a pinch-zoomed body, which pans it.
 */
@Composable
private fun Modifier.threadPager(
    offset: Animatable<Float, AnimationVector1D>,
    neighbors: OpenNeighbors,
    wide: WebRegions,
    shift: Float,
    onStep: (PageStep) -> Boolean,
): Modifier {
    val insets = WindowInsets.systemGestures
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val edge = maxOf(
        with(density) { PAGE_EDGE.dp.toPx() },
        insets.getLeft(density, direction).toFloat(),
        insets.getRight(density, direction).toFloat(),
    )
    val animate = animationsOn()
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val hasPrevious = neighbors.previous != null
    val hasNext = neighbors.next != null
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.pointerInput(offset, hasPrevious, hasNext, edge, shift, animate) {
        if (!hasPrevious && !hasNext) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            if (!pageStarts(down.position.x, width, edge)) return@awaitEachGesture
            // On a zoomed body a sideways drag pans it, as a pinch-zoomed page does on the web.
            if (wide.zoomedAt(origin + down.position)) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop * if (wide.covers(origin + down.position)) WEB_SLOP_MULTIPLIER else 1f
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var locked = false
            var dx = 0f
            var released = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.size > 1 || change.isConsumed) break
                if (!change.pressed) {
                    released = true
                    break
                }
                val travel = change.position - down.position
                if (!locked) {
                    when {
                        abs(travel.x) > slop && abs(travel.x) > abs(travel.y) -> locked = true
                        abs(travel.y) > viewConfiguration.touchSlop -> break
                        else -> continue
                    }
                }
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                dx = travel.x
                scope.launch { offset.snapTo(pageOffset(dx, hasPrevious, hasNext)) }
            }
            if (!locked) return@awaitEachGesture
            val velocity = if (released) tracker.calculateVelocity().x else 0f
            val toward = if (released) {
                pageStep(dx / density.density, width / density.density, velocity / density.density, hasPrevious, hasNext)
            } else {
                null
            }
            scope.launch {
                if (toward == null) {
                    if (animate) offset.animateTo(0f, tween(PAGE_SETTLE_MS, easing = LinearOutSlowInEasing)) else offset.snapTo(0f)
                } else {
                    val target = if (toward == PageStep.NEXT) -shift else shift
                    if (animate) offset.animateTo(target, tween(PAGE_SETTLE_MS, easing = LinearOutSlowInEasing)) else offset.snapTo(target)
                    // Once the neighbor opens, this offset belongs to a thread no longer shown; a step that
                    // opened nothing (the neighbor gone) slides this thread back.
                    if (!step(toward)) {
                        if (animate) offset.animateTo(0f, tween(PAGE_SETTLE_MS, easing = LinearOutSlowInEasing)) else offset.snapTo(0f)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    state: MailState,
    model: MailModel,
    detail: ThreadDetail,
    contentPadding: PaddingValues,
    onUnlock: () -> Unit,
    todos: ThreadTodos,
    line: MessageLine,
    onScrolledUnder: (Boolean) -> Unit,
) {
    val opening = remember(detail.id) { threadOpenState(detail.messages) }
    var expanded by remember(detail.id) { mutableStateOf(opening.expanded.toSet()) }
    // Messages that arrive while the thread is open open too, as on the web: keyed on the messages, so a
    // label or read change (a new history id) leaves folded ones folded.
    val messageIds = remember(detail.messages) { detail.messages.map { it.id } }
    LaunchedEffect(messageIds) { expanded = expanded + threadOpenState(detail.messages).expanded }
    val labels = model.listLabelsById
    val summary = model.rowSummary(detail.id)
    val chips = remember(summary, model.labels) {
        summary?.let { userLabelsOf(it, model.listLabels.map { label -> label.id }) }.orEmpty()
    }
    val localDrafts by state.sending.localDrafts.collectAsStateWithLifecycle()
    val shown = remember(detail, localDrafts, model.outboxDrafts) { threadItems(detail, localDrafts, model.outboxDrafts.messageIds) }
    // In the row's thread order, as Edit todo picks it, then any other thread the open copy holds.
    val linked = remember(model.threadTodos, detail.threadIds, model.joinedThreads) {
        val rowThreads = model.threadsOf(detail.id)
        rowEmailTodo(model.threadTodos, rowThreads + (detail.threadIds - rowThreads.toSet()))
    }
    val todoActions = todos.actions
    val todosShown = todoActions != null && linked != null
    // The items above the messages.
    val leading = listOfNotNull(SUBJECT_ITEM, TODOS_ITEM.takeIf { todosShown })
    // Opens already at the first unread message (else the last); one at the top keeps the subject in view.
    val listState = remember(detail.id) {
        val target = shown.indexOfFirst { it is ThreadItem.Message && it.message.id == opening.scrollTo }
        val atTop = opening.scrollTo == detail.messages.firstOrNull()?.id || target < 0
        LazyListState(firstVisibleItemIndex = if (atTop) 0 else leading.size + target)
    }
    // The email's todo opened to edit is scrolled to.
    val editingHere = linked != null && todos.snapshot.editingId == linked.id
    LaunchedEffect(editingHere) { if (editingHere) listState.animateScrollToItem(0) }
    val under by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    LaunchedEffect(under) { onScrolledUnder(under) }
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().imePadding()) {
        item(key = SUBJECT_ITEM) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for (labelId in chips) {
                        // A 24dp chip in a 48dp tap area; tapping it opens the label's view.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = "Open label") { state.setView(View.Label(labelId)) },
                        ) {
                            ThreadLabelChip(labelId, labels[labelId])
                        }
                    }
                    val count = detail.messages.size
                    Text(
                        if (count == 1) "1 message" else "$count messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                    )
                    for (action in line.actions) LinePill(threadTool(action, line.snoozed)) { line.run(action) }
                }
            }
        }
        if (todoActions != null && linked != null) {
            item(key = TODOS_ITEM) {
                ThreadTodoCard(linked, todos, todoActions, joined = shown.isNotEmpty())
            }
        }
        itemsIndexed(shown, key = { _, item -> threadItemKey(item) }) { index, item ->
            // The messages and drafts are one card, the email's todo, when shown, at its top.
            val first = index == 0 && !todosShown
            val last = index == shown.lastIndex
            if (item !is ThreadItem.Message) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = MESSAGE_CARD_INSET)
                        .clip(messageShape(first, last))
                        .background(paper.card),
                ) {
                    if (!first) HorizontalDivider(color = paper.separator)
                    ThreadDraft(state, item)
                }
                return@itemsIndexed
            }
            val message = item.message
            val open = message.id in expanded
            Column(
                modifier = Modifier
                    .padding(horizontal = MESSAGE_CARD_INSET)
                    .clip(messageShape(first, last))
                    .background(paper.card),
            ) {
                if (!first) HorizontalDivider(color = paper.separator)
                MessageHeader(message, model, open) {
                    expanded = if (open) expanded - message.id else expanded + message.id
                }
                AnimatedVisibility(
                    visible = open,
                    enter = expandVertically(tween(EXPAND_MS)) + fadeIn(tween(EXPAND_MS)),
                    exit = shrinkVertically(tween(EXPAND_MS)) + fadeOut(tween(FADE_MS)),
                ) {
                    Column {
                        val lock = message.lock
                        if (lock == null) {
                            MessageContent(state, message)
                        } else if (lock.state == LockState.OPEN) {
                            SignatureNote(lock, displayName(message.from, model.email))
                            MessageContent(state, message)
                        } else {
                            LockedNote(lock.state, onUnlock)
                        }
                    }
                }
            }
        }
        detail.messages.lastOrNull()?.let { last ->
            item(key = "reply") {
                val senders by state.sending.senders.collectAsStateWithLifecycle()
                val replyAll = offersReplyAll(ReplyParticipants(listOf(last.from), last.replyTo, last.to, last.cc), senders.map { it.email })
                // A joined row's reply goes in the newest message's own Gmail thread.
                val replyThread = detail.threadOf(last)
                ReplyButtons(
                    replyAll = replyAll,
                    onOpen = { kind -> state.sending.answer(kind, replyThread, last, rowThreads = detail.threadIds) },
                )
            }
        }
    }
}

/** What the open thread's todo area needs: the todos' state, their actions while todos show, and whether a new one is being added. */
@Immutable
private data class ThreadTodos(
    /** the todos and which one is being edited */
    val snapshot: TodoSnapshot,
    /** what a todo row can ask for; null while todos are off */
    val actions: TodoActions?,
)

/** The open email's todo, under its subject: the top of the messages' card when [joined] to it, as on web. */
@Composable
private fun ThreadTodoCard(todo: EmailTodo, todos: ThreadTodos, actions: TodoActions, joined: Boolean) {
    val snapshot = todos.snapshot
    Column(
        modifier = Modifier
            .padding(horizontal = MESSAGE_CARD_INSET)
            .fillMaxWidth()
            .clip(messageShape(first = true, last = !joined))
            .background(paper.card),
    ) {
        key(todo.id) {
            EmailTodoLine(
                todo,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                editing = snapshot.editingId == todo.id,
                tagCounts = snapshot.tagCounts,
                actions = actions,
            )
        }
    }
}

// The keys of the items above the messages: the subject line, and the email's todo.
private const val SUBJECT_ITEM = "subject"
private const val TODOS_ITEM = "todos"

private fun threadItemKey(item: ThreadItem): String = when (item) {
    is ThreadItem.Message -> item.message.id
    is ThreadItem.Draft -> "draft:${item.message.id}"
    is ThreadItem.Local -> "local:${item.draft.draft.localId}"
}

/** A draft in the thread, or a message being sent from this device: a row of the messages' card. */
@Composable
private fun ThreadDraft(state: MailState, item: ThreadItem) {
    when (item) {
        is ThreadItem.Draft -> DraftRow(item.message.snippet, sending = false) { state.sending.openDraftMessage(item.message.id) }
        is ThreadItem.Local -> DraftRow(item.draft.snippet, item.sending) { state.sending.openLocalDraft(item.draft.draft.localId) }
        is ThreadItem.Message -> Unit
    }
}

@Composable
private fun DraftRow(snippet: String, sending: Boolean, onEdit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = !sending, onClickLabel = "Edit draft", onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Lines the word up with the senders' names past their 36 dp avatars.
        Spacer(Modifier.width(36.dp))
        Text(
            if (sending) "Sending…" else "Draft",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (sending) paper.textSecondary else paper.snooze,
        )
        Text(
            if (sending) snippet else snippet.ifEmpty { "(empty)" },
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!sending) Icon(Icons.Outlined.Edit, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The answers at the foot of a thread, each opening the full composer: Reply, Reply all when the
 * last message reached more than one other person, and Forward.
 */
@Composable
private fun ReplyButtons(replyAll: Boolean, onOpen: (ComposeKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
    ) {
        ReplyButton(Icons.AutoMirrored.Outlined.Reply, "Reply") { onOpen(ComposeKind.REPLY) }
        if (replyAll) ReplyButton(Icons.AutoMirrored.Outlined.ReplyAll, "Reply all") { onOpen(ComposeKind.REPLY_ALL) }
        ReplyButton(Icons.AutoMirrored.Outlined.Forward, "Forward") { onOpen(ComposeKind.FORWARD) }
    }
}

@Composable
private fun ReplyButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .border(1.dp, paper.border, CircleShape)
            .background(paper.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = paper.text)
    }
}

private fun messageShape(first: Boolean, last: Boolean): RoundedCornerShape {
    val top = if (first) 12.dp else 0.dp
    val bottom = if (last) 12.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// From this window width the sender's address sits beside the name, as at web's `sm` width.
private val WIDE_HEADER = 640.dp

@Composable
private fun MessageHeader(message: MessageView, model: MailModel, open: Boolean, onToggle: () -> Unit) {
    val sender = displayName(message.from, model.email)
    val address = message.from.email.takeIf { open && it.isNotEmpty() && it != sender }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val inlineAddress = windowWidth >= WIDE_HEADER
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(message.from, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    sender,
                    fontWeight = if (message.unread) FontWeight.SemiBold else FontWeight.Medium,
                    color = paper.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = address == null || !inlineAddress),
                )
                if (address != null && inlineAddress) {
                    Text(
                        address,
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (message.lock != null) {
                    Icon(Icons.Outlined.Lock, contentDescription = "Encrypted", tint = paper.lock, modifier = Modifier.size(14.dp))
                }
                Text(formatMessageDate(message.date, model.now), style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
            }
            val bcc = if (message.bcc.isEmpty()) "" else ", bcc: ${recipientsLine(message.bcc, model.email)}"
            val snippet = when (message.lock?.state) {
                null, LockState.OPEN -> message.snippet
                else -> "Encrypted message"
            }
            val secondLine = if (open) "to ${recipientsLine(message.to + message.cc, model.email)}$bcc" else snippet
            // On a phone the address gets its own line, so a long name and address don't both cut short.
            if (address != null && !inlineAddress) {
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                secondLine,
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An open encrypted message's signature: a check when it matches, a warning when it doesn't. */
@Composable
private fun SignatureNote(lock: MessageLock, sender: String) {
    val (icon, text, color) = when (lock.signature) {
        SignatureStatus.VALID.wire -> Triple(Icons.Outlined.VerifiedUser, "Encrypted · Signed by $sender", paper.lock)
        SignatureStatus.INVALID.wire -> Triple(Icons.Outlined.ErrorOutline, "The signature doesn't match the key we have for $sender", paper.snooze)
        else -> Triple(Icons.Outlined.Lock, "Encrypted", paper.lock)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
    mismatchWarning(lock)?.let { warning ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = paper.snooze, modifier = Modifier.size(14.dp))
            Text(warning, style = MaterialTheme.typography.bodySmall, color = paper.snooze)
        }
    }
}

/** An encrypted message this phone can't show: why, and the way to unlock when that's the reason. */
@Composable
private fun LockedNote(state: LockState, onUnlock: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = paper.lock, modifier = Modifier.size(16.dp))
            Text(
                when (state) {
                    LockState.WRONG_KEY -> "Can't open: encrypted to another key"
                    LockState.MALFORMED -> "Can't open this encrypted message"
                    else -> "This message is encrypted. Unlock encrypted mail to read it."
                },
                color = paper.text,
            )
        }
        if (state == LockState.LOCKED) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onUnlock)
                    .padding(end = 8.dp),
            ) {
                Text("Unlock encrypted mail", color = paper.accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun recipientsLine(people: List<Person>, selfEmail: String): String =
    people.joinToString(", ") { displayName(it, selfEmail) }.ifEmpty { "undisclosed recipients" }

@Composable
private fun MessageContent(state: MailState, message: MessageView) {
    val account = state.sending.account
    val settings by account.settings.collectAsStateWithLifecycle()
    var showImages by remember(message.id) { mutableStateOf(false) }
    var cidUrls by remember(message.id) { mutableStateOf(emptyMap<String, String>()) }
    // Inline images that couldn't be fetched offline, by content id, to their placeholder's text.
    var cidMissing by remember(message.id) { mutableStateOf(emptyMap<String, String>()) }
    val html = message.body.html
    val offline by state.offline.collectAsStateWithLifecycle()
    // Runs again when the app comes back online, so images missed offline load then.
    LaunchedEffect(message.id, offline) {
        if (html != null && message.body.inline.isNotEmpty()) {
            val urls = mutableMapOf<String, String>()
            val missing = mutableMapOf<String, String>()
            for (part in message.body.inline) {
                try {
                    state.inlineDataUrl(message.id, part)?.let { urls[part.contentId] = it }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    missing[part.contentId] = missingPartLabel(part.filename, part.size)
                }
            }
            cidUrls = urls
            cidMissing = missing
        }
    }
    var invite by remember(message.id) { mutableStateOf<InviteCard?>(null) }
    LaunchedEffect(message.id) { invite = state.invites.load(message) }
    var showQuote by remember(message.id) { mutableStateOf(false) }
    val text = message.body.text ?: message.snippet
    // The last body stays up while the next one is worked out, so toggling the quote doesn't blank the view.
    var prepared by remember(message.id) { mutableStateOf<PreparedBody?>(null) }
    LaunchedEffect(html, text, showQuote, cidUrls, cidMissing) {
        prepared = withContext(Dispatchers.Default) { prepareBody(html, text, showQuote, cidUrls, cidMissing) }
    }
    val hasQuote = prepared?.hasQuote == true
    val clean = prepared?.clean
    val wantImages = showImages || settings.imagePolicy == ImagePolicy.ALWAYS || addressKey(message.from.email) in settings.imageSenders
    val imagesShown = wantImages && clean?.hasRemoteImages == true
    var document by remember(message.id) { mutableStateOf<String?>(null) }
    // Plain mail sits straight on the card; mail with its own background keeps a rounded box.
    var plain by remember(message.id) { mutableStateOf(true) }
    val themeId = LocalThemeId.current
    val dark = LocalDarkTheme.current
    // The body's width in CSS pixels (dp), which fixed-width mail is narrowed to; 0 until measured.
    var bodyWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(prepared, imagesShown, themeId, dark, bodyWidth) {
        val body = prepared ?: return@LaunchedEffect
        if (bodyWidth == 0) return@LaunchedEffect
        val (shownPlain, shown) = withContext(Dispatchers.Default) {
            val cleanHtml = if (body.clean != null && imagesShown) absoluteImages(body.clean.html) else body.html
            val bodyHtml = if (body.clean != null) fitWideLayouts(cleanHtml, bodyWidth) else cleanHtml
            val isPlain = !hasOwnBackground(bodyHtml)
            val shownHtml = if (dark) {
                darkenMessageHtml(bodyHtml, ThemePalette.darkPalette(themeId), body.clean?.colorSchemes.orEmpty(), isPlain)
            } else {
                bodyHtml
            }
            val mode = if (dark) ColorMode.DARK else ColorMode.LIGHT
            isPlain to messageDocument(
                shownHtml,
                imagesShown,
                ThemePalette.emailStyle(themeId, mode),
                isPlain,
                ThemePalette.hexColors(themeId, mode).getValue("surface-muted"),
            )
        }
        plain = shownPlain
        document = shown
    }
    val hasUnsubscribe = remember(message.id) { parseUnsubscribe(message.listUnsubscribe, message.listUnsubscribePost).preferred != null }
    // The body fades in once the web view has drawn it, and the card grows to it smoothly.
    var bodyShown by remember(message.id) { mutableStateOf(false) }
    val bodyAlpha by animateFloatAsState(if (bodyShown) 1f else 0f, tween(FADE_MS), label = "bodyAlpha")
    Column(
        modifier = Modifier
            .animateContentSize(tween(EXPAND_MS))
            .padding(start = BODY_GUTTER, end = BODY_GUTTER, bottom = 16.dp)
            .onSizeChanged { size -> bodyWidth = with(density) { size.width.toDp().value.toInt() } },
    ) {
        if (hasUnsubscribe) UnsubscribeControl(state, message)
        invite?.let { card ->
            InviteCardView(card, state.invites.canAnswer(card), onError = state::showNotice) { partstat ->
                invite = state.invites.answer(card, message, partstat)
            }
        }
        if (clean?.hasRemoteImages == true && !imagesShown) {
            ImagesBar(
                onShow = { showImages = true },
                onAlways = { account.setImageSender(message.from.email, true) },
            )
        }
        val shown = document
        if (shown == null) {
            // Room held for the body while it is worked out, so the card doesn't jump twice.
            Box(modifier = Modifier.fillMaxWidth().height(BODY_PLACEHOLDER_HEIGHT))
        } else {
            MessageWebView(
                shown,
                imagesShown,
                onLoaded = { bodyShown = true },
                onZoom = bodyZoomReporter(message.id),
                modifier = Modifier
                    .webRegion(message.id)
                    .graphicsLayer { alpha = bodyAlpha }
                    .then(if (plain) Modifier else Modifier.clip(RoundedCornerShape(16.dp))),
            )
        }
        if (hasQuote) QuoteToggle(showQuote) { showQuote = !showQuote }
        if (message.body.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                message.body.attachments.forEachIndexed { index, attachment -> AttachmentCard(state, message.id, index, attachment) }
            }
        }
    }
}

/** A message body ready to show: cleaned HTML when it has some, else its text as HTML. */
private data class PreparedBody(
    /** the cleaned HTML part; null for a text-only message */
    val clean: CleanHtml?,
    /** what the view shows when images stay hidden */
    val html: String,
    /** whether a quoted earlier message was split off */
    val hasQuote: Boolean,
)

private fun prepareBody(
    html: String?,
    text: String,
    showQuote: Boolean,
    cidUrls: Map<String, String>,
    cidMissing: Map<String, String>,
): PreparedBody =
    if (html != null) {
        val split = splitQuotedHtml(html)
        val clean = cleanMessageHtml(if (showQuote) html else split.body, cidUrls, cidMissing)
        PreparedBody(clean, clean.html, split.quote != null)
    } else {
        val split = splitQuotedText(text)
        PreparedBody(null, plainTextHtml(if (showQuote) text else split.body), split.quote != null)
    }

// How long a message takes to open or close, and to fade in; as on web.
private const val EXPAND_MS = 220
private const val FADE_MS = 150
private val BODY_PLACEHOLDER_HEIGHT = 48.dp
// A message card's gap to the screen's sides, and its body's inside the card: together the body
// text's gutter, about 12dp, so wide mail gets most of a phone's width.
private val MESSAGE_CARD_INSET = 4.dp
private val BODY_GUTTER = 8.dp

