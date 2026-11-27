package cc.hafa.inbox.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.auth.AuthManager
import cc.hafa.inbox.core.address.AddressNoticeState
import cc.hafa.inbox.core.address.addressNoticeState
import cc.hafa.inbox.core.address.addressNoticeText
import cc.hafa.inbox.core.compose.ComposePark
import cc.hafa.inbox.core.compose.InlineStyle
import cc.hafa.inbox.core.contacts.Contact
import cc.hafa.inbox.core.filters.FilterEditor
import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.compose.ComposePrefill
import cc.hafa.inbox.core.mail.ActSource
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.SelectedThread
import cc.hafa.inbox.core.mail.SelectedTodo
import cc.hafa.inbox.core.mail.SetAction
import cc.hafa.inbox.core.mail.TargetSource
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.viewFromKey
import cc.hafa.inbox.core.mail.viewsAfter
import cc.hafa.inbox.core.mail.allSelected
import cc.hafa.inbox.core.mail.clearsSelection
import cc.hafa.inbox.core.mail.threadRowKey
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.mail.rowTodoId
import cc.hafa.inbox.core.mail.selectableKeys
import cc.hafa.inbox.core.mail.ThreadAction
import cc.hafa.inbox.core.mail.selectionActions
import cc.hafa.inbox.core.mail.selectionInboxPlan
import cc.hafa.inbox.core.mail.selectionPlan
import cc.hafa.inbox.core.mail.signOutWarning
import cc.hafa.inbox.core.mail.todoSelectionPlan
import cc.hafa.inbox.core.search.searchPlan
import cc.hafa.inbox.core.send.outboxShownAt
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.data.TodoSnapshot
import cc.hafa.inbox.mail.ActOrigin
import cc.hafa.inbox.mail.LauncherRequests
import cc.hafa.inbox.mail.LauncherShortcut
import cc.hafa.inbox.mail.ListCommands
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.mail.SendNotice
import cc.hafa.inbox.mail.selectedThreads
import cc.hafa.inbox.notify.NOTIFY_PREFS
import cc.hafa.inbox.notify.Notifier
import cc.hafa.inbox.ui.theme.paper
import cc.hafa.inbox.ui.theme.rowHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WAKE_INTERVAL_MS = 60_000L
private const val ASKED_KEY = "asked"

private fun viewTitle(model: MailModel): String = when (val view = model.view) {
    is View.Fixed -> view.box.title
    is View.Label -> model.listLabelsById[view.labelId]?.name ?: "Label"
    is View.Search -> view.query
    View.Todos -> "Todos"
}

private const val SCREEN_IN_MS = 220
private const val SCREEN_OUT_MS = 140
private const val BAR_IN_MS = 150
private const val BAR_OUT_MS = 100
// The floating buttons slide away and back over this long, once a scroll moves more than the slop (in pixels).
private const val BUTTONS_MS = 200
private const val BUTTONS_SCROLL_SLOP = 2f

// While Compose shows, the undo and sending bars stop short of it (16 dp margin, 56 dp button, 12 dp gap)
// on both sides, so they stay centered, and sit centered on its line (a 48 dp bar beside a 56 dp button 16 dp up).
private val BAR_END_GAP = 84.dp
// Without Compose on screen they only keep the screen's margin.
private val BAR_SIDE_GAP = 16.dp
private val BAR_BOTTOM_GAP = 20.dp
// Round corner buttons keep this far from the screen's bottom and sides, and from the selection bar; the undo bar
// sits above an action button by its height and the gap again.
private val CORNER_GAP = 16.dp
private val CORNER_ABOVE_BAR = 12.dp
private val CORNER_STACK = CORNER_BUTTON + 12.dp
// The undo bar's height, and New todo's tap square stacked over Compose.
private val UNDO_BAR_HEIGHT = 48.dp
private val NEW_TODO_SIZE = 48.dp
// The end of a list scrolls clear of Compose by the corner's gap; the end of a thread, of its corner button and the
// undo bar above it.
private val LIST_END_CLEAR = CORNER_GAP + CORNER_BUTTON + CORNER_GAP
private val THREAD_END_CLEAR = CORNER_GAP + CORNER_STACK + UNDO_BAR_HEIGHT + CORNER_ABOVE_BAR

/**
 * Screens besides mail lists and threads, with how deep each sits (deeper ones slide in from the right) and the view
 * key the web gives it, for the views Back returns through (the filter editor is part of Filters, and the signature
 * of General, as on the web).
 */
private enum class Page(val depth: Int, val view: String?) {
    MAIL(0, null),
    OUTBOX(0, "outbox"),
    SETTINGS(1, "settings:general"),
    ADDRESS(2, "settings:addresses"),
    SIGNATURE(2, "settings:general"),
    FILTERS(2, "settings:filters"),
    FILTER_EDITOR(3, "settings:filters"),
    ENCRYPTION(2, "settings:encryption"),
    LABELS(2, "settings:labels"),
}

/** The views shown, oldest first, by key, as last drawn (see [viewsAfter]). */
private class ViewTrail(var views: List<String> = emptyList()) {
    companion object {
        /** Keeps the views across a turn of the phone and the app being stopped. */
        val Saver: Saver<ViewTrail, ArrayList<String>> = Saver(save = { ArrayList(it.views) }, restore = { ViewTrail(it) })
    }
}

// The page a view key names; mail views are the mail page's.
private fun pageFor(view: String?): Page = Page.entries.firstOrNull { it.view != null && it.view == view } ?: Page.MAIL

// Deeper pages slide in from the right and back out to it; pages side by side (mail and Outbox) fade,
// rising a little. The system's animation scale shortens or skips both.
private fun pageTransition(from: Page, to: Page): ContentTransform = when {
    to.depth > from.depth ->
        slideInHorizontally(tween(SCREEN_IN_MS, easing = OpenEasing)) { it } togetherWith
            (slideOutHorizontally(tween(SCREEN_IN_MS, easing = OpenEasing)) { -it / 4 } + fadeOut(tween(SCREEN_IN_MS)))
    to.depth < from.depth ->
        ((slideInHorizontally(tween(SCREEN_IN_MS, easing = OpenEasing)) { -it / 4 } + fadeIn(tween(SCREEN_IN_MS))) togetherWith
            slideOutHorizontally(tween(SCREEN_IN_MS, easing = OpenEasing)) { it }).apply { targetContentZIndex = -1f }
    else ->
        (fadeIn(tween(SCREEN_IN_MS)) + slideInVertically(tween(SCREEN_IN_MS)) { it / 32 }) togetherWith fadeOut(tween(SCREEN_OUT_MS))
}

// Which bar tops the list.
private enum class ListBar { TOP, SEARCH }

// Bars swap with a quick fade, the new one dropping in a little; their own size changes stay instant.
private fun barTransition(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(BAR_IN_MS)) + slideInVertically(tween(BAR_IN_MS)) { -it / 6 },
    initialContentExit = fadeOut(tween(BAR_OUT_MS)),
    sizeTransform = SizeTransform(clip = false) { _, _ -> snap() },
)

// Where Cloudflare's confirmation mail turns up, for confirming an address by hand.
private const val CONFIRMATION_SEARCH = "from:cloudflare.com in:anywhere newer_than:2d"

/**
 * The signed-in app: menu drawer, plain top bar, the current list or open thread, the Outbox
 * view and settings, the full-screen composer over them, and the undo and sending bars. A
 * [launcher] shortcut waiting is taken once these show.
 */
@Composable
fun MailScreen(state: MailState, authManager: AuthManager, launcher: LauncherRequests? = null) {
    val model by state.model.collectAsStateWithLifecycle()
    val undo by state.undo.collectAsStateWithLifecycle()
    val stats by state.snoozeStats.stats.collectAsStateWithLifecycle()
    val todos by state.todos.snapshot.collectAsStateWithLifecycle()
    val sending = state.sending
    val localOnly by state.localOnly.collectAsStateWithLifecycle()
    val composer by sending.composer.collectAsStateWithLifecycle()
    val parked by sending.parked.collectAsStateWithLifecycle()
    // A parked composer's strip takes the bottom of the screen; everything else sits above it.
    val stripShown = composer != null && parked
    val stripSpace = if (stripShown) {
        PaddingValues(bottom = ComposePark.STRIP_HEIGHT.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    } else {
        PaddingValues()
    }
    val notice by sending.notice.collectAsStateWithLifecycle()
    val outboxItems by sending.outboxItems.collectAsStateWithLifecycle()
    val photoUrls by sending.contacts.photos.collectAsStateWithLifecycle()
    val failedPhotos by sending.contacts.failedPhotos.collectAsStateWithLifecycle()
    val photos = remember(photoUrls, failedPhotos) { ContactPhotos(photoUrls, failedPhotos, sending.contacts::photoFailed) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Each view keeps its own scroll position while a thread is open and across rotation.
    val listStates = rememberSaveableStateHolder()
    // A tap on the view's name scrolls the list shown back to its top.
    val topRequests = remember { TopRequests() }
    val scope = rememberCoroutineScope()
    // What sign-out would lose, while it asks first.
    var signOutQuestion by remember { mutableStateOf<String?>(null) }

    fun askSignOut() {
        scope.launch {
            val (changes, drafts) = runSuspendCatching { state.unsyncedWork() }.getOrDefault(0 to 0)
            val warning = signOutWarning(changes, drafts)
            if (warning == null) authManager.signOut() else signOutQuestion = warning
        }
    }
    var snoozing by rememberSaveable { mutableStateOf<String?>(null) }
    // The bundle (label id) the snooze sheet acts on, and the threads it showed.
    var snoozingBundle by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozingBundleThreads by rememberSaveable(stateSaver = jsonSaver<List<String>>()) { mutableStateOf(emptyList()) }
    var snoozingTodo by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(Page.MAIL) }
    // The view an empty search bar was opened over; showing any other view (a label chip in a result) closes it.
    var searchBarOver by rememberSaveable { mutableStateOf<String?>(null) }
    // The last move a sideways swipe made from one open thread to another, whose new thread shows at once rather
    // than growing from its row.
    var paged by remember { mutableStateOf<Pair<String, String>?>(null) }
    var labeling by rememberSaveable { mutableStateOf<String?>(null) }
    // The selection bar's sheet that is open: Snooze or Label.
    var selectionSheet by rememberSaveable { mutableStateOf<SetAction?>(null) }
    val clearSelection = {
        state.dispatch(ListEvent.ClearSelection)
        selectionSheet = null
    }
    var labelSettingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Where a scroll down the list tucked the floating buttons away (its view, page and open thread): a scroll up
    // brings them back, and they show again on any other view, page or thread.
    var buttonsTuckedAt by remember { mutableStateOf<Any?>(null) }
    val buttonsPlace = Triple(model.view, page, model.openId)
    val currentButtonsPlace by rememberUpdatedState(buttonsPlace)
    val buttonsShown = buttonsTuckedAt != buttonsPlace
    val buttonsScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -BUTTONS_SCROLL_SLOP) {
                    buttonsTuckedAt = currentButtonsPlace
                } else if (consumed.y > BUTTONS_SCROLL_SLOP) {
                    buttonsTuckedAt = null
                }
                return Offset.Zero
            }
        }
    }
    // The address notice the user closed; it stays closed until its wording (the problem) changes.
    var dismissedNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFilter by rememberSaveable(stateSaver = jsonSaver<GmailFilter?>()) { mutableStateOf(null) }
    var filterStart by rememberSaveable(stateSaver = jsonSaver<FilterEditor?>()) { mutableStateOf(null) }
    val contactList by sending.contacts.contacts.collectAsStateWithLifecycle()
    // Counts opens, so an editor reopened while the last one still slides away starts fresh.
    var filterOpens by rememberSaveable { mutableIntStateOf(0) }
    val openFilter = { filter: GmailFilter?, start: FilterEditor? ->
        editingFilter = filter
        filterStart = start
        filterOpens++
        page = Page.FILTER_EDITOR
    }
    val runSearch = { query: String ->
        page = Page.MAIL
        if (searchPlan(query).outbox) {
            page = Page.OUTBOX
            searchBarOver = null
            state.close()
        } else {
            state.search(query)
        }
    }
    // Bumped when a held-up send reaches the moment the Outbox should be listed.
    var outboxTick by remember { mutableIntStateOf(0) }
    val outboxNow = remember(outboxItems, outboxTick) { System.currentTimeMillis() }
    val outboxAt = outboxShownAt(outboxItems.map { it.doc }, outboxNow)
    LaunchedEffect(outboxAt, outboxNow) {
        if (outboxAt != null && outboxAt > outboxNow) {
            delay(outboxAt - outboxNow)
            outboxTick++
        }
    }
    val showOutbox = page == Page.OUTBOX || outboxAt == outboxNow
    val account by sending.account.settings.collectAsStateWithLifecycle()
    val addressSetup = account.addressSetup?.takeIf { account.address != null }
    val addressNoticeState = addressSetup?.let { addressNoticeState(it.status, it.errorCode, it.updatedAt, model.now) }
    val addressNotice = addressSetup?.takeIf { addressNoticeState == AddressNoticeState.ERROR }?.let { setup ->
        addressNoticeText(setup.status, setup.errorCode, setup.updatedAt, model.now)
    }
    // Set by the notice's Fix it, so Addresses points at its Try again.
    var pointAtRetry by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as InboxApplication
    val focusStore = app.focus
    val focusOn by focusStore.on.collectAsStateWithLifecycle()
    val cornerStore = app.cornerSide
    val cornerSide by cornerStore.side.collectAsStateWithLifecycle()
    val cornerPlacement = remember(cornerSide) { CornerPlacement(cornerSide, cornerStore::set) }
    // Focus mode applies to the inbox only; other lists are ones the user went looking in.
    val focusing = focusOn && model.view == View.INBOX
    val focused = model.shownSections
    val shownIds = remember(model.list.rows) { model.list.selectableKeys() }
    val selection = model.list.selection
    LaunchedEffect(account.loaded, account.theme) {
        if (account.loaded) app.theme.show(account.theme)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rowActions = remember(state, sending) {
        RowActions(
            list = state::dispatch,
            tap = { key -> state.dispatch(ListEvent.Tap(key)) },
            done = { state.done(it) },
            moveToInbox = { state.moveToInbox(listOf(it)) },
            notSpam = { state.notSpam(listOf(it)) },
            showMoreTodos = state::showMoreTodos,
            snooze = { snoozing = it },
            togglePin = { state.togglePin(it) },
            wakeNow = state::wakeNow,
            trackAnchor = state::trackAnchor,
            loadMoreBundle = state::loadMoreBundle,
            bundleEndShown = state::noteBundleEnd,
            doneBundle = state::doneBundle,
            pinBundle = state::pinBundle,
            unpinBundle = state::unpinBundle,
            deleteBundle = state::deleteBundle,
            snoozeBundle = { labelId, threads ->
                snoozingBundleThreads = threads
                snoozingBundle = labelId
            },
            doneSection = state::doneSection,
            labelSettings = { labelSettingsFor = it },
            loadMore = state::loadMore,
            refresh = { state.refreshAndWait() },
            act = { id, action ->
                when (action) {
                    ThreadAction.DONE -> state.done(listOf(id))
                    ThreadAction.INBOX -> state.moveToInbox(listOf(id))
                    ThreadAction.SNOOZE -> snoozing = id
                    ThreadAction.WAKE -> state.wakeNow(id)
                    ThreadAction.PIN, ThreadAction.UNPIN -> state.togglePin(id)
                    ThreadAction.LABEL -> labeling = id
                    ThreadAction.READ -> state.setUnread(listOf(id), false)
                    ThreadAction.UNREAD -> state.setUnread(listOf(id), true)
                    ThreadAction.SPAM -> state.reportSpam(listOf(id))
                    ThreadAction.NOT_SPAM -> state.notSpam(listOf(id))
                    ThreadAction.DELETE -> state.delete(listOf(id))
                    ThreadAction.UNTRASH -> state.untrash(listOf(id))
                    else -> Unit
                }
            },
        )
    }
    val todoActions = remember(state) {
        TodoActions(
            toggleDone = state::toggleTodoDone,
            wake = state::wakeTodo,
            snooze = { snoozingTodo = it },
            togglePin = state::toggleTodoPin,
            delete = { state.deleteTodos(listOf(it)) },
            startEditing = state.todos::setEditing,
            edit = state.todos::edit,
            finishEditing = state::finishTodoEdit,
            enter = state::newTodoAbove,
            continues = state::startsTodoAbove,
        )
    }

    // Action keys that need a menu: Snooze and Labels, on what the list says they act on.
    LaunchedEffect(state) {
        state.sheetRequests.collect { act ->
            val target = act.target
            val single = target.from != TargetSource.SELECTION && target.bundle == null
            val bundle = target.bundle
            when {
                target.from == TargetSource.SELECTION -> selectionSheet = if (act.action == ListCommands.SNOOZE) SetAction.SNOOZE else SetAction.LABEL
                bundle != null && act.action == ListCommands.SNOOZE -> {
                    snoozingBundleThreads = target.threads
                    snoozingBundle = bundle
                }
                !single -> Unit
                act.action == ListCommands.SNOOZE -> {
                    target.threads.firstOrNull()?.let { snoozing = it }
                    target.todos.firstOrNull()?.let { snoozingTodo = it }
                }
                else -> target.threads.firstOrNull()?.let { labeling = it }
            }
        }
    }

    // The app going to the background saves typing not yet saved; the edit stays open.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.todos.flush() }

    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state.wake()
                delay(WAKE_INTERVAL_MS)
            }
        }
    }

    // The views shown, oldest first, by key (the Outbox's and each settings page count as views); Back returns to the
    // one before, as on the web (viewsAfter). Worked out as each view shows, so Back sees it in the same frame, and kept
    // once that frame is drawn.
    val viewTrail = rememberSaveable(saver = ViewTrail.Saver) { ViewTrail() }
    val shownView = page.view ?: model.view.key
    val viewHistory = viewsAfter(viewTrail.views, shownView)
    SideEffect { viewTrail.views = viewHistory }
    val viewBefore = viewHistory.getOrNull(viewHistory.size - 2)
    val showView: (String) -> Unit = { key ->
        val shown = pageFor(key)
        if (shown == Page.MAIL) {
            val view = viewFromKey(key) ?: View.INBOX
            page = Page.MAIL
            searchBarOver = null
            if (model.view != view) state.setView(view)
        } else {
            if (shown == Page.OUTBOX) state.close()
            page = shown
            searchBarOver = null
        }
    }
    // Back from the Outbox's and settings' pages: to the view before.
    val leavePage: () -> Unit = { viewBefore?.let(showView) ?: run { page = Page.MAIL } }
    // The filter editor opened from Filters closes back to it; opened from a thread, it leaves as any page does.
    val leaveFilterEditor: () -> Unit = { if (filterStart == null) page = Page.FILTERS else leavePage() }
    // A shortcut shows its action on top: a message being written is kept as a draft, an open thread closes.
    LaunchedEffect(launcher) {
        launcher?.requests?.collect { shortcut ->
            if (shortcut != null && launcher.take(shortcut)) {
                scope.launch { drawerState.close() }
                if (shortcut == LauncherShortcut.NEW_EMAIL) {
                    sending.composeFrom(ComposePrefill()) { null }
                } else {
                    sending.composer.value?.let(sending::close)
                    clearSelection()
                    state.close()
                    page = Page.MAIL
                    if (shortcut == LauncherShortcut.NEW_TODO) {
                        searchBarOver = null
                        state.newTodo()
                    } else {
                        // A search shown already gives way to an empty bar, which takes the keyboard.
                        if (state.model.value.view is View.Search) state.closeSearch()
                        searchBarOver = state.model.value.view.key
                    }
                }
            }
        }
    }

    // Where Back from each page goes.
    val backPage = when {
        page == Page.MAIL -> Page.MAIL
        page == Page.FILTER_EDITOR && filterStart == null -> Page.FILTERS
        page == Page.SIGNATURE -> Page.SETTINGS
        else -> pageFor(viewBefore)
    }
    val pages = remember { SeekableTransitionState(page) }
    // A back gesture on a page drags the slide back to the page it returns to.
    val pageBack = remember(pages, backPage) {
        object : BackFollower {
            override suspend fun follow(event: BackEventCompat) = pages.seekTo(event.progress, targetState = backPage)

            override fun canceled() {
                scope.launch { pages.animateTo(pages.currentState) }
            }
        }
    }

    val tagOffers = remember { TagOffers() }
    CompositionLocalProvider(
        LocalContactPhotos provides photos,
        LocalBackFollower provides pageBack,
        LocalTagOffers provides tagOffers,
        LocalLabelTags provides model.labelTags,
        LocalCornerPlacement provides cornerPlacement,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only a swipe closing the panel: a row's swipe right always wins; the menu button opens it.
            gesturesEnabled = (composer == null || parked) && drawerState.isOpen,
            drawerContent = {
                MenuDrawer(
                    model = model,
                    page = page,
                    outboxCount = outboxItems.size,
                    outboxFailed = outboxItems.count { it.doc.status == OutboxStatus.FAILED },
                    showOutbox = showOutbox,
                    todosOn = todos.enabled,
                    onSelect = { view ->
                        page = Page.MAIL
                        searchBarOver = null
                        state.setView(view)
                        scope.launch { drawerState.close() }
                    },
                    onOutbox = {
                        page = Page.OUTBOX
                        state.close()
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        page = Page.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    settingsAlert = addressNotice != null,
                    focusOn = focusOn,
                    onFocus = focusStore::set,
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(paper.page).padding(stripSpace).consumeWindowInsets(stripSpace)) {
                val navigationBottom = if (stripShown) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val threadPadding = PaddingValues(top = 4.dp, bottom = navigationBottom + THREAD_END_CLEAR)
                // Lists under the floating buttons scroll clear of them: Compose alone, or New todo stacked over it.
                val buttonsPadding = PaddingValues(top = 4.dp, bottom = navigationBottom + LIST_END_CLEAR + if (todos.enabled) NEW_TODO_SIZE else 0.dp)
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    LaunchedEffect(page) { pages.animateTo(page) }
                    rememberTransition(pages, label = "page").AnimatedContent(
                        transitionSpec = { pageTransition(initialState, targetState) },
                        modifier = Modifier.fillMaxSize(),
                    ) { shown ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            when (shown) {
                                Page.SETTINGS -> SettingsScreen(
                                    sending = sending,
                                    email = model.email,
                                    onBack = leavePage,
                                    onSignOut = ::askSignOut,
                                    onDeleteAccount = authManager::deleteAccount,
                                    onAddress = { page = Page.ADDRESS },
                                    onSignature = { page = Page.SIGNATURE },
                                    onFilters = { page = Page.FILTERS },
                                    onEncryption = { page = Page.ENCRYPTION },
                                    onLabels = { page = Page.LABELS },
                                    addressAlert = addressNotice != null,
                                    savedMail = state.savedMail,
                                    savedMailBytes = state::savedMailBytes,
                                )
                                Page.SIGNATURE -> SignatureScreen(sending = sending, onBack = { page = Page.SETTINGS })
                                Page.LABELS -> LabelsSettingsScreen(
                                    state = state,
                                    model = model,
                                    onBack = leavePage,
                                    onOpen = { labelSettingsFor = it },
                                )
                                Page.ENCRYPTION -> EncryptionScreen(
                                    state = state,
                                    onBack = leavePage,
                                    onAddress = { page = Page.ADDRESS },
                                )
                                Page.ADDRESS -> AddressScreen(
                                    state = state,
                                    email = model.email,
                                    onBack = leavePage,
                                    onFindConfirmation = { runSearch(CONFIRMATION_SEARCH) },
                                    pointAtRetry = pointAtRetry,
                                    onPointedAtRetry = { pointAtRetry = false },
                                )
                                Page.FILTERS -> FiltersScreen(
                                    state = state,
                                    labels = model.userLabels,
                                    onBack = leavePage,
                                    onEdit = { filter -> openFilter(filter, null) },
                                )
                                Page.FILTER_EDITOR -> key(filterOpens) {
                                    FilterEditorScreen(
                                        state = state,
                                        labels = model.userLabels,
                                        filter = editingFilter,
                                        start = filterStart,
                                        onDone = leaveFilterEditor,
                                        onShow = runSearch,
                                    )
                                }
                                Page.OUTBOX -> OutboxScreen(
                                    sending = sending,
                                    now = model.now,
                                    onBack = leavePage,
                                    onMenu = { scope.launch { drawerState.open() } },
                                    todosOn = todos.enabled,
                                    onView = { view ->
                                        page = Page.MAIL
                                        searchBarOver = null
                                        state.setView(view)
                                    },
                                    contentPadding = buttonsPadding,
                                )
                                Page.MAIL -> ListKeys(refocus = Triple(model.view, model.openId, model.list.focus), onEvent = state::dispatch) {
                                    ThreadOverList(
                                        opened = model.takeIf { it.openId != null },
                                        key = { it.openId.orEmpty() },
                                        inPlace = { from, to -> paged == (from to to) },
                                        list = {
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                val current = model.view
                                                val bar = if (searchBarOver == current.key || current is View.Search) ListBar.SEARCH else ListBar.TOP
                                                AnimatedContent(targetState = bar, transitionSpec = { barTransition() }, label = "bar") { shownBar ->
                                                    CompositionLocalProvider(LocalLayerLeaving provides (shownBar != bar)) {
                                                        when (shownBar) {
                                                            ListBar.SEARCH -> SearchBar(
                                                                initial = (current as? View.Search)?.query ?: "",
                                                                labels = remember(model.userLabels) { model.userLabels.map { it.name } },
                                                                contacts = remember(contactList, model.summaries) { searchContacts(contactList, model) },
                                                                now = model.now,
                                                                onSearch = runSearch,
                                                                tagCounts = todos.tagCounts.takeIf { todos.enabled },
                                                                onClose = {
                                                                    searchBarOver = null
                                                                    // Back to the view search was started from, which a Back to a search can't know.
                                                                    val before = viewBefore
                                                                    if (current is View.Search && before != null) showView(before) else state.closeSearch()
                                                                },
                                                            )
                                                            ListBar.TOP -> TopBar(
                                                                model = model,
                                                                alert = addressNotice != null,
                                                                onMenu = { scope.launch { drawerState.open() } },
                                                                todosOn = todos.enabled,
                                                                onTop = topRequests::request,
                                                                onView = state::setView,
                                                                onSearch = { searchBarOver = current.key },
                                                                onSignOut = ::askSignOut,
                                                                onLabelSettings = { labelSettingsFor = it },
                                                                focusOn = focusOn,
                                                                onFocus = focusStore::toggle,
                                                                offlineWaiting = model.queue.size.takeIf { model.offline },
                                                            )
                                                        }
                                                    }
                                                }
                                                if (model.currentList.fromCache) {
                                                    OfflineNote("Offline. These are matches saved on this device.")
                                                }
                                                val shownNotice = addressNotice
                                                if (addressNoticeState == AddressNoticeState.WORKING && model.view == View.INBOX) {
                                                    AddressWorkingNote(account.address.orEmpty())
                                                }
                                                if (shownNotice != null && shownNotice != dismissedNotice && model.view == View.INBOX) {
                                                    AddressNotice(
                                                        text = shownNotice,
                                                        onFix = {
                                                            pointAtRetry = true
                                                            page = Page.ADDRESS
                                                        },
                                                        onDismiss = { dismissedNotice = shownNotice },
                                                    )
                                                }
                                                listStates.SaveableStateProvider(model.view.key) {
                                                    ThreadList(
                                                        model = model,
                                                        actions = rowActions,
                                                        contentPadding = buttonsPadding,
                                                        modifier = Modifier.nestedScroll(buttonsScroll),
                                                        focus = FocusInfo(focusing, focused.hidden) { focusStore.set(false) },
                                                        todos = todos,
                                                        todoActions = todoActions,
                                                        localDrafts = LocalOnlyRows(localOnly, sending::openLocalDraft),
                                                        toTop = topRequests,
                                                    )
                                                }
                                            }
                                        },
                                        thread = { openModel ->
                                            ThreadScreen(
                                                state = state,
                                                model = openModel,
                                                onSnooze = { snoozing = it },
                                                onLabels = { labeling = it },
                                                onFilter = { start -> openFilter(null, start) },
                                                onUnlock = {
                                                    page = Page.ENCRYPTION
                                                },
                                                contentPadding = threadPadding,
                                                todos = todos,
                                                todoActions = todoActions,
                                                onPage = { neighbor ->
                                                    val from = state.model.value.openId
                                                    rowActions.tap(threadRowKey(neighbor))
                                                    val opened = from != null && state.model.value.openId == neighbor
                                                    if (opened) paged = from to neighbor
                                                    opened
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // On a phone the bar for selected rows sits at the bottom, in the thumb's reach, in place of the floating buttons.
                val selectionShown = page == Page.MAIL && model.openId == null && selection.isNotEmpty()
                val showCompose = (page == Page.MAIL || page == Page.OUTBOX) && model.openId == null && !selectionShown
                // The bars sit centered on Compose's line, kept clear of it on both sides while it shows, and above an
                // action corner button.
                val barSideGap = if (showCompose && buttonsShown) BAR_END_GAP else BAR_SIDE_GAP
                Box(
                    contentAlignment = Alignment.BottomCenter,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(
                            start = barSideGap,
                            end = barSideGap,
                            bottom = when {
                                selectionShown -> SELECTION_BAR_HEIGHT + CORNER_ABOVE_BAR + CORNER_STACK
                                page == Page.MAIL && model.openId != null -> CORNER_GAP + CORNER_STACK
                                else -> BAR_BOTTOM_GAP
                            },
                        ),
                ) {
                    // A sending notice stacks over an undo rather than hiding it.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = notice
                        if (current != null) {
                            SendBar(
                                notice = current,
                                onUndo = when (current) {
                                    is SendNotice.Sending -> ({ sending.undoSend(current.outboxId, current.localId) })
                                    is SendNotice.Scheduled -> ({ sending.undoSend(current.outboxId, current.localId) })
                                    is SendNotice.Discarded -> ({ sending.undoDiscard(current.localId) })
                                    is SendNotice.Sent, is SendNotice.Failed, is SendNotice.Saved, is SendNotice.Info -> null
                                },
                                onView = {
                                    sending.dismissNotice()
                                    page = Page.OUTBOX
                                    state.close()
                                },
                                onOpen = { (current as? SendNotice.Saved)?.let { sending.reopenDraft(it.localId) } },
                            )
                        }
                        undo?.let { entry -> UndoBar(entry = entry, onUndo = state::undoLast) }
                    }
                }
                TagBar(tagOffers, Modifier.align(Alignment.BottomCenter).imePadding())
                // The selection bar keeps its count while it slides away.
                val barSelection = rememberLast(selection, selection.isNotEmpty())
                AnimatedVisibility(
                    visible = selectionShown,
                    enter = slideInVertically(tween(BUTTONS_MS)) { it } + fadeIn(tween(BUTTONS_MS)),
                    exit = slideOutVertically(tween(BUTTONS_MS)) { it } + fadeOut(tween(BUTTONS_MS)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    val edge = paper.border
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .shadow(8.dp)
                            .background(paper.card)
                            .drawBehind { drawLine(edge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                            .navigationBarsPadding(),
                    ) {
                        SelectionBar(
                            count = barSelection.size,
                            allSelected = allSelected(barSelection, shownIds),
                            onClear = clearSelection,
                            onSelectAll = { state.dispatch(ListEvent.PickAll) },
                        )
                    }
                }
                if (selectionShown) {
                    val barThreads = model.selectedThreads(selection)
                    val barTodos = selectedTodos(todos, selection)
                    val menu = selectionCornerMenu(
                        plan = selectionPlan(barThreads),
                        todoPlan = todoSelectionPlan(barThreads, barTodos),
                        inbox = selectionInboxPlan(barThreads),
                        actions = selectionActions(barThreads.size, barTodos.size),
                        threadCount = barThreads.size,
                        onAction = { action ->
                            when (action) {
                                SetAction.SNOOZE, SetAction.LABEL, SetAction.CATEGORY -> selectionSheet = action
                                else -> {
                                    actOnSelection(state, barThreads, barTodos, action)
                                    if (clearsSelection(action)) selectionSheet = null
                                }
                            }
                        },
                        onDelete = {
                            val delete = todoSelectionPlan(barThreads, barTodos).delete
                            state.deleteWithTodos(delete.threads, delete.untrash, delete.todos, ActOrigin(ActSource.SELECTION, clearSelection = true))
                        },
                        onInbox = { ids -> state.moveToInbox(ids, ActOrigin(ActSource.SELECTION, clearSelection = true)) },
                    )
                    if (menu.inner.isNotEmpty() || menu.outer.isNotEmpty()) {
                        CornerButton(
                            label = "Selection actions",
                            icon = Icons.Outlined.MoreVert,
                            menu = { menu },
                            modifier = Modifier
                                .align(cornerAlignment(cornerSide))
                                .navigationBarsPadding()
                                .padding(start = CORNER_GAP, end = CORNER_GAP, bottom = SELECTION_BAR_HEIGHT + CORNER_ABOVE_BAR),
                        )
                    }
                }
                // Scrolling the list down tucks the buttons away; scrolling up brings them back.
                AnimatedVisibility(
                    visible = buttonsShown && showCompose,
                    enter = slideInVertically(tween(BUTTONS_MS)) { it / 2 } + fadeIn(tween(BUTTONS_MS)),
                    exit = slideOutVertically(tween(BUTTONS_MS)) { it / 2 } + fadeOut(tween(BUTTONS_MS)),
                    modifier = Modifier
                        .align(cornerAlignment(cornerSide))
                        .navigationBarsPadding()
                        .padding(start = CORNER_GAP, end = CORNER_GAP, bottom = CORNER_GAP),
                ) {
                    CornerButton(
                        label = "Compose",
                        icon = Icons.Outlined.Edit,
                        onTap = sending::composeNew,
                        above = {
                            if (todos.enabled) {
                                NewTodoButton(
                                    onClick = {
                                        page = Page.MAIL
                                        searchBarOver = null
                                        state.newTodo()
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        },
                    )
                }
            }
            // Under the menu drawer and over the screen's bottom, below the composer that grows out of it.
            val stripComposer = rememberLast(composer, stripShown)
            AnimatedVisibility(
                visible = stripShown,
                enter = fadeIn(tween(BUTTONS_MS)),
                exit = fadeOut(tween(BUTTONS_MS)),
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
            ) {
                stripComposer?.let { session ->
                    ParkedStrip(title = ComposePark.title(session.subject, session.kind), onOpen = sending::unpark)
                }
            }
        }

        // Back goes to the layer that opened last (LayerBackHandler); returning to the view before is the bottom one,
        // and at the first view Back leaves the app. These stay added while a thread or search is open over them (each
        // adds its own Back as it opens, above these), so closing one doesn't put these back on top of the other.
        LayerBackHandler(open = page == Page.MAIL && viewBefore != null && model.view !is View.Search) {
            viewBefore?.let(showView)
        }

        LayerBackHandler(open = page == Page.MAIL && model.list.bundle != null) {
            state.dispatch(ListEvent.CloseBundle)
        }

        LayerBackHandler(open = page == Page.MAIL && selection.isNotEmpty()) {
            clearSelection()
        }

        // Only while the menu is opening or open: one closing from a pick leaves Back to the page picked.
        LayerBackHandler(open = drawerState.targetValue == DrawerValue.Open) {
            scope.launch { drawerState.close() }
        }

        signOutQuestion?.let { warning ->
            ListMenu("sign-out", onEvent = state::dispatch)
            SignOutDialog(
                warning = warning,
                onSignOut = {
                    signOutQuestion = null
                    authManager.signOut()
                },
                onDismiss = { signOutQuestion = null },
            )
        }

        // The composer slides up over everything and back down when it closes, by Back too. Parked, it slides down
        // until its top bar sits on the strip, and grows back up from there when the strip is tapped.
        val shownComposer = rememberLast(composer, composer != null)
        val drop = remember { Animatable(0f) }
        val parkedMemory = remember { ParkedMemory() }
        if (composer == null) parkedMemory.localId = null else if (parked) parkedMemory.localId = composer?.localId
        val fromStrip = composer != null && composer?.localId == parkedMemory.localId
        val stripTop = with(LocalDensity.current) {
            (ComposePark.STRIP_HEIGHT.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).roundToPx() +
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding().roundToPx()
        }
        LaunchedEffect(composer) { drop.snapTo(0f) }
        AnimatedVisibility(
            visible = composer != null && !parked,
            enter = slideInVertically(tween(SCREEN_IN_MS, easing = OpenEasing)) { if (fromStrip) it - stripTop else it },
            exit = slideOutVertically(tween(SCREEN_IN_MS, easing = OpenEasing)) { if (parked) it - stripTop else it },
            modifier = Modifier.graphicsLayer { translationY = drop.value },
        ) {
            // Each message written starts afresh: another one replacing it keeps none of its dialogs or sheets.
            shownComposer?.let { session ->
                key(session) {
                    HeldWhileLeaving(leaving = composer == null || parked) {
                        ComposeScreen(
                            session = session,
                            sending = sending,
                            onViewOutbox = {
                                sending.dismissNotice()
                                page = Page.OUTBOX
                                state.close()
                            },
                            drop = drop,
                            onPark = {
                                // The slide down starts where the drag left it and takes the drag back over the same time.
                                scope.launch { drop.animateTo(0f, tween(SCREEN_IN_MS, easing = OpenEasing)) }
                                sending.park(session)
                            },
                        )
                    }
                }
            }
        }

        // A label gone meanwhile shows no sheet.
        labelSettingsFor?.let(model.listLabelsById::get)?.let { label ->
            ListMenu("label-settings", onEvent = state::dispatch)
            LabelSettingsSheet(state, label, model.labelPrefs[label.id], onDismiss = { labelSettingsFor = null })
        }

        labeling?.let { id ->
            ListMenu("labels-thread", onEvent = state::dispatch)
            LabelSheet(
                state = state,
                threads = model.selectedThreads(listOf(id)),
                labels = model.userLabels,
                onDismiss = { labeling = null },
            )
        }

        // What a sheet acts on, kept while it slides away after a pick that clears the selection.
        val sheetSelection = rememberLast(selection, selection.isNotEmpty())
        val shownSheet = selectionSheet.takeIf { sheetSelection.isNotEmpty() }
        ListMenu("selection-sheet", open = shownSheet == SetAction.LABEL || shownSheet == SetAction.CATEGORY || shownSheet == SetAction.SNOOZE, onEvent = state::dispatch)
        if (sheetSelection.isNotEmpty()) when (selectionSheet) {
            SetAction.LABEL, SetAction.CATEGORY -> LabelSheet(
                state = state,
                threads = model.selectedThreads(sheetSelection),
                labels = model.userLabels,
                onDismiss = { selectionSheet = null },
                onAction = { action -> if (clearsSelection(action)) state.dispatch(ListEvent.ClearSelection) },
            )
            SetAction.SNOOZE -> SnoozeSheet(
                title = snoozeSelectionTitle(model, todos, sheetSelection),
                stats = stats,
                onDismiss = { selectionSheet = null },
                onSnooze = { epoch, source, pickedKey ->
                    val threadIds = model.selectedThreads(sheetSelection).map { it.id }
                    val todoIds = todoSelectionPlan(emptyList(), selectedTodos(todos, sheetSelection)).snooze
                    state.snoozeWithTodos(threadIds, todoIds, epoch, source, pickedKey, ActOrigin.selection(SetAction.SNOOZE))
                },
            )
            else -> Unit
        }

        // A todo gone meanwhile shows no sheet.
        snoozingTodo?.let(todos::get)?.let { todo ->
            val id = todo.id
            ListMenu("snooze-todo", onEvent = state::dispatch)
            SnoozeSheet(
                title = InlineStyle.plain(todo.text).trim().ifEmpty { "Todo" },
                stats = stats,
                onDismiss = { snoozingTodo = null },
                onSnooze = { epoch, source, pickedKey -> state.snoozeTodo(id, epoch, source, pickedKey) },
                repeat = todo.repeat,
                onRepeat = { draft -> state.setTodoRepeat(id, draft) },
            )
        }

        snoozingBundle?.let { labelId ->
            val threads = snoozingBundleThreads
            ListMenu("snooze-bundle", onEvent = state::dispatch)
            SnoozeSheet(
                title = "Snooze all in ${model.listLabelsById[labelId]?.name ?: labelId}",
                stats = stats,
                onDismiss = { snoozingBundle = null },
                onSnooze = { epoch, source, pickedKey -> state.snoozeBundle(labelId, threads, epoch, source, pickedKey) },
            )
        }

        snoozing?.let { id ->
            ListMenu("snooze-thread", onEvent = state::dispatch)
            SnoozeSheet(
                title = model.rowSummary(id)?.let { shownSubject(it, model.rowPreviews[id]) } ?: "Snooze",
                stats = stats,
                onDismiss = { snoozing = null },
                onSnooze = { epoch, source, pickedKey ->
                    val snoozed = state.snooze(id, epoch, source, pickedKey)
                    // The sheet slides away on its own, then clears snoozing.
                    if (snoozed && model.openId == id) state.close()
                    snoozed
                },
            )
        }
    }
}

/** Todos among the selected ids, as the selection bar's rules see them. */
private fun selectedTodos(todos: TodoSnapshot, ids: List<String>): List<SelectedTodo> =
    ids.mapNotNull { key -> rowTodoId(key)?.let(todos::get) }.map { SelectedTodo(it.id, it.state == TodoState.DONE, it.pinned) }

/** The snooze sheet's title for the selection: how many threads, todos, or items of both. */
private fun snoozeSelectionTitle(model: MailModel, todos: TodoSnapshot, ids: List<String>): String {
    val threads = model.selectedThreads(ids).size
    val todoCount = selectedTodos(todos, ids).size
    val count = threads + todoCount
    val noun = when {
        todoCount == 0 -> if (count == 1) "thread" else "threads"
        threads == 0 -> if (count == 1) "todo" else "todos"
        else -> "items"
    }
    return "Snooze $count $noun"
}

/** Apply one of the selection bar's direct actions to every selected thread and todo it fits. */
private fun actOnSelection(state: MailState, threads: List<SelectedThread>, todos: List<SelectedTodo>, action: SetAction) {
    val plan = selectionPlan(threads)
    val todoPlan = todoSelectionPlan(threads, todos)
    val origin = ActOrigin.selection(action)
    when (action) {
        SetAction.DONE -> state.doneWithTodos(selectionInboxPlan(threads).done, todoPlan.done, origin)
        SetAction.WAKE -> {
            state.wakeAll(plan.wake)
            state.dispatch(ListEvent.ClearSelection)
        }
        SetAction.PIN -> state.setPinnedWithTodos(todoPlan.pin.threads, todoPlan.pin.todos, todoPlan.pin.on, origin)
        SetAction.UNREAD -> state.setUnread(plan.unread.ids, plan.unread.on, origin)
        SetAction.SPAM -> if (plan.spam.on) state.reportSpam(plan.spam.ids, origin) else state.notSpam(plan.spam.ids, origin)
        SetAction.SNOOZE, SetAction.LABEL, SetAction.CATEGORY -> Unit
    }
}

@Composable
private fun TopBar(
    model: MailModel,
    alert: Boolean,
    onMenu: () -> Unit,
    todosOn: Boolean,
    onTop: () -> Unit,
    onView: (View) -> Unit,
    onSearch: () -> Unit,
    onSignOut: () -> Unit,
    onLabelSettings: (String) -> Unit,
    focusOn: Boolean,
    onFocus: () -> Unit,
    offlineWaiting: Int? = null,
) {
    var accountMenu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onMenu) {
            Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = paper.text)
        }
        Box(modifier = Modifier.weight(1f)) {
            ViewName(viewTitle(model), current = model.view, todosOn = todosOn, onTop = onTop, onPick = onView)
        }
        val view = model.view
        if (view is View.Label) {
            IconButton(onClick = { onLabelSettings(view.labelId) }) {
                Icon(Icons.Outlined.Tune, contentDescription = "Label settings", tint = paper.text)
            }
        }
        if (view == View.INBOX) FocusButton(focusOn, onFocus)
        offlineWaiting?.let { OfflineMark(it) }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = "Search", tint = paper.text)
        }
        Box {
            IconButton(
                onClick = { accountMenu = true },
                modifier = Modifier.semantics {
                    contentDescription = if (alert) "Account. Your @hafa.cc address isn't set up" else "Account"
                },
            ) {
                IndicatorDot(show = alert, tone = DotTone.PROBLEM, ring = paper.page, round = true) {
                    Avatar(Person("", model.email), size = 32.dp, single = true)
                }
            }
            ListMenu("account", accountMenu)
            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                Text(
                    model.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    onClick = {
                        accountMenu = false
                        onSignOut()
                    },
                )
            }
        }
    }
}

/** The top bar's focus switch: a plain icon while off, a filled "Focus" pill while on. */
@Composable
private fun FocusButton(on: Boolean, onToggle: () -> Unit) {
    val description = "Focus: show only pinned mail and todos"
    if (on) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .height(36.dp)
                .clip(CircleShape)
                .background(paper.accentSoft)
                .toggleable(value = true, role = Role.Switch) { onToggle() }
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, tint = paper.accentStrong, modifier = Modifier.size(18.dp))
            Text("Focus", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = paper.accentStrong)
        }
    } else {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics {
                contentDescription = description
                role = Role.Switch
            },
        ) {
            Icon(Icons.Outlined.CenterFocusWeak, contentDescription = null, tint = paper.text)
        }
    }
}

// Contacts and recent senders for search completion, best first.
private fun searchContacts(contacts: List<Contact>, model: MailModel): List<Person> {
    val saved = contacts.sortedBy { it.other }.flatMap { contact -> contact.emails.map { Person(contact.name, it) } }
    val recent = model.summaries.values.sortedByDescending { it.lastDate }.flatMap { it.senders }
    return (saved + recent).filter { it.email.isNotEmpty() && it.email != model.email }.distinctBy { it.email.lowercase() }
}

/** A line above the inbox while the @hafa.cc address isn't set up: what's wrong, Fix it, and close. */
@Composable
private fun AddressNotice(text: String, onFix: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(paper.snoozeSoft, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            "Your @hafa.cc address isn't set up. $text",
            style = MaterialTheme.typography.bodyMedium,
            color = paper.onSnoozeSoft,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(onClick = onFix) {
            Text("Fix it", color = paper.onSnoozeSoft, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Hide until something changes", tint = paper.onSnoozeSoft)
        }
    }
}

/** A calm line above the inbox while the @hafa.cc address is being set up and nothing is wrong. */
@Composable
private fun AddressWorkingNote(address: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(paper.muted, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(color = paper.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(
            "Setting up ${address.ifEmpty { "your @hafa.cc address" }}… this can take a few minutes.",
            style = MaterialTheme.typography.bodyMedium,
            color = paper.textSecondary,
        )
    }
}

/**
 * New todo: a small round button that takes taps on a 48 dp square around it, as on web, so the
 * small button is easy to hit.
 */
@Composable
private fun NewTodoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(NEW_TODO_SIZE)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "New todo" },
    ) {
        // The button shows the press of the whole square; its own taps go to the same place.
        SmallFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = paper.card,
            contentColor = paper.accentStrong,
            interactionSource = interactions,
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Icon(Icons.Outlined.AddTask, contentDescription = null)
        }
    }
}

/**
 * The top bar's small mark while Gmail can't be reached, as on web at phone width: "Offline", or
 * how many changes wait to be sent; TalkBack hears the whole of it.
 */
@Composable
private fun OfflineMark(waiting: Int) {
    val said = when (waiting) {
        0 -> "Offline. Changes are sent when you're back online"
        1 -> "Offline. 1 change waiting to send"
        else -> "Offline. $waiting changes waiting to send"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .height(32.dp)
            .background(paper.muted, CircleShape)
            .padding(horizontal = 12.dp)
            .clearAndSetSemantics {
                contentDescription = said
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
        Text(if (waiting == 0) "Offline" else "$waiting waiting", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
    }
}

/** The question sign-out asks while label changes or drafts haven't reached Gmail. */
@Composable
private fun SignOutDialog(warning: String, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = paper.card,
        title = { Text("Sign out anyway?", color = paper.text) },
        text = { Text(warning, color = paper.textSecondary) },
        confirmButton = {
            TextButton(onClick = onSignOut) { Text("Sign out", color = paper.danger, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = paper.textSecondary) } },
    )
}

@Composable
private fun OfflineNote(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
    }
}

@Composable
private fun MenuDrawer(
    model: MailModel,
    page: Page,
    outboxCount: Int,
    outboxFailed: Int,
    showOutbox: Boolean,
    todosOn: Boolean,
    onSelect: (View) -> Unit,
    onOutbox: () -> Unit,
    onSettings: () -> Unit,
    settingsAlert: Boolean,
    focusOn: Boolean,
    onFocus: (Boolean) -> Unit,
) {
    val counts = mapOf(
        Mailbox.INBOX to model.inboxRows.size.toLong(),
        Mailbox.SNOOZED to model.snoozedCount.toLong(),
        Mailbox.DRAFTS to model.draftCount,
    )
    val labelCounts = model.labelCounts
    ModalDrawerSheet(drawerContainerColor = paper.sidebar, drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Avatar(Person("", model.email), size = 36.dp, single = true)
                Column {
                    Text("Inbox", fontWeight = FontWeight.SemiBold, color = paper.text)
                    Text(model.email, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            for (box in Mailbox.entries) {
                val view = View.Fixed(box)
                MenuItem(
                    title = box.title,
                    selected = page == Page.MAIL && model.view == view,
                    count = counts[box] ?: 0,
                    leading = { tint -> Icon(mailboxIcon(box), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
                    onClick = { onSelect(view) },
                )
                if (box == Mailbox.DONE && todosOn) {
                    MenuItem(
                        title = "Todos",
                        selected = page == Page.MAIL && model.view == View.Todos,
                        count = model.activeTodos.size.toLong(),
                        leading = { tint -> Icon(TodosIcon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
                        onClick = { onSelect(View.Todos) },
                    )
                }
                if (box == Mailbox.DRAFTS && showOutbox) {
                    MenuItem(
                        title = "Outbox",
                        selected = page == Page.OUTBOX,
                        count = outboxCount.toLong(),
                        // Failed sends turn the count red, as on the web.
                        countAlert = if (outboxFailed > 0) "$outboxFailed couldn't send" else null,
                        leading = { tint -> Icon(Icons.Outlined.Outbox, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
                        onClick = onOutbox,
                    )
                }
            }
            val labels = model.drawerLabels
            if (labels.isNotEmpty()) {
                val context = LocalContext.current
                val drawerPrefs = remember { context.getSharedPreferences(DRAWER_PREFS, Context.MODE_PRIVATE) }
                var labelsOpen by remember { mutableStateOf(!drawerPrefs.getBoolean(LABELS_COLLAPSED_KEY, false)) }
                val turn by animateFloatAsState(if (labelsOpen) 0f else -90f, label = "labelsChevron")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .rowHighlight(CircleShape)
                        .clickable(onClickLabel = if (labelsOpen) "Hide labels" else "Show labels") {
                            labelsOpen = !labelsOpen
                            drawerPrefs.edit { putBoolean(LABELS_COLLAPSED_KEY, !labelsOpen) }
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        "Labels",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = paper.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp).rotate(turn))
                }
                AnimatedVisibility(visible = labelsOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        for (label in labels) {
                            val view = View.Label(label.id)
                            MenuItem(
                                title = label.name,
                                selected = page == Page.MAIL && model.view == view,
                                count = labelCounts[label.id]?.toLong() ?: 0,
                                leading = { _ ->
                                    Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                        LabelMark(label, model.labelPrefs[label.id]?.icon, if (model.labelPrefs[label.id]?.icon == null) 14.dp else 20.dp, plain = true)
                                    }
                                },
                                onClick = { onSelect(view) },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = paper.border, modifier = Modifier.padding(vertical = 12.dp))
            SwitchRow(
                title = "Focus",
                checked = focusOn,
                onChange = onFocus,
                detail = "Only pinned mail and todos in the inbox",
                icon = Icons.Outlined.CenterFocusStrong,
                padding = PaddingValues(horizontal = 16.dp),
            )
            MenuItem(
                title = "Settings",
                selected = page == Page.SETTINGS,
                count = 0,
                leading = { tint ->
                    IndicatorDot(
                        show = settingsAlert,
                        tone = DotTone.PROBLEM,
                        ring = if (page == Page.SETTINGS) paper.card else paper.sidebar,
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = if (settingsAlert) "Your @hafa.cc address isn't set up" else null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun MenuItem(
    title: String,
    selected: Boolean,
    count: Long,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    countAlert: String? = null,
) {
    val tint = if (selected) paper.text else paper.textSecondary
    NavigationDrawerItem(
        label = {
            Text(
                title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        selected = selected,
        onClick = onClick,
        icon = { leading(tint) },
        badge = if (count > 0) {
            {
                if (countAlert != null) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.danger,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { contentDescription = "$count, $countAlert" },
                    )
                } else {
                    Text(count.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            null
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = paper.card,
            unselectedContainerColor = Color.Transparent,
            selectedTextColor = paper.text,
            unselectedTextColor = paper.textSecondary,
            selectedBadgeColor = paper.text,
            unselectedBadgeColor = paper.faint,
        ),
        // The current mailbox is a raised pill on the drawer's stone, as on web.
        modifier = Modifier
            .height(48.dp)
            .then(if (selected) Modifier.shadow(2.dp, CircleShape) else Modifier)
            .rowHighlight(CircleShape),
    )
}

/** Whether notifications are on, and what tapping their switch does. */
data class NotificationsControl(
    /** whether notifications are on */
    val allowed: Boolean,
    /** turns them on or off */
    val toggle: () -> Unit,
)

/**
 * Remember the notifications switch: tapping asks for the permission once; once on (or refused for
 * good) it opens the app's notification settings instead.
 */
@Composable
fun rememberNotificationsControl(): NotificationsControl {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefs = remember { context.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE) }
    var allowed by remember { mutableStateOf(Notifier.allowed(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Notifier.allowed(context)
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Notifier.allowed(context)
    }
    return NotificationsControl(allowed) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val refused = prefs.getBoolean(ASKED_KEY, false) &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) != true
        if (granted || refused) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        } else {
            prefs.edit { putBoolean(ASKED_KEY, true) }
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// Per-device drawer choices.
private const val DRAWER_PREFS = "drawer"
private const val LABELS_COLLAPSED_KEY = "labelsCollapsed"
