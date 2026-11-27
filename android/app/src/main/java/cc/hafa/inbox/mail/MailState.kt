package cc.hafa.inbox.mail

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import cc.hafa.inbox.FirebaseConfig
import cc.hafa.inbox.auth.ReauthNeeded
import cc.hafa.inbox.auth.isReauth
import cc.hafa.inbox.auth.isUnreachableCall
import cc.hafa.inbox.core.compose.ComposeKind
import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.InvalidAddressError
import cc.hafa.inbox.core.filters.FilterEditor
import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.filters.editorToFilter
import cc.hafa.inbox.core.gmail.Backoff
import cc.hafa.inbox.core.gmail.CalendarClient
import cc.hafa.inbox.core.gmail.CallPriority
import cc.hafa.inbox.core.gmail.FIRST_BACKOFF_MS
import cc.hafa.inbox.core.gmail.GmailClient
import cc.hafa.inbox.core.gmail.GmailException
import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.gmail.GmailLabelColor
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailPriority
import cc.hafa.inbox.core.gmail.ReportingTransport
import cc.hafa.inbox.core.gmail.ConnectionEvent
import cc.hafa.inbox.core.gmail.offlineAfter
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.gmail.GmailThreadStub
import cc.hafa.inbox.core.gmail.GmailTimeoutException
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.ListOptions
import cc.hafa.inbox.core.gmail.MAX_CONCURRENT
import cc.hafa.inbox.core.gmail.RateGate
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.gmail.UrlConnectionTransport
import cc.hafa.inbox.core.gmail.isNetworkError
import cc.hafa.inbox.core.gmail.isNotFound
import cc.hafa.inbox.core.gmail.isRateLimited
import cc.hafa.inbox.core.gmail.mapLimited
import cc.hafa.inbox.core.gmail.touchesDrafts
import cc.hafa.inbox.core.label.categoryChange
import cc.hafa.inbox.core.label.keepListed
import cc.hafa.inbox.core.label.labelNamed
import cc.hafa.inbox.core.label.renames
import cc.hafa.inbox.core.mail.AccountChange
import cc.hafa.inbox.core.mail.ActSource
import cc.hafa.inbox.core.mail.BUNDLE_FIRST_PAGE
import cc.hafa.inbox.core.mail.BUNDLE_PAGE_SIZE
import cc.hafa.inbox.core.mail.BundleList
import cc.hafa.inbox.core.mail.FOCUS_KEY
import cc.hafa.inbox.core.mail.HeldPlaces
import cc.hafa.inbox.core.mail.InboxPageRequest
import cc.hafa.inbox.core.mail.InlinePart
import cc.hafa.inbox.core.mail.LabelChange
import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.ListAnchor
import cc.hafa.inbox.core.mail.ListEffect
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.PINNED_KEY
import cc.hafa.inbox.core.mail.PINNED_LABEL
import cc.hafa.inbox.core.mail.PINNED_PAGE
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.QueryLabel
import cc.hafa.inbox.core.mail.ReplayStep
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SelectedStatus
import cc.hafa.inbox.core.mail.SelectedThread
import cc.hafa.inbox.core.mail.SelectedTodo
import cc.hafa.inbox.core.mail.SelectionAction
import cc.hafa.inbox.core.mail.SetAction
import cc.hafa.inbox.core.mail.SnoozeDoc
import cc.hafa.inbox.core.mail.SnoozeStatus
import cc.hafa.inbox.core.mail.SweptThread
import cc.hafa.inbox.core.mail.TargetSource
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.mail.TodoPageCount
import cc.hafa.inbox.core.mail.UndoKind
import cc.hafa.inbox.core.mail.UnsubscribeMethod
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.core.mail.answeredSearch
import cc.hafa.inbox.core.mail.applyChange
import cc.hafa.inbox.core.mail.bundleKey
import cc.hafa.inbox.core.mail.bundleSweepIds
import cc.hafa.inbox.core.mail.bundlesToFetch
import cc.hafa.inbox.core.mail.bundlesToRemember
import cc.hafa.inbox.core.mail.changeRefused
import cc.hafa.inbox.core.mail.changeSent
import cc.hafa.inbox.core.mail.clearsSelection
import cc.hafa.inbox.core.mail.compareHistoryIds
import cc.hafa.inbox.core.mail.decodeBase64Url
import cc.hafa.inbox.core.mail.doneAllIds
import cc.hafa.inbox.core.mail.doneAllTodoIds
import cc.hafa.inbox.core.mail.draftsOpening
import cc.hafa.inbox.core.mail.enqueue
import cc.hafa.inbox.core.mail.epochToIso
import cc.hafa.inbox.core.mail.enqueueAccount
import cc.hafa.inbox.core.mail.filterMade
import cc.hafa.inbox.core.mail.focusQuery
import cc.hafa.inbox.core.mail.holdPlaces
import cc.hafa.inbox.core.mail.importantChange
import cc.hafa.inbox.core.mail.inLapseWindow
import cc.hafa.inbox.core.mail.inboxExclusion
import cc.hafa.inbox.core.mail.inboxNextPage
import cc.hafa.inbox.core.mail.inboxPlan
import cc.hafa.inbox.core.mail.joinAccountQueues
import cc.hafa.inbox.core.mail.labelMade
import cc.hafa.inbox.core.mail.labelsWithPending
import cc.hafa.inbox.core.mail.layoutKeys
import cc.hafa.inbox.core.mail.listOptions
import cc.hafa.inbox.core.mail.listStep
import cc.hafa.inbox.core.mail.localOnlyDrafts
import cc.hafa.inbox.core.mail.mergeDetails
import cc.hafa.inbox.core.mail.mergeFirstPage
import cc.hafa.inbox.core.mail.mergeUndoItems
import cc.hafa.inbox.core.mail.newLocalId
import cc.hafa.inbox.core.mail.newerMail
import cc.hafa.inbox.core.mail.nextAccountChange
import cc.hafa.inbox.core.mail.openPlaces
import cc.hafa.inbox.core.mail.parseUnsubscribe
import cc.hafa.inbox.core.mail.pinHeldFocus
import cc.hafa.inbox.core.mail.relabelChanges
import cc.hafa.inbox.core.mail.replay
import cc.hafa.inbox.core.mail.restoresSnooze
import cc.hafa.inbox.core.mail.rowThreadId
import cc.hafa.inbox.core.mail.rowTodoId
import cc.hafa.inbox.core.mail.savedSummary
import cc.hafa.inbox.core.mail.scanStart
import cc.hafa.inbox.core.mail.selectionActions
import cc.hafa.inbox.core.mail.selectionPlan
import cc.hafa.inbox.core.mail.sendableChanges
import cc.hafa.inbox.core.mail.snoozeArchivedElsewhere
import cc.hafa.inbox.core.mail.stillFound
import cc.hafa.inbox.core.mail.summarizeThread
import cc.hafa.inbox.core.mail.threadDetail
import cc.hafa.inbox.core.mail.threadLabels
import cc.hafa.inbox.core.mail.threadRowKey
import cc.hafa.inbox.core.mail.ListEntry
import cc.hafa.inbox.core.mail.bundledOnWake
import cc.hafa.inbox.core.mail.todoRowKey
import cc.hafa.inbox.core.mail.todoSelectionPlan
import cc.hafa.inbox.core.mail.touchedBundles
import cc.hafa.inbox.core.mail.undoChange
import cc.hafa.inbox.core.mail.undoLabels
import cc.hafa.inbox.core.mail.unreadAfter
import cc.hafa.inbox.core.mail.unsyncedChanges
import cc.hafa.inbox.core.mail.withAddedMessages
import cc.hafa.inbox.core.notify.mailNotificationStale
import cc.hafa.inbox.core.notify.SnoozeEnd
import cc.hafa.inbox.core.notify.notificationSnoozeTime
import cc.hafa.inbox.core.notify.todoSnoozeEnd
import cc.hafa.inbox.core.pgp.encryptedSearchQuery
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.search.matchesOffline
import cc.hafa.inbox.core.search.savedText
import cc.hafa.inbox.core.search.searchPlan
import cc.hafa.inbox.core.search.searchTagTerms
import cc.hafa.inbox.core.mail.OfflineDraftsInput
import cc.hafa.inbox.core.mail.OfflineLocalDraft
import cc.hafa.inbox.core.mail.offlineDraftThreads
import cc.hafa.inbox.core.mail.withQueued
import cc.hafa.inbox.core.send.GmailDraftRef
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.outboxDraftIds
import cc.hafa.inbox.core.send.outboxDrafts
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.Position
import cc.hafa.inbox.core.todo.RepeatDraft
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoFilter
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.activeKey
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.rowEmailTodo
import cc.hafa.inbox.core.todo.isSnoozed
import cc.hafa.inbox.core.todo.matches
import cc.hafa.inbox.core.todo.withDone
import cc.hafa.inbox.core.todo.withPinToggled
import cc.hafa.inbox.core.todo.withReactivated
import cc.hafa.inbox.core.todo.withSnoozed
import cc.hafa.inbox.data.AccountStore
import cc.hafa.inbox.data.DraftStore
import cc.hafa.inbox.data.LabelPrefDocs
import cc.hafa.inbox.data.MailCache
import cc.hafa.inbox.data.MailDatabase
import cc.hafa.inbox.data.ParkedWork
import cc.hafa.inbox.data.SnoozeDocs
import cc.hafa.inbox.data.SnoozeSource
import cc.hafa.inbox.data.SnoozeStatsStore
import cc.hafa.inbox.data.SnoozeStatsUndo
import cc.hafa.inbox.data.TagRename
import cc.hafa.inbox.data.TodoStore
import cc.hafa.inbox.data.TodoUndo
import cc.hafa.inbox.notify.Notifier
import cc.hafa.inbox.notify.ReminderSnoozes
import cc.hafa.inbox.notify.SnoozeAlarm
import cc.hafa.inbox.pgp.Encryption
import cc.hafa.inbox.pgp.EncryptionStatus
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// How long the undo bar stays.
private const val UNDO_MS = 5_000L
// How long Snooze from a notification waits for Firestore to take the snooze.
private const val SNOOZE_WRITE_WAIT_MS = 3_000L
// Messages one search for the threads an encrypted reply answers lists, as the server's Done.
private const val MAX_ANSWERED_SEARCH_RESULTS = 100
// How long the mail stays still before shown notifications are checked against it.
private const val NOTIFICATION_CHECK_MS = 500L

// How long a todo opened from its reminder waits for the todos to load before opening anyway.
private const val TODO_OPEN_WAIT_MS = 15_000L

private const val TAG = "MailState"

// Saved threads' bodies held ready in memory, and the map's load factor.
private const val SAVED_BODIES_HELD = 24
private const val LOAD_FACTOR = 0.75f

// How urgent the Gmail reads of a coroutine are (GmailPriority); reads default to VISIBLE. Shared,
// so never raised.
private val ACTION = GmailPriority(CallPriority.ACTION)
private val VISIBLE = GmailPriority(CallPriority.VISIBLE)
private val BACKGROUND = GmailPriority(CallPriority.BACKGROUND)

// The caller's Gmail priority, for work it hands to another coroutine.
private suspend fun callerPriority(): CoroutineContext = currentCoroutineContext()[GmailPriority] ?: EmptyCoroutineContext
// Each thread on a page costs a metadata read, so a page holds about two screens, not more.
private const val PAGE_SIZE = 25
private const val MAX_NOW_STALENESS_MS = 60_000L
private const val MIN_TICK_MS = 100L
// How long a closed bundle keeps its place: its close animation.
private const val HELD_RELEASE_MS = 200L
private const val MAX_HISTORY_PAGES = 5
// Most threads Done all on a bundle archives, listed by id; also the list page size it asks for.
private const val MAX_BULK_THREADS = 500
// Fetched attachments kept in memory, in bytes.
private const val ATTACHMENT_CACHE_BYTES = 32 * 1024 * 1024

/** One thread's label change, before it is queued. */
private data class ThreadChange(val threadId: String, val add: List<String>, val remove: List<String>)

// A row with a label change (Gmail's ids) shown, as queueing it shows it.
private fun ThreadSummary.withChange(add: List<String>, remove: List<String>): ThreadSummary =
    copy(labelIds = applyChange(labelIds, add, remove), unread = unreadAfter(unread, add, remove))

// A snooze menu pick, learned from once the snooze it made is carried out.
private data class SnoozePick(val epoch: Long, val source: SnoozeSource, val key: String?)

// What undo takes back of an action: its kind, the threads as they were, whether it pinned (for
// the bar's text), and the labels label changes touch (null for the other kinds).
private data class UndoPlan(
    val kind: UndoKind,
    val items: List<UndoItem> = emptyList(),
    val pinned: Boolean = false,
    val touched: List<String>? = null,
)

// A change to todos: those it changes, each as the list shows it after (null: gone), the store's
// change, which returns what undo puts back, and whether it deletes those on emails among them
// (the only change an email's todo takes as its own).
private class TodoStep(
    val ids: List<String>,
    val show: (Todo) -> Todo?,
    val carry: (List<String>) -> TodoUndo?,
    val deletesEmail: Boolean = false,
)

// One action, or several taken as one (`+`), before it is carried out: the rows it acts on, the
// label changes it queues, the snoozes it writes and drops, the threads whose notifications go,
// whether it closes the open thread when that is among them, its todo changes, the snooze pick it
// learns from, what undo takes back (null: no undo), and whether it came from a notification.
private data class ActionPlan(
    val keys: List<String> = emptyList(),
    val changes: List<ThreadChange> = emptyList(),
    val snoozes: Map<String, SnoozeDoc> = emptyMap(),
    val unsnoozed: Set<String> = emptySet(),
    val cleared: Set<String> = emptySet(),
    val closes: Boolean = false,
    val todos: List<TodoStep> = emptyList(),
    val pick: SnoozePick? = null,
    val undo: UndoPlan? = null,
    val fromNotification: Boolean = false,
) {
    // The Gmail threads it changes.
    val threads: Set<String> get() = changes.mapTo(mutableSetOf()) { it.threadId } + snoozes.keys + unsnoozed

    // Both plans as one, [other] after this; the undo bar names the later one's action when it
    // has anything to take back.
    operator fun plus(other: ActionPlan): ActionPlan = ActionPlan(
        keys = keys + other.keys,
        changes = changes + other.changes,
        snoozes = snoozes + other.snoozes,
        unsnoozed = unsnoozed + other.unsnoozed,
        cleared = cleared + other.cleared,
        closes = closes || other.closes,
        todos = todos + other.todos,
        pick = pick ?: other.pick,
        undo = joinedUndo(undo, other.undo, other.todos.isNotEmpty()),
        fromNotification = fromNotification || other.fromNotification,
    )
}

// Two plans' undo as one; [second]'s kind leads when it takes back threads or [secondHasTodos].
private fun joinedUndo(first: UndoPlan?, second: UndoPlan?, secondHasTodos: Boolean): UndoPlan? {
    if (first == null) return second
    if (second == null) return first
    val lead = if (second.items.isNotEmpty() || secondHasTodos) second else first
    val touched = if (first.touched == null && second.touched == null) null else first.touched.orEmpty() + second.touched.orEmpty()
    return UndoPlan(lead.kind, (first.items + second.items).distinctBy { it.id }, lead.pinned, touched)
}

// What carrying out a plan did: whether it changed anything, the undo it offered, the queue's
// save to the device, and the snooze writes, which end once Firestore has them.
private data class Carried(
    val acted: Boolean = false,
    val entry: UndoEntry? = null,
    val saving: Job? = null,
    val written: List<Task<Void>> = emptyList(),
)

// The selection bar's action each action key stands for.
private val SELECTION_KEY_ACTIONS = mapOf(
    ListCommands.DONE to SelectionAction.DONE,
    ListCommands.SNOOZE to SelectionAction.SNOOZE,
    ListCommands.PIN to SelectionAction.PIN,
    ListCommands.LABEL to SelectionAction.LABEL,
    ListCommands.SPAM to SelectionAction.SPAM,
    ListCommands.READ to SelectionAction.READ,
    ListCommands.UNREAD to SelectionAction.READ,
)

/** The actions the list's keys ask for ([ListEvent.Command]). */
object ListCommands {
    /** Done (e) */
    const val DONE = "done"

    /** Snooze (b), which opens the snooze menu */
    const val SNOOZE = "snooze"

    /** Pin or unpin (p) */
    const val PIN = "pin"

    /** Labels (l), which opens the label menu */
    const val LABEL = "label"

    /** Report spam, or not spam (!) */
    const val SPAM = "spam"

    /** Mark read (Shift+I) */
    const val READ = "read"

    /** Mark unread (Shift+U) */
    const val UNREAD = "unread"
}

/** Where an action came from, as the list hears of it ([ListEvent.Act]). */
data class ActOrigin(
    /** a row, the open thread, the selection or a notification */
    val from: ActSource,
    /** whether it ends the selection it acted on */
    val clearSelection: Boolean = false,
) {
    /** The origins that aren't the selection. */
    companion object {
        /** a row's swipe, button or key */
        val ROW = ActOrigin(ActSource.ROW)

        /** the open thread */
        val THREAD = ActOrigin(ActSource.THREAD)

        /** a notification */
        val NOTIFICATION = ActOrigin(ActSource.NOTIFICATION)

        /** the selection bar applying [action] */
        fun selection(action: SetAction) = ActOrigin(ActSource.SELECTION, clearsSelection(action))
    }
}

/**
 * The model as one value that every change settles before anyone sees it ([settle]), then hears of
 * once it is in ([changed]).
 */
private class ModelFlow(
    initial: MailModel,
    private val settle: (MailModel, MailModel) -> MailModel,
    private val changed: (MailModel, MailModel) -> Unit,
) {
    private val state = MutableStateFlow(initial)

    /** the model, to collect */
    val flow: StateFlow<MailModel> = state

    /** the model now */
    val value: MailModel get() = state.value

    /** Change the model with [transform], settled. */
    fun update(transform: (MailModel) -> MailModel) {
        while (true) {
            val before = state.value
            val after = settle(before, transform(before))
            if (state.compareAndSet(before, after)) {
                if (after !== before) changed(before, after)
                return
            }
        }
    }
}

// Whether two models list the same sections: everything the sections are made from is the same.
@Suppress("ComplexCondition")
private fun sameLayoutInputs(first: MailModel, second: MailModel): Boolean =
    first.view == second.view &&
        first.summaries === second.summaries &&
        first.lists === second.lists &&
        first.snoozeDocs === second.snoozeDocs &&
        first.woken === second.woken &&
        first.labels === second.labels &&
        first.labelPrefDocs === second.labelPrefDocs &&
        first.held === second.held &&
        first.todos === second.todos &&
        first.emailTodos === second.emailTodos &&
        first.outboxDrafts == second.outboxDrafts &&
        first.todosOn == second.todosOn &&
        first.todoPages == second.todoPages &&
        first.focusOn == second.focusOn &&
        first.now == second.now &&
        first.list.open == second.list.open

// The model with [step]'s todos changed, as the todo store will show them.
private fun MailModel.withTodos(step: TodoStep): MailModel = copy(
    todos = todos.mapNotNull { todo -> if (todo.id in step.ids) step.show(todo) else todo },
    emailTodos = if (step.deletesEmail) emailTodos.filterNot { it.id in step.ids } else emailTodos,
)

/** Threads as the selection bar's rules see them (a joined row as one); in Snoozed every one counts as snoozed. */
fun MailModel.selectedThreads(ids: List<String>): List<SelectedThread> {
    val inSnoozed = view == View.Fixed(Mailbox.SNOOZED)
    return ids.mapNotNull { id ->
        val thread = rowSummary(id) ?: return@mapNotNull null
        val status = when {
            inSnoozed -> SelectedStatus.SNOOZED
            else -> when (rowStatus(id)) {
                SnoozeStatus.SNOOZED -> SelectedStatus.SNOOZED
                SnoozeStatus.LAPSED -> SelectedStatus.LAPSED
                else -> SelectedStatus.NONE
            }
        }
        SelectedThread(id, thread.labelIds, status)
    }
}

// Gmail's answer to a filter that already exists.
private fun isDuplicateFilter(error: GmailException): Boolean =
    error.status == HttpURLConnection.HTTP_BAD_REQUEST && error.message.orEmpty().contains("exists", ignoreCase = true)

/** How an unsubscribe went. */
sealed interface UnsubscribeResult {
    /** the sender took the one-click request */
    data object Unsubscribed : UnsubscribeResult

    /** the request mail is on its way */
    data object Requested : UnsubscribeResult

    /** the sender's page has to be opened */
    data class OpenLink(
        /** the page to open */
        val url: String,
    ) : UnsubscribeResult

    /** Gmail access has to be granted again first */
    data object NeedsSignIn : UnsubscribeResult

    /** there was no connection */
    data object Offline : UnsubscribeResult

    /** it didn't work */
    data object Failed : UnsubscribeResult
}

/** One thread as it was before an action, for undo. */
data class UndoItem(
    /** Gmail thread id */
    val id: String,
    /** its labels before */
    val labelIds: List<String>,
    /** its snooze before, or null */
    val snooze: SnoozeDoc?,
)

/** The one action undo can take back. */
data class UndoEntry(
    /** what the action was */
    val kind: UndoKind,
    /** the threads it touched, as they were */
    val items: List<UndoItem>,
    /** what to take back from the learned snooze counts, for a snooze */
    val statsUndo: SnoozeStatsUndo? = null,
    /** whether the action pinned (rather than unpinned), for the bar's text */
    val pinned: Boolean = false,
    /** the todos it changed, as they were, and the copies it made */
    val todos: TodoUndo? = null,
    /** the labels a label change, move or read change touched, which undo puts back; null for the usual ones */
    val labels: List<String>? = null,
)

// Two todo undos as one: [first]'s todos as they were come back before [second]'s.
private fun joinTodoUndo(first: TodoUndo?, second: TodoUndo): TodoUndo =
    if (first == null) second else TodoUndo(first.before + second.before, first.spawned + second.spawned, first.emailBefore + second.emailBefore)

/** Whether an error means the device is offline, as opposed to Gmail or the server refusing. */
fun isOffline(error: Throwable): Boolean =
    isNetworkError(error) || error is FirebaseNetworkException || isUnreachableCall(error)

private const val MS_PER_SECOND = 1_000L

// Each Gmail backoff, in a debuggable build's log only, without ids or content.
private fun backoffLog(context: Context): (Backoff) -> Unit {
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    return { backoff ->
        if (debuggable) {
            Log.d(TAG, "Gmail backoff: ${backoff.status} ${backoff.reason}, strike ${backoff.strike}, pause ${backoff.pauseMs} ms, ${backoff.priority}")
        }
    }
}

private fun errorText(error: Throwable, pausedUntil: Long = 0L): String {
    val seconds = (pausedUntil - System.currentTimeMillis() + MS_PER_SECOND - 1) / MS_PER_SECOND
    return when {
        isOffline(error) -> "You're offline."
        error is GmailTimeoutException -> "Gmail didn't answer in time."
        isRateLimited(error) && seconds > 0 -> "Gmail asked us to slow down. Trying again in $seconds s."
        isRateLimited(error) -> "Gmail asked us to slow down. Trying again shortly."
        error is GmailException -> "Gmail said: ${error.message}"
        else -> "Something went wrong."
    }
}

/**
 * Everything the signed-in screens share: lists, threads, snoozes, the offline queue and undo.
 *
 * State lives in [model] (pure data; see [MailModel] for what's derived from it) and [undo]. All
 * changes run on the main thread; Gmail and the cache are called from coroutines.
 */
class MailState(
    private val context: Context,
    /** Firebase user id */
    val uid: String,
    email: String,
    tokens: TokenSource,
    firestore: FirebaseFirestore,
    /** called when Gmail can't be reached and queued changes wait, so they get sent later */
    private val onPendingChanges: () -> Unit,
    /** called with a thread whose Done from a notification Gmail refused for good */
    private val onNotificationDoneFailed: (String) -> Unit,
    /** whether focus mode is on */
    private val focusOn: StateFlow<Boolean>,
    /** how Gmail is reached */
    transport: HttpTransport = UrlConnectionTransport,
    /** where Gmail requests and their decoding run */
    gmailIo: CoroutineDispatcher = Dispatchers.IO,
    /** the pacing every Gmail call waits for */
    gate: RateGate = RateGate(onBackoff = backoffLog(context)),
    /** called at start while the saved copy of mail has never been filled, so it fills in the background */
    private val onSavedMailUnfilled: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = MailDatabase.open(context, uid)
    private val cache = MailCache(database.mailDao())

    // Bundle labels the device remembers holding inbox threads, as last saved; read before the first inbox page.
    private var bundlesRemembered: Set<String> = emptySet()

    private val snoozeDocs = SnoozeDocs(firestore, uid) { docs ->
        val ends = state.value.snoozeDocs.mapValues { it.value.snoozeUntil }
        state.update { it.copy(snoozeDocs = docs) }
        scope.launch(BACKGROUND) { fetchSnoozedSummaries() }
        // Reminders only care when each snooze ends.
        if (docs.mapValues { it.value.snoozeUntil } != ends) SnoozeAlarm.checkSoon(context)
    }

    private val labelPrefDocs = LabelPrefDocs(firestore, uid) { docs -> state.update { it.copy(labelPrefDocs = docs) } }
    private val gmail = GmailClient(tokens, gate, gmailIo, transport = ReportingTransport(transport, ::noteConnection))
    private val functions = FirebaseFunctions.getInstance(FirebaseConfig.FUNCTIONS_REGION)
    // Attachments being fetched, then the fetched ones, most recently used kept up to ATTACHMENT_CACHE_BYTES.
    private val fetchingAttachments = ConcurrentHashMap<String, Deferred<ByteArray>>()
    private val attachments = object : LruCache<String, ByteArray>(ATTACHMENT_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    // The flush running, which a flush asked for meanwhile shares, and whether one was.
    private var flushRun: Deferred<Boolean>? = null
    private var flushAgain = false
    // Gmail's ids for local ones made this session, or null for one Gmail refused, so a change made
    // from a screen still holding the local id names the right label or filter.
    private val madeIds = mutableMapOf<String, String?>()
    private val queueLoaded = CompletableDeferred<Unit>()
    // Whether the label and filter queue is written to the device: only once its saved copy was read,
    // so edits made before that (or after it couldn't be read) never replace it.
    private var accountQueueSaves = false
    // A thread opened from a notification's Reply, waiting for its messages to start the reply, and the reply's text so far.
    private var pendingReply: String? = null

    // Saved threads' bodies ready to show, the latest shown held so reopening one reads nothing.
    private val savedBodies = object : LinkedHashMap<String, ThreadDetail>(SAVED_BODIES_HELD, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThreadDetail>): Boolean = size > SAVED_BODIES_HELD
    }
    private var pendingReplyText = ""

    /** The account's learned snooze counts. */
    val snoozeStats = SnoozeStatsStore(firestore, uid, scope)

    // Reads where the list on screen sits, just before each new layout; null while none shows.
    private var anchorSource: (() -> ListAnchor?)? = null

    // Lets go of closed bundles' places once their close has played.
    private var releaseHeld: Job? = null

    // The open thread's next load after Gmail's usage limit failed it.
    private var detailRetry: Job? = null

    // The rest an action carries out once Gmail lists what it acts on, with the undo it joins.
    private var undoRest: Pair<UndoEntry, Job>? = null

    private val state = ModelFlow(MailModel(email = email, now = System.currentTimeMillis(), focusOn = focusOn.value), ::settleModel, ::modelChanged)

    /** Lists, threads and snoozes. */
    val model: StateFlow<MailModel> = state.flow

    /** Whether the app last found itself offline; what waits for a connection tries again once it is false. */
    val offline: StateFlow<Boolean> = state.flow.map { it.offline }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, state.flow.value.offline)

    /**
     * Take what [event] says of the connection: each Gmail call's outcome, and the device's network
     * coming or going. The only writer of [offline]; called from any thread, applied on the main one.
     */
    fun noteConnection(event: ConnectionEvent) {
        scope.launch {
            if (offlineAfter(state.value.offline, event) != state.value.offline) {
                state.update { model -> model.copy(offline = offlineAfter(model.offline, event)) }
            }
        }
    }

    private val listSheets = MutableSharedFlow<ListEffect.Act>(extraBufferCapacity = 1)

    /** Action keys the screen carries out by opening a menu: Snooze and Labels. */
    val sheetRequests: SharedFlow<ListEffect.Act> = listSheets

    private val undoState = MutableStateFlow<UndoEntry?>(null)

    /** The action undo would take back, while the bar shows. */
    val undo: StateFlow<UndoEntry?> = undoState

    private val account = AccountStore(firestore, uid)

    /** The account's todos, while the Show todos setting is on. */
    val todos = TodoStore(firestore, uid, scope) { SnoozeAlarm.checkSoon(context) }.also { store ->
        store.onClearedOut = { id -> deleteTodos(listOf(id)) }
    }
    private var encryptedSearch: Job? = null

    /** Encrypted mail: the key, passkeys, decrypting and recipients' keys. */
    val encryption = Encryption(context.applicationContext, uid, email, scope, account, gmail, database.mailDao())

    /** Where unsubscribing from each message stands this session (see [unsubscribe]). */
    val unsubscribes = Unsubscribes(scope) { unsubscribe(it) }

    /** Compose and send: composer, drafts, outbox, settings and contacts. */
    val sending = Sending(
        appContext = context.applicationContext,
        uid = uid,
        scope = scope,
        gmail = gmail,
        tokens = tokens,
        firestore = firestore,
        account = account,
        encryption = encryption,
        cache = cache,
        dao = database.mailDao(),
        accountEmail = email,
        onPendingChanges = { onPendingChanges() },
        attachmentBytes = ::attachmentBytes,
    )

    /** The @hafa.cc address flow. */
    val addresses = Addresses(functions)

    /** Gmail filters. */
    val filters = FilterStore(gmail, state.flow.map { it.accountQueue }.distinctUntilChanged(), scope)

    /** Calendar invites: the card's details and RSVP. */
    val invites = Invites(CalendarClient(tokens), sending, ::attachmentBytes)

    /** The mail saved on this device for reading offline. */
    val savedMail = OfflineCopy(gmail, cache, SavedMailHost(), scope)

    // Gmail's history id as of the last reload that came back whole, or null before one.
    private var syncedHistoryId: String? = null

    // Whether the last reload missed something (a list, a thread, a bundle), so the next check reloads.
    private var loadFailed = false

    // Bundle labels whose own list was asked for this session, each with its first fetch.
    private val bundleRequests = mutableMapOf<String, Deferred<Boolean>>()

    // Bundle labels whose own list came back this session; only these leave the inbox's own list.
    private val bundlesFetched = mutableSetOf<String>()

    // Bundle fetches in flight, by label: whether each is a next page, and the fetch.
    private val bundleLoads = mutableMapOf<String, Pair<Boolean, Deferred<Boolean>>>()

    // Bundle labels whose own list was fetched past its first page.
    private val bundlesPaged = mutableSetOf<String>()

    // Bundle labels to fetch again when the inbox shows (history touched them, or a fetch failed);
    // each stays until a fetch succeeds.
    private val staleBundles = mutableSetOf<String>()

    // Whether the pinned list was fetched since the inbox's last first page.
    private var pinnedFresh = false

    // Threads Gmail answered "not found" for this session, not asked for again.
    private val gone = mutableSetOf<String>()

    // Label renames the todos' tags still have to follow, oldest first; kept on the device.
    private var pendingRetags: List<TagRename> = emptyList()

    // The run applying [pendingRetags], while one runs.
    private var retagRun: Job? = null

    // Saves of [pendingRetags] one at a time, so the newest lands last.
    private val retagSaves = Mutex()

    // View keys to load again once the load running for them ends.
    private val reloadAfter = mutableSetOf<String>()

    // View keys whose next page was asked for while a load ran, fetched once it ends.
    private val moreAfter = mutableSetOf<String>()

    private var refreshing: Job? = null

    // How urgent the running refresh's reads are; raised by a refresh asked for meanwhile.
    private var refreshPriority: GmailPriority? = null

    // A refresh asked for while one ran (and whether forced), run when it ends.
    private var refreshQueued: Boolean? = null

    private var wakeupCheck: Job? = null
    private var wakeupCheckAgain = false

    // A check's history scan its page limit cut short, which the next check goes on with.
    private var wakeScan: WakeScan? = null
    private var snoozedFetch: Job? = null
    private var snoozedFetchAgain = false

    // Gmail's drafts as last listed, while the Outbox holds any; and which outbox drafts that was for.
    private var draftRefs: List<GmailDraftRef> = emptyList()
    private var draftRefsFor: Set<String>? = null
    // The outbox drafts the last listing that came back was made for.
    private var draftRefsListedFor: Set<String> = emptySet()

    // Drafts this device has sent or is sending, by draft id, so one whose doc says sent is known
    // by its message after it leaves the sends.
    private val sendingKnown = mutableMapOf<String, GmailDraftRef>()

    // Whether Drafts was last listed from the saved copy: always once it is filled, before that
    // only when Gmail couldn't be reached.
    private var draftsFromSaved = false

    private val localOnlyState = MutableStateFlow<List<LocalDraft>>(emptyList())

    /** This device's new messages in no Gmail thread yet, newest first, which Drafts shows above its threads. */
    val localOnly: StateFlow<List<LocalDraft>> = localOnlyState

    // Everything [init] starts can run before the constructor returns (Main.immediate), so every field
    // it reaches is declared above it.
    init {
        snoozeDocs.start()
        labelPrefDocs.start()
        snoozeStats.start()
        // Drafts are sealed with a key deleted when the account's key changes on another device, and not written down at all while locked.
        sending.drafts.persists = encryption::keepsComposerState
        sending.online = { !state.value.offline }
        sending.offlineDrafts = ::offlineDrafts
        encryption.onLocked = {
            sending.drafts.lock()
            savedBodies.clear()
        }
        sending.start()
        scope.launch { state.flow.map { it.detail }.distinctUntilChanged().collect(sending::settleSent) }
        scope.launch {
            sending.outbox.docs.collect { docs ->
                // Published at once, before the listing: a new doc leaves Drafts by its thread meanwhile.
                publishOutboxDrafts(docs)
                if (outboxDraftIds(docs) != draftRefsFor) loadDraftRefs()
                savedMail.noteSent(docs, sendingKnown.values.toList())
                reloadSavedDrafts()
            }
        }
        scope.launch {
            sending.sendingDrafts.collect { sends ->
                for (send in sends) {
                    val draftId = send.draftId ?: continue
                    val messageId = send.messageId ?: continue
                    sendingKnown[draftId] = GmailDraftRef(draftId, messageId, send.threadId.orEmpty())
                }
                publishOutboxDrafts(sending.outbox.docs.value)
                reloadSavedDrafts()
            }
        }
        scope.launch { sending.localDrafts.collect { reloadSavedDrafts() } }
        encryption.start()
        encryption.onUnlocked = {
            savedBodies.clear()
            state.value.openId?.let { scope.launch { loadDetail(it, fromCache = false) } }
            scope.launch { sending.flush() }
        }
        scope.launch {
            account.settings.map { it.showTodos }.distinctUntilChanged().collect { on ->
                todos.setEnabled(on)
                withContext(Dispatchers.IO) { offerTodoShortcut(context, on) }
            }
        }
        scope.launch {
            todos.snapshot.collect { snapshot ->
                state.update { it.copy(todosOn = snapshot.enabled, todos = snapshot.placed, emailTodos = snapshot.emailTodos) }
            }
        }
        scope.launch { encryption.previews.collect { previews -> state.update { it.copy(previews = previews) } } }
        // Emails a label or search lists for their todo's text, read once the list or the todos name them.
        scope.launch(BACKGROUND) {
            state.flow.map { it.missingTaggedThreads }.distinctUntilChanged().collectLatest { ids ->
                if (ids.isNotEmpty()) fetchTaggedSummaries()
            }
        }
        // Nothing is written when a snooze ends, so the clock has to advance itself.
        scope.launch {
            state.flow.map { it.nextWake }.distinctUntilChanged().collectLatest { nextWake ->
                while (true) {
                    val current = System.currentTimeMillis()
                    val wakeAt = minOf(nextWake?.takeIf { it > current } ?: Long.MAX_VALUE, current + MAX_NOW_STALENESS_MS)
                    delay(maxOf(MIN_TICK_MS, wakeAt - current))
                    refreshNow()
                }
            }
        }
        // A notification goes once the mail shows its thread read, out of the inbox or snoozed, here or elsewhere.
        scope.launch {
            state.flow.collectLatest { model ->
                delay(NOTIFICATION_CHECK_MS)
                Notifier.clearStale(context) { threadId, arrivedAt ->
                    mailNotificationStale(arrivedAt, model.summaries[threadId], model.statusOf(threadId) == SnoozeStatus.SNOOZED)
                }
            }
        }
        scope.launch {
            undoState.collectLatest { entry ->
                if (entry != null) {
                    delay(UNDO_MS)
                    undoState.value = null
                }
            }
        }
        scope.launch { undoState.collect { if (it == null) dispatch(ListEvent.UndoExpired) } }
        // A bundle label on a loaded inbox thread gets its own list; focus mode shows no bundles.
        scope.launch {
            state.flow.map { model ->
                if (model.view != View.INBOX || model.focusOn) emptyList() else bundlesDue(model, emptySet())
            }.distinctUntilChanged().collect { launch(BACKGROUND) { fetchDueBundles() } }
        }
        // A bundle whose loaded threads ran out or short, or whose end shows, fetches its next page.
        scope.launch {
            state.flow.map { it.bundlesToTopUp }.distinctUntilChanged().collect { labels ->
                for (labelId in labels) launch(if (labelId in state.value.openBundles) VISIBLE else BACKGROUND) { loadBundle(labelId, more = true) }
            }
        }
        // A label no longer bundled comes back into the inbox's own list.
        scope.launch {
            state.flow.map { it.bundleLabelIds }.distinctUntilChanged().collect { bundled ->
                val dropped = bundleRequests.keys.filterNot { it in bundled }.toSet()
                if (dropped.isNotEmpty()) {
                    bundleRequests -= dropped
                    bundlesFetched -= dropped
                    staleBundles -= dropped
                    rememberBundles()
                    // A load already running asked with the label left out, so this one waits for it.
                    launch { loadView(View.INBOX) }
                }
            }
        }
        // Switching focus mode lists the inbox again, its own list or only its pinned threads.
        scope.launch {
            focusOn.drop(1).distinctUntilChanged().collect { on ->
                state.update { it.copy(focusOn = on) }
                val view = state.value.view
                if (view == View.INBOX) {
                    showCached(state.value.listKey(view))
                    loadView(view)
                }
            }
        }
        scope.launch { start() }
    }

    private suspend fun start() {
        try {
            val labels = cache.labels()
            val queued = cache.queue()
            val accountQueued = cache.accountQueue()
            bundlesRemembered = cache.bundles()
            val retags = cache.retags()
            if (retags.isNotEmpty()) setPendingRetags(retags + pendingRetags)
            // Label and filter changes made while the device was being read go after the ones it kept.
            val early = state.value.accountQueue
            val joined = if (accountQueued == null) early else joinAccountQueues(accountQueued, early)
            state.update {
                it.copy(
                    labels = labelsWithPending(labels ?: it.labels, joined),
                    labelsLoaded = it.labelsLoaded || labels != null,
                    queue = queued,
                    accountQueue = joined,
                )
            }
            if (accountQueued == null) {
                Log.w(TAG, "saved label and filter edits unreadable; left as they are")
            } else {
                accountQueueSaves = true
                if (early.isNotEmpty()) runSuspendCatching { cache.putAccountQueue(joined) }.onFailure { Log.w(TAG, "queue write failed", it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "cache read failed", error)
        }
        queueLoaded.complete(Unit)
        savedMail.load()
        if (!savedMail.filled) onSavedMailUnfilled()
        showCached(state.value.view.key)
        showCached(state.value.listKey(state.value.view))
        showCached(PINNED_KEY)
        scope.launch { flush() }
        runRetags()
        runRefresh(force = true, CallPriority.BACKGROUND)
    }

    /** Change one label's drawer, bundle or icon setting (`icon` takes an icon id or "none"). */
    fun setLabelPrefs(labelId: String, patch: Map<String, Any>) {
        val docs = state.value.labelPrefDocs
        state.update { it.copy(labelPrefDocs = docs + (labelId to docs[labelId].orEmpty() + patch)) }
        labelPrefDocs.write(labelId, patch)
    }

    /** Bytes this account's mail cache takes on the device, nearly all of it saved mail. */
    suspend fun savedMailBytes(): Long = withContext(Dispatchers.IO) {
        val file = context.getDatabasePath(MailDatabase.fileName(uid))
        listOf(file, File("${file.path}-wal")).sumOf { it.length() }
    }

    /** Stop listeners and timers, wait for work under way to end, and close the cache. */
    private suspend fun dispose() {
        snoozeDocs.stop()
        labelPrefDocs.stop()
        snoozeStats.stop()
        todos.stop()
        sending.close()
        // Joined, so nothing still running writes a file or makes a key after the deletes below.
        scope.coroutineContext[Job]?.cancelAndJoin()
        encryption.forgetDevice()
    }

    /**
     * How many changes (to threads' labels, and to labels and filters) and drafts (or sends waiting
     * on them) haven't reached Gmail, as (changes, drafts).
     */
    suspend fun unsyncedWork(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val dao = database.mailDao()
        unsyncedChanges(dao.queue(), state.value.accountQueue) to dao.drafts().count { it.dirty || it.deleted || it.pendingSendJson != null }
    }

    /**
     * Drop this account from the device, as on sign-out. With [parking] (a sign-out forced by lost
     * Gmail access), its unsent work (queued label changes, queued label and filter edits, label
     * renames the todos' tags haven't followed yet, and drafts not yet in Gmail or waiting to send)
     * is kept there for its next sign-in first, still sealed, and so is the key that sealed it.
     */
    suspend fun clearDevice(parking: ParkedWork?) {
        dispose()
        val parked = parking != null && runSuspendCatching {
            val dao = database.mailDao()
            val drafts = dao.drafts()
            val queue = dao.queue()
            val accountQueue = cache.accountQueue().orEmpty()
            // Renames still being written are only in memory once the device's copy was read.
            val retags = if (queueLoaded.isCompleted) pendingRetags else cache.retags()
            withContext(Dispatchers.IO) { parking.park(uid, drafts, queue, accountQueue, retags, DraftStore.directoryOf(context, uid)) }
        }.onFailure { Log.w(TAG, "keeping unsent work failed", it) }.getOrDefault(false)
        withContext(Dispatchers.IO) {
            database.close()
            context.deleteDatabase(MailDatabase.fileName(uid))
            DraftStore.clear(context, uid, keepKey = parked)
            Addresses.clear(context, uid)
        }
    }

    // Moves the clock lists are computed against to now.
    private fun refreshNow() {
        state.update { it.copy(now = System.currentTimeMillis()) }
    }

    /** The app came to the front or the network came back: catch up. */
    fun wake() {
        refreshNow()
        snoozeDocs.reattach()
        labelPrefDocs.reattach()
        snoozeStats.reattach()
        todos.reattach()
        sending.refresh()
        scope.launch { flush() }
        runRetags()
        refresh(force = false)
    }

    private fun setList(key: String, transform: (LoadedList) -> LoadedList) {
        state.update { model -> model.copy(lists = model.lists + (key to transform(model.lists[key] ?: LoadedList()))) }
    }

    private fun putSummaries(threads: List<ThreadSummary>) {
        if (threads.isEmpty()) return
        state.update { model -> model.copy(summaries = model.summaries + threads.associateBy { it.id }) }
        scope.launch {
            runSuspendCatching { cache.putSummaries(threads) }.onFailure { Log.w(TAG, "cache write failed", it) }
        }
    }

    // Threads Gmail no longer has leave the screen and the device.
    private fun removeGone(ids: List<String>) {
        if (ids.isEmpty()) return
        gone += ids
        forgetBodies(ids)
        state.update { model -> model.copy(summaries = model.summaries - ids.toSet()) }
        scope.launch {
            runSuspendCatching { cache.deleteSummaries(ids) }.onFailure { Log.w(TAG, "cache write failed", it) }
        }
    }

    private suspend fun showCached(key: String) {
        if (state.value.lists[key]?.loaded == true) return
        try {
            val list = cache.list(key)
            val missing = list?.ids.orEmpty().filterNot { it in state.value.summaries }
            val found = cache.summaries(missing)
            if (found.isNotEmpty()) state.update { model -> model.copy(summaries = model.summaries + found.associateBy { it.id }) }
            if (list != null && state.value.lists[key]?.loaded != true) setList(key) { list }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "cache read failed", error)
        }
    }

    // Gmail doesn't know about queued changes yet, so a fresh copy would undo them on screen.
    private fun withQueued(summary: ThreadSummary): ThreadSummary = summary.withQueued(state.value.queue)

    /**
     * Fetch summaries for threads whose history changed or that aren't known yet; returns the first
     * read that failed, null when every one came back. A thread Gmail no longer has is remembered in
     * [gone], not a failure.
     */
    private suspend fun fetchSummaries(stubs: List<GmailThreadStub>): Throwable? {
        val changedBefore = locallyChanged()
        val known = state.value.summaries
        val stale = stubs.filter { stub -> known[stub.id].let { it == null || it.historyId != stub.historyId } }
        val results = mapLimited(stale, MAX_CONCURRENT) { gmail.getThread(it.id, full = false) }
        var failure: Throwable? = null
        val missing = mutableListOf<String>()
        results.forEachIndexed { index, result ->
            val error = result.exceptionOrNull() ?: return@forEachIndexed
            if (isNotFound(error)) {
                missing += stale[index].id
            } else {
                failure = failure ?: error
            }
        }
        val summaries = withContext(Dispatchers.Default) { results.mapNotNull { result -> result.getOrNull()?.let(::summarizeThread) } }
        dropSnoozesArchivedElsewhere(summaries, changedBefore)
        putSummaries(summaries.map(::withQueued))
        removeGone(missing)
        return failure
    }

    // Threads with a change of this device's own not yet in Gmail: queued, or open to undo.
    private fun locallyChanged(): Set<String> =
        state.value.queue.mapTo(mutableSetOf()) { it.threadId } + undoState.value?.items.orEmpty().map(UndoItem::id)

    // Fresh Gmail copies of snoozed threads archived elsewhere count as Done. [changedBefore] holds
    // threads changed here when the fetch began, whose change may have reached Gmail only after it.
    private fun dropSnoozesArchivedElsewhere(fresh: List<ThreadSummary>, changedBefore: Set<String>) {
        val model = state.value
        val changed = changedBefore + locallyChanged()
        val now = System.currentTimeMillis()
        for (summary in fresh) {
            if (snoozeArchivedElsewhere(model.snoozeDocs[summary.id], summary.labelIds, summary.historyId, now, summary.id in changed)) {
                snoozeDocs.delete(summary.id)
            }
        }
    }

    /**
     * The snoozes the phone's reminders wait on, as this session's listeners hold them: threads' and,
     * while todos show, todos' not done, ending no earlier than [from]. Null unless the snoozes', the
     * account's and (while they show) the todos' listeners are all current.
     */
    fun reminderSnoozes(from: Long, zone: ZoneId): ReminderSnoozes? {
        val settings = account.settings.value
        val todoSnapshot = todos.snapshot.value
        val todosReady = if (settings.showTodos) todoSnapshot.enabled && todos.live else !todoSnapshot.enabled
        if (!snoozeDocs.live || !account.live || !settings.loaded || !todosReady) return null
        val threads = state.value.snoozeDocs.mapNotNull { (id, doc) ->
            doc.snoozeUntil?.takeIf { it >= from }?.let { SnoozeEnd(id, epochToIso(it, zone)) }
        }
        val fromIso = epochToIso(from, zone)
        val todoEnds = todoSnapshot.todos.mapNotNull { todo -> todoSnoozeEnd(todo)?.takeIf { it.until >= fromIso }?.let { it to todo } }
        val model = state.value
        // With Skip notifications for bundled mail on, a thread or todo a bundle holds comes back quietly.
        val quiet = if (settings.quietBundles) {
            val quietThreads = threads.filter { end ->
                val labels = model.summaries[end.itemId]?.labelIds.orEmpty()
                val tagged = model.threadTodos[end.itemId]?.let { model.labelTags.labelIds(it.text) }.orEmpty()
                bundledOnWake(labels + tagged, PINNED_LABEL in labels, model.bundleLabelIds)
            }
            val quietTodos = todoEnds.filter { (_, todo) -> bundledOnWake(model.labelTags.labelIds(todo.text), todo.pinned, model.bundleLabelIds) }
            (quietThreads + quietTodos.map { it.first }).mapTo(hashSetOf()) { it.itemId }
        } else {
            emptySet()
        }
        return ReminderSnoozes(threads + todoEnds.map { it.first }, todoEnds.associate { (end, todo) -> end.itemId to todo.text }, quiet)
    }

    /** Whether newer mail already woke [threadId]'s snooze, and was notified for itself. */
    fun wokenByMail(threadId: String): Boolean = threadId in state.value.woken

    /** [threadId]'s summary as last seen, from memory or the cache; null when unknown. */
    suspend fun summary(threadId: String): ThreadSummary? =
        state.value.summaries[threadId] ?: runSuspendCatching { cache.summaries(listOf(threadId)).firstOrNull() }.getOrNull()

    // Fetches the summaries snoozes need; one fetch at a time, and a call meanwhile runs once after it.
    private suspend fun fetchSnoozedSummaries() {
        val running = snoozedFetch
        if (running != null && running.isActive) {
            snoozedFetchAgain = true
            running.join()
            return
        }
        val job = scope.launch {
            do {
                snoozedFetchAgain = false
                fetchSnoozedSummariesOnce()
            } while (snoozedFetchAgain)
        }
        snoozedFetch = job
        job.join()
    }

    private suspend fun fetchSnoozedSummariesOnce() {
        val model = state.value
        val ids = model.snoozeDocs.filter { (id, doc) ->
            id !in model.summaries && id !in gone && inLapseWindow(doc.snoozeUntil, model.now)
        }.keys.toList()
        if (ids.isEmpty()) return
        val found = runSuspendCatching { cache.summaries(ids) }.getOrDefault(emptyList())
        if (found.isNotEmpty()) state.update { it.copy(summaries = it.summaries + found.associateBy { summary -> summary.id }) }
        val missing = ids.filterNot { it in state.value.summaries }
        try {
            fetchSummaries(missing.map { GmailThreadStub(it) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "snoozed threads read failed", error)
        }
        // A snooze on a thread Gmail no longer has (deleted for good) can never wake; clear it.
        for (id in missing) {
            if (id in gone && id in state.value.snoozeDocs) snoozeDocs.delete(id)
        }
    }

    // Reads the emails the current list shows for their todo's text that this device hasn't got:
    // from the cache, else Gmail.
    private suspend fun fetchTaggedSummaries() {
        val ids = state.value.missingTaggedThreads.filterNot { it in gone }
        if (ids.isEmpty()) return
        state.update { it.copy(readingTodoEmails = true) }
        try {
            val found = runSuspendCatching { cache.summaries(ids) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) state.update { it.copy(summaries = it.summaries + found.associateBy { summary -> summary.id }) }
            val missing = ids.filterNot { it in state.value.summaries }
            fetchSummaries(missing.map { GmailThreadStub(it) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "todo emails read failed", error)
        } finally {
            state.update { it.copy(readingTodoEmails = false) }
        }
    }

    // Checks for newer mail on snoozed threads; a call while one runs waits for it and runs once
    // more after it, so a snooze written meanwhile is checked too.
    private suspend fun checkWakeups() {
        val running = wakeupCheck
        if (running != null && running.isActive) {
            wakeupCheckAgain = true
            running.join()
            return
        }
        val job = scope.launch {
            do {
                wakeupCheckAgain = false
                checkWakeupsOnce()
            } while (wakeupCheckAgain)
        }
        wakeupCheck = job
        job.join()
    }

    // Snoozes the check for newer mail watches, by thread: watermarks of those not long ended.
    private fun wakeWatermarks(model: MailModel): Map<String, String> = model.snoozeDocs.mapNotNull { (id, doc) ->
        doc.snoozeWatermark?.takeIf { inLapseWindow(doc.snoozeUntil, model.now) }?.let { id to it }
    }.toMap()

    // Reads history a few pages per check; a scan longer than that goes on at the next check, and
    // what it found is used only once it reached the end.
    private suspend fun checkWakeupsOnce() {
        val watermarks = wakeWatermarks(state.value)
        val from = scanStart(watermarks, wakeScan?.start)
        if (from == null) {
            wakeScan = null
            return
        }
        val scan = wakeScan?.takeIf { from.resume } ?: WakeScan(from.start)
        wakeScan = scan
        try {
            for (page in 0 until MAX_HISTORY_PAGES) {
                val result = gmail.listHistory(scan.start, scan.pageToken)
                scan.records += result.history.orEmpty()
                scan.pageToken = result.nextPageToken
                if (scan.pageToken == null) break
            }
            if (scan.pageToken != null) return
            wakeScan = null
            val newer = newerMail(scan.records, watermarks)
            state.update { model ->
                val current = wakeWatermarks(model)
                model.copy(
                    woken = stillFound(newer.inInbox, watermarks, current),
                    skippedInbox = stillFound(newer.skippedInbox, watermarks, current),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            wakeScan = null
        }
    }

    // The search shown runs again when the labels its `#name`s name have changed, as one fetched
    // before the labels came listed those `#name`s as todo tags.
    private fun rerunSearch(before: LabelTags) {
        val view = state.value.view as? View.Search ?: return
        if (searchTagTerms(view.query, before) != searchTagTerms(view.query, state.value.labelTags)) scope.launch { loadView(view) }
    }

    private suspend fun loadLabels() {
        try {
            val labels = gmail.listLabels()
            val drafts = gmail.getLabel(Labels.DRAFT)
            val before = state.value.labelTags
            state.update {
                it.copy(labels = labelsWithPending(labels, it.accountQueue), labelsLoaded = true, draftLabelThreads = drafts.threadsTotal ?: 0)
            }
            rerunSearch(before)
            cache.putLabels(labels)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "labels read failed", error)
        }
    }

    // Counts listings of Gmail's drafts, so only the latest one's answer is kept.
    private var draftRefsCall = 0

    // Gmail's drafts, to tell which the Outbox holds (scheduled sends show only there); none are listed while it holds none.
    private suspend fun loadDraftRefs() {
        val docs = sending.outbox.docs.value
        val held = outboxDraftIds(docs)
        draftRefsFor = held
        val call = ++draftRefsCall
        try {
            val refs = if (held.isEmpty()) emptyList() else allDraftRefs()
            // A later listing started meanwhile; its answer is the newer one.
            if (call != draftRefsCall) return
            draftRefs = refs
            draftRefsListedFor = held
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (call != draftRefsCall) return
            draftRefsFor = null
        }
        publishOutboxDrafts(sending.outbox.docs.value)
    }

    private suspend fun allDraftRefs(): List<GmailDraftRef> {
        val refs = mutableListOf<GmailDraftRef>()
        var pageToken: String? = null
        do {
            val page = gmail.listDrafts(pageToken)
            for (draft in page.drafts.orEmpty()) {
                val message = draft.message ?: continue
                refs += GmailDraftRef(draft.id, message.id, message.threadId)
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return refs
    }

    /** What the saved copy and this device know of drafts, to list and open them offline. */
    suspend fun offlineDrafts(): OfflineDraftsInput = OfflineDraftsInput(
        saved = savedMail.savedDrafts(),
        refs = savedMail.savedDraftRefs,
        docs = sending.outbox.docs.value,
        sending = sending.sendingDrafts.value,
        sent = savedMail.sentDrafts,
        local = sending.localDrafts.value.map {
            OfflineLocalDraft(it.draft.localId, it.draftThreadId ?: it.threadId, it.draft.messageId, it.draft.state)
        },
    )

    // The new messages Drafts shows by their local ids ([localOnlyDrafts]), read again as drafts change.
    private suspend fun publishLocalOnly() {
        val ids = localOnlyDrafts(offlineDrafts()).toSet()
        localOnlyState.value = sending.localDrafts.value.filter { it.draft.localId in ids }.sortedByDescending { it.draft.updatedAt }
    }

    // Drafts listed from the saved copy follow this device's drafts as they change.
    private suspend fun reloadSavedDrafts() {
        val view = View.Fixed(Mailbox.DRAFTS)
        if (state.value.view != view) return
        publishLocalOnly()
        if (!(savedMail.filled || draftsFromSaved)) return
        val ids = offlineDraftThreads(offlineDrafts())
        // Gmail's own listing may have landed while the saved copy was read.
        if (state.value.view != view || !(savedMail.filled || draftsFromSaved)) return
        loadSavedIds(state.value.listKey(view), ids, "")
    }

    // Drafts from the saved copy and this device's own drafts: once the copy is filled, or while
    // Gmail can't be reached.
    private suspend fun loadSavedDrafts(key: String): Boolean {
        draftsFromSaved = true
        return loadSavedIds(key, offlineDraftThreads(offlineDrafts()), "")
    }

    private fun publishOutboxDrafts(docs: List<OutboxDoc>) {
        val outbox = outboxDrafts(draftRefs, docs, sending.sendingDrafts.value, draftRefsListedFor)
        state.update { if (it.outboxDrafts == outbox) it else it.copy(outboxDrafts = outbox) }
    }

    /** Show a view: its cached copy at once, then Gmail's. */
    fun setView(view: View) {
        val shown = state.value.view
        if (view is View.Search && shown !is View.Search) searchOrigin = shown
        encryptedSearch?.cancel()
        encryptedSearch = null
        state.update { it.copy(view = view, list = listStep(it.list, ListEvent.Reset), detail = null, detailError = null, held = HeldPlaces()) }
        todos.setEditing(null)
        scope.launch {
            showCached(view.key)
            showCached(state.value.listKey(view))
            loadView(view)
            withContext(BACKGROUND) { loadStaleBundles() }
        }
    }

    // The view the shown search was started from, which leaving search returns to.
    private var searchOrigin: View = View.INBOX

    /** Leave the shown search for the view it was started from. */
    fun closeSearch() {
        if (state.value.view is View.Search) setView(searchOrigin)
    }

    /**
     * Fetches a view's first page (or its next one, with [more]) from Gmail; returns whether the
     * page and every thread on it came back. A first page asked for while one loads is fetched
     * again after it.
     */
    private suspend fun loadView(view: View, more: Boolean = false): Boolean {
        val snoozedSearch = view is View.Search && searchPlan(view.query).snoozedOnly
        val snoozeChecks = !more && (view == View.INBOX || view == View.Fixed(Mailbox.SNOOZED) || snoozedSearch)
        val options = listOptions(view, state.value.labelTags)
        if (options == null) {
            if (snoozeChecks) runSnoozeChecks()
            return true
        }
        val key = state.value.listKey(view)
        val current = state.value.lists[key] ?: LoadedList()
        if (current.loading) {
            // The list's end asks once per page, so a next page asked for meanwhile waits too.
            if (more) moreAfter += key else reloadAfter += key
            return false
        }
        if (more && current.nextPageToken == null) return true
        // Retry under rows whose threads couldn't be read reads them before any next page.
        if (more && current.error != null && unreadRows(current.ids).isNotEmpty()) return retryRows(key)
        if (snoozeChecks) runSnoozeChecks()
        setList(key) { it.copy(loading = true, error = null) }
        var complete = false
        try {
            var request = InboxPageRequest(options.query.orEmpty(), current.nextPageToken)
            var pinned: Deferred<Boolean>? = null
            if (key == FOCUS_KEY) {
                request = request.copy(q = focusQuery(request.q))
                if (!more) scope.launch(BACKGROUND) { loadInboxBehindFocus() }
            } else if (view == View.INBOX) {
                // Fetched bundles' threads come from their own lists, so the inbox's pages leave them
                // out; a bundle due now comes back (or fails) first.
                fetchDueBundles()
                val query = inboxQuery()
                request = if (more) inboxNextPage(query, current.nextPageToken, current.query) else InboxPageRequest(query, null)
                if (!more) pinnedFresh = false
                if (query.isNotEmpty() && !pinnedFresh) pinned = scope.async(callerPriority()) { loadPinned() }
            }
            val pageOptions = options.copy(
                query = request.q.ifEmpty { null },
                maxResults = PAGE_SIZE,
                pageToken = if (more) request.pageToken else null,
            )
            if (view == View.Fixed(Mailbox.DRAFTS) && !more) publishLocalOnly()
            if (view == View.Fixed(Mailbox.DRAFTS) && !more && savedMail.filled) {
                return finishLoad(view, key, loadSavedDrafts(key))
            }
            val savedLabels = if (savedMail.filled) savedListLabels(pageOptions) else null
            if (savedLabels != null) {
                val read = loadSaved(key, savedLabels, request.q)
                return finishLoad(view, key, read && pinned?.await() != false)
            }
            val page = gmail.listThreads(pageOptions)
            if (view == View.Fixed(Mailbox.DRAFTS) && !more) draftsFromSaved = false
            val stubs = page.threads.orEmpty()
            val readFailure = fetchSummaries(stubs)
            val pageIds = stubs.map { it.id }
            val ids = if (more) (state.value.lists[key]?.ids.orEmpty() + pageIds).distinct() else pageIds
            // Threads that couldn't be read leave no row; the error stops the list paging past them and offers Retry.
            val error = readFailure?.let { errorText(it, gmail.gate.pausedUntil.value) }
            setList(key) { LoadedList(ids, page.nextPageToken, loading = false, loaded = true, error = error, query = request.q) }
            cache.putList(key, ids, page.nextPageToken)
            if (view is View.Search && !more) searchEncrypted(view)
            complete = readFailure == null && pinned?.await() != false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (view is View.Search && !more && isOffline(error)) {
                searchCache(view)
            } else if (view == View.Fixed(Mailbox.DRAFTS) && !more && isOffline(error) && savedMail.filled) {
                loadSavedDrafts(key)
            } else {
                val hasRows = state.value.lists[key]?.ids?.isNotEmpty() == true
                // A failed next page keeps its rows and says why under them, with Retry.
                setList(key) { it.copy(loading = false, loaded = true, error = if (hasRows && !more) null else errorText(error, gmail.gate.pausedUntil.value)) }
            }
        }
        return finishLoad(view, key, complete)
    }

    // What a load returns once its list is set: a load asked for meanwhile runs now.
    private suspend fun finishLoad(view: View, key: String, complete: Boolean): Boolean = when {
        reloadAfter.remove(key) -> loadView(view)
        moreAfter.remove(key) -> loadView(view, more = true) && complete
        else -> complete
    }

    // A list of the inbox's own threads, whole, from the saved copy, newest first; returns whether
    // every thread on it has a row (one whose row the device lacks is read from Gmail).
    private suspend fun loadSaved(key: String, labels: List<String>, query: String): Boolean =
        loadSavedIds(key, savedMail.savedIds(labels), query)

    // A list of saved threads [ids], newest first; returns whether every one has a row.
    private suspend fun loadSavedIds(key: String, ids: List<String>, query: String): Boolean {
        val missing = ids.filterNot { it in state.value.summaries }
        val found = try {
            cache.summaries(missing)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "cache read failed", error)
            emptyList()
        }
        if (found.isNotEmpty()) state.update { model -> model.copy(summaries = model.summaries + found.associateBy { it.id }) }
        val unread = ids.filterNot { it in state.value.summaries }
        val complete = unread.isEmpty() || fetchSummaries(unread.map { GmailThreadStub(it) }) == null
        val summaries = state.value.summaries
        val listed = ids.filter { it in summaries }.sortedByDescending { summaries.getValue(it).lastDate }
        setList(key) { LoadedList(listed, null, loading = false, loaded = true, query = query) }
        runSuspendCatching { cache.putList(key, listed, null) }.onFailure { Log.w(TAG, "cache write failed", it) }
        return complete
    }

    // The inbox's own first page while focus mode lists only its pinned threads, when neither the
    // cache nor an earlier load has it, so the inbox's count stays right.
    private suspend fun loadInboxBehindFocus() {
        val key = View.INBOX.key
        val list = state.value.lists[key]
        if (list?.loaded == true || list?.loading == true) return
        if (savedMail.filled) {
            loadSaved(key, listOf(Labels.INBOX), "")
            if (reloadAfter.remove(key)) loadView(View.INBOX)
            return
        }
        setList(key) { it.copy(loading = true, error = null) }
        try {
            val query = inboxQuery()
            val page = gmail.listThreads(
                ListOptions(labelIds = listOf(Labels.INBOX), query = query.ifEmpty { null }, maxResults = PAGE_SIZE),
            )
            val stubs = page.threads.orEmpty()
            val error = fetchSummaries(stubs)?.let { errorText(it, gmail.gate.pausedUntil.value) }
            val ids = stubs.map { it.id }
            setList(key) { LoadedList(ids, page.nextPageToken, loading = false, loaded = true, error = error, query = query) }
            cache.putList(key, ids, page.nextPageToken)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setList(key) { it.copy(loading = false, loaded = true, error = errorText(error, gmail.gate.pausedUntil.value)) }
        }
        // Focus turned off while this loaded: the inbox asked for its own load meanwhile.
        if (reloadAfter.remove(key)) loadView(View.INBOX)
    }

    // The listed threads with no row because their read failed; not those Gmail no longer has.
    private fun unreadRows(ids: List<String>): List<String> {
        val summaries = state.value.summaries
        return ids.filter { it !in summaries && it !in gone }
    }

    // Reads the threads of [key]'s list that have no row yet; returns whether all came back.
    private suspend fun retryRows(key: String): Boolean {
        setList(key) { it.copy(loading = true, error = null) }
        val failure = try {
            fetchSummaries(unreadRows(state.value.lists[key]?.ids.orEmpty()).map { GmailThreadStub(it) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }
        setList(key) { it.copy(loading = false, error = failure?.let { error -> errorText(error, gmail.gate.pausedUntil.value) }) }
        return failure == null
    }

    // Snoozed threads' wake-ups and summaries, once per first page; calls meanwhile share them.
    private fun runSnoozeChecks() {
        scope.launch(BACKGROUND) { checkWakeups() }
        scope.launch(BACKGROUND) { fetchSnoozedSummaries() }
    }

    // The inbox's pinned threads: its own list leaves them out with their bundles' labels.
    private suspend fun loadPinned(): Boolean {
        pinnedFresh = true
        if (savedMail.filled) return loadSaved(PINNED_KEY, listOf(Labels.INBOX, PINNED_LABEL), "")
        return try {
            val page = gmail.listThreads(ListOptions(labelIds = listOf(Labels.INBOX, PINNED_LABEL), maxResults = PINNED_PAGE))
            val stubs = page.threads.orEmpty()
            val read = fetchSummaries(stubs) == null
            val ids = stubs.map { it.id }
            setList(PINNED_KEY) { LoadedList(ids, page.nextPageToken, loading = false, loaded = true) }
            cache.putList(PINNED_KEY, ids, page.nextPageToken)
            read
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pinnedFresh = false
            false
        }
    }

    // Bundle labels on loaded inbox threads whose own list wasn't asked for yet.
    private fun bundlesDue(model: MailModel, requested: Set<String>): List<String> {
        val inboxLabels = model.lists[View.INBOX.key]?.ids.orEmpty().map { model.summaries[it]?.labelIds.orEmpty() }
        return bundlesToFetch(model.bundleLabelIds, inboxLabels, requested, bundlesRemembered)
    }

    // Saves which fetched bundles hold inbox threads, when that changed.
    private fun rememberBundles() {
        val model = state.value
        val remembered = bundlesToRemember(
            model.bundleLabelIds.filter { it in bundlesFetched }.map { labelId ->
                val list = model.lists[bundleKey(labelId)]
                BundleList(labelId, list?.ids.orEmpty(), list?.nextPageToken != null)
            },
        ).toSet()
        if (remembered == bundlesRemembered) return
        bundlesRemembered = remembered
        scope.launch { runSuspendCatching { cache.putBundles(remembered) }.onFailure { Log.w(TAG, "cache write failed", it) } }
    }

    // Asks for the due bundles' own lists (the saved copy, then Gmail's first page); returns once
    // each list asked for so far came back or failed.
    private suspend fun fetchDueBundles() {
        for (labelId in bundlesDue(state.value, bundleRequests.keys.toSet())) {
            bundleRequests[labelId] = scope.async(callerPriority()) {
                showCached(bundleKey(labelId))
                // A saved copy paged past its first page keeps those pages when the first is fetched,
                // as one paged this session does, so an open bundle doesn't shrink under the user.
                if (state.value.lists[bundleKey(labelId)]?.ids.orEmpty().size > BUNDLE_FIRST_PAGE) bundlesPaged += labelId
                loadBundle(labelId)
            }
        }
        bundleRequests.values.toList().awaitAll()
    }

    // The query leaving the bundles whose own list came back out of the inbox's own list.
    private fun inboxQuery(): String {
        val model = state.value
        val known = model.listLabels.map { QueryLabel(it.id, it.name) }
        return inboxExclusion(known.filter { it.id in bundlesFetched && it.id in model.bundleLabelIds }, known)
    }

    /**
     * A bundle label's own inbox list: its first few (a paged one keeps its later pages), or with
     * [more] its next page; one of the same kind already running is shared, another is waited for first.
     * Returns whether it all came back.
     */
    private suspend fun loadBundle(labelId: String, more: Boolean = false): Boolean {
        val running = bundleLoads[labelId]
        if (running != null) {
            if (more == running.first) return running.second.await()
            running.second.join()
            return loadBundle(labelId, more)
        }
        val key = bundleKey(labelId)
        val current = state.value.lists[key] ?: LoadedList()
        if (more && current.nextPageToken == null) return true
        val load = scope.async(callerPriority()) { fetchBundlePage(labelId, more) }
        bundleLoads[labelId] = more to load
        load.invokeOnCompletion { if (bundleLoads[labelId]?.second === load) bundleLoads -= labelId }
        return load.await()
    }

    private suspend fun fetchBundlePage(labelId: String, more: Boolean): Boolean {
        val key = bundleKey(labelId)
        if (savedMail.filled) {
            // The saved copy holds every thread of the bundle, so there is no next page.
            if (more) return true
            val complete = loadSaved(key, listOf(Labels.INBOX, labelId), "")
            bundlesPaged -= labelId
            if (labelId in bundleRequests) bundlesFetched += labelId
            if (complete) staleBundles -= labelId else staleBundles += labelId
            rememberBundles()
            return complete
        }
        val current = state.value.lists[key] ?: LoadedList()
        setList(key) { it.copy(loading = true, error = null, loadingMore = it.loadingMore || more, moreError = it.moreError.takeIf { !more }) }
        return try {
            val page = gmail.listThreads(
                ListOptions(
                    labelIds = listOf(Labels.INBOX, labelId),
                    maxResults = if (more) BUNDLE_PAGE_SIZE else BUNDLE_FIRST_PAGE,
                    pageToken = if (more) current.nextPageToken else null,
                ),
            )
            val stubs = page.threads.orEmpty()
            val failure = fetchSummaries(stubs)
            val complete = failure == null
            val pageIds = stubs.map { it.id }
            val loaded = state.value.lists[key]?.ids.orEmpty()
            // A refresh of a bundle opened past its first page keeps the pages after it.
            val merged = if (!more && labelId in bundlesPaged && page.nextPageToken != null) mergeFirstPage(loaded, pageIds) else null
            val ids = if (more) (loaded + pageIds).distinct() else merged ?: pageIds
            val next = if (merged != null) current.nextPageToken else page.nextPageToken
            if (more) bundlesPaged += labelId else if (merged == null) bundlesPaged -= labelId
            // Bundling turned off meanwhile leaves the label in the inbox's own list.
            if (labelId in bundleRequests) bundlesFetched += labelId
            if (!more && complete) staleBundles -= labelId else if (!more) staleBundles += labelId
            // A next page whose threads couldn't be read counts as failed, so top-up stops and the footer offers Retry.
            setList(key) { LoadedList(ids, next, loading = false, loaded = true, loadingMore = it.loadingMore && !more, moreError = failure?.takeIf { more }?.let { errorText(it, gmail.gate.pausedUntil.value) }) }
            rememberBundles()
            cache.putList(key, ids, next)
            complete
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setList(key) { it.copy(loading = false, loaded = true, loadingMore = it.loadingMore && !more, moreError = if (more) errorText(error, gmail.gate.pausedUntil.value) else it.moreError) }
            if (!more) staleBundles += labelId
            false
        }
    }

    // Fetches again the first page of stale bundles once the inbox shows them; returns whether all
    // came back (true while another view or focus mode shows, as they wait for the inbox).
    private suspend fun loadStaleBundles(): Boolean {
        val model = state.value
        if (model.view != View.INBOX || model.focusOn) return true
        val stale = staleBundles.filter { it in model.bundleLabelIds }
        return stale.map { labelId -> scope.async(callerPriority()) { loadBundle(labelId) } }.awaitAll().all { it }
    }

    /** Note whether the end of bundle [labelId]'s open list is on screen. */
    fun noteBundleEnd(labelId: String, shown: Boolean) {
        state.update { model ->
            if ((labelId in model.bundleEndsShown) == shown) {
                model
            } else {
                model.copy(bundleEndsShown = if (shown) model.bundleEndsShown + labelId else model.bundleEndsShown - labelId)
            }
        }
    }

    /** Fetch the next page of a bundle's inbox threads. */
    fun loadMoreBundle(labelId: String) {
        scope.launch { loadBundle(labelId, more = true) }
    }

    /** Load the current view's next page. */
    fun loadMore() {
        scope.launch { loadView(state.value.view, more = true) }
    }

    /**
     * Reload labels, the current view and the open thread (when it changed) from Gmail. Unless
     * [force], a cheap history read first checks whether anything changed since the last reload
     * that came back whole, and skips the reload when nothing did and that one did. A forced reload
     * fetches every bundle's first page again. A refresh asked for while one runs runs once after it.
     */
    fun refresh(force: Boolean = true) {
        scope.launch { runRefresh(force, CallPriority.BACKGROUND) }
    }

    /**
     * Bring the saved copy of mail up to date and wait for it, for the background job: once filled,
     * the same reload the minute's check runs; before that, its first fill (on an unmetered network
     * only). Returns whether the copy is filled and the sync came back whole.
     */
    suspend fun syncSavedMail(): Boolean {
        savedMail.ready()
        if (savedMail.filled) runRefresh(force = false, CallPriority.BACKGROUND) else savedMail.sync()
        return savedMail.settled()
    }

    /** A forced [refresh] that returns once it has finished, for pull to refresh. */
    suspend fun refreshAndWait() = runRefresh(force = true, CallPriority.VISIBLE)

    // A refresh asked for while one runs makes the running one at least as urgent (pull to
    // refresh during a background refresh), and runs once more after it.
    private suspend fun runRefresh(force: Boolean, priority: CallPriority) {
        val running = refreshing
        if (running != null && running.isActive) {
            refreshQueued = force || refreshQueued == true
            if (refreshPriority?.raise(priority) == true) gmail.gate.poke()
            running.join()
            return
        }
        val urgency = GmailPriority(priority)
        refreshPriority = urgency
        val job = scope.launch(urgency) {
            var next: Boolean? = force
            while (next != null) {
                refreshQueued = null
                refreshOnce(next)
                next = refreshQueued
            }
        }
        refreshing = job
        job.join()
    }

    private suspend fun refreshOnce(force: Boolean) {
        savedMail.ready()
        // Once the saved copy is filled, its own history sync says what changed; until then it fills
        // in the background while lists come from Gmail's pages.
        val saved = savedMail.filled
        val synced = if (saved) savedMail.sync() else null
        if (!saved) scope.launch(BACKGROUND) { savedMail.sync() }
        val records = when {
            saved -> synced?.records
            force -> null
            else -> historySinceSync()
        }
        if (!force && records != null && records.isEmpty() && !loadFailed) return
        val history = if (force) null else records
        // Gmail's history id from before the reload, kept only once the reload came back whole, so
        // a failed one is tried again at the next check.
        val point = scope.async(BACKGROUND) {
            if (saved) return@async null
            try {
                gmail.getProfile().historyId
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
        }
        val fetched = bundlesFetched.toList()
        staleBundles += if (history == null) fetched else touchedBundles(history, fetched)
        // The saved copy already brought its own threads' rows up to date.
        val touched = when {
            saved -> synced?.touched.orEmpty().filter { it in state.value.summaries }
            history == null -> emptyList()
            else -> knownThreadsIn(history)
        }
        val before = touched.map { state.value.summaries[it] }
        // Gmail's drafts are listed again when history can't rule out a draft change, or the last listing failed.
        val draftsChanged = history == null || touchesDrafts(history)
        scope.launch(BACKGROUND) {
            loadLabels()
            if (draftsChanged || outboxDraftIds(sending.outbox.docs.value) != draftRefsFor) loadDraftRefs()
        }
        // A list not shown yet is what the screen waits for, however the refresh started.
        val loaded = if (state.value.currentList.loaded) loadView(state.value.view) else withContext(VISIBLE) { loadView(state.value.view) }
        val bundles = loadStaleBundles()
        // Threads history changed that no page just fetched holds (deep in a paged list) are read alone.
        val missed = touched.filterIndexed { index, id -> state.value.summaries[id] === before[index] }
        val read = fetchSummaries(missed.map { GmailThreadStub(it) }) == null
        val historyId = point.await()
        val complete = loaded && bundles && read && (if (saved) synced != null else historyId != null)
        loadFailed = !complete
        if (complete && !saved) syncedHistoryId = historyId
        val model = state.value
        val id = model.openId ?: return
        // A saved row's sync already brought it up to date (see [reshowSaved]).
        if (savedMail.hasAll(listOf(id) + (model.threadsOf(id) - id))) return
        if (model.rowSummary(id)?.historyId != model.detail?.historyId) withContext(VISIBLE) { loadDetail(id, fromCache = false) }
    }

    // The threads with a summary on the device that history records changed.
    private fun knownThreadsIn(records: List<GmailHistoryRecord>): List<String> {
        val summaries = state.value.summaries
        return records.flatMap { record ->
            record.messagesAdded.orEmpty().map { it.message } + record.messagesDeleted.orEmpty().map { it.message } +
                record.labelsAdded.orEmpty().map { it.message } + record.labelsRemoved.orEmpty().map { it.message }
        }.map { it.threadId }.filter { it in summaries }.distinct()
    }

    // Gmail's history after the last whole reload, one page of every kind; null when unknown or longer.
    private suspend fun historySinceSync(): List<GmailHistoryRecord>? {
        val start = syncedHistoryId ?: return null
        return try {
            val result = gmail.listHistory(start, types = null)
            if (result.nextPageToken != null) null else result.history.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    // Queues one thread's change and shows it on its row at once.
    private fun queueChange(change: ThreadChange, fromNotification: Boolean = false): LabelChange {
        val add = gmailIds(change.add)
        val remove = gmailIds(change.remove)
        state.value.summaries[change.threadId]?.let { putSummaries(listOf(it.withChange(add, remove))) }
        val queue = enqueue(state.value.queue, change.threadId, add, remove, fromNotification)
        state.update { it.copy(queue = queue) }
        return queue.last()
    }

    private suspend fun saveQueued(change: LabelChange) {
        runSuspendCatching { cache.putQueued(change) }.onFailure { Log.w(TAG, "queue write failed", it) }
    }

    // Queues label changes to threads, shows them at once and sends them in one flush; returns the
    // queue's save to the device (null with nothing queued), which a change from a notification waits for.
    private fun changeAll(changes: List<ThreadChange>, fromNotification: Boolean = false): Job? {
        if (changes.isEmpty()) return null
        val added = changes.map { queueChange(it, fromNotification) }
        val saving = scope.launch { for (change in added) saveQueued(change) }
        scope.launch {
            saving.join()
            flush()
        }
        return saving
    }

    /**
     * Send what's queued: label changes in order, then drafts and the sends waiting on them. Each
     * stops at the first change the network or Gmail can't take yet. Returns whether nothing is left.
     */
    suspend fun flush(): Boolean {
        val running = flushRun
        if (running != null && running.isActive) {
            flushAgain = true
            return running.await()
        }
        val run = scope.async { flushAll() }
        flushRun = run
        return run.await()
    }

    // Sends everything queued, again while a flush was asked for meanwhile; see [flush].
    private suspend fun flushAll(): Boolean {
        queueLoaded.await()
        var sent: Boolean
        do {
            flushAgain = false
            var labelsSent: Boolean
            do {
                val accountSent = flushAccount()
                labelsSent = flushLabels()
            } while (accountSent && state.value.accountQueue.isNotEmpty())
            val draftsSent = sending.flush()
            sent = labelsSent && state.value.accountQueue.isEmpty() && draftsSent
        } while (sent && flushAgain)
        return sent
    }

    // Sends queued thread changes in order, those queued meanwhile too, until the queue is empty
    // or a change has to wait; returns whether it emptied.
    private suspend fun flushLabels(): Boolean {
        // A thread's changes wait while one names a label Gmail hasn't made yet.
        val drained = replay({ sendableChanges(state.value.queue) }) { change ->
            val refusal = try {
                gmail.modifyThread(change.threadId, change.add, change.remove)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (retryLater(error)) {
                    onPendingChanges()
                    return@replay ReplayStep.LATER
                }
                error
            }
            val sent = state.value.queue.filter { it.threadId == change.threadId && it.seq <= change.seq }
            state.update { model -> model.copy(queue = model.queue - sent.toSet()) }
            runSuspendCatching { cache.deleteQueued(sent.map(LabelChange::seq)) }
            if (refusal != null) changeRefused(change.threadId, sent.any(LabelChange::fromNotification), refusal)
            ReplayStep.SETTLED
        }
        return drained && state.value.queue.isEmpty()
    }

    // Gmail refused a thread's change for good (the thread is gone, say): it is dropped, the row
    // comes back and shows Gmail's labels again, and the user is told.
    private fun changeRefused(threadId: String, fromNotification: Boolean, error: Exception) {
        Log.w(TAG, "label change to $threadId refused", error)
        dispatch(ListEvent.Restore(listOf(threadRowKey(state.value.rowKeyOf(threadId)))))
        scope.launch { fetchSummaries(listOf(GmailThreadStub(threadId))) }
        if (fromNotification) {
            onNotificationDoneFailed(threadId)
        } else {
            showNotice("Couldn't change a conversation. Gmail said: ${error.message ?: "error"}")
        }
    }

    // The Gmail threads these row keys act on: each of a joined row's (see [MailModel.threadsOf]).
    private fun gmailThreads(ids: Collection<String>): List<String> = state.value.threadsOf(ids)

    private fun undoItem(id: String): UndoItem? {
        val model = state.value
        val thread = model.summaries[id] ?: return null
        return UndoItem(id, thread.labelIds, model.snoozeDocs[id])
    }

    /**
     * Done: archive in Gmail, and drop any snooze. A thread not read yet counts for undo as having
     * [assumed] labels, or is skipped without them.
     */
    fun done(ids: List<String>, assumed: List<String>? = null, origin: ActOrigin = ActOrigin.ROW) {
        carryOut(archivePlan(undoItems(gmailThreads(ids), assumed), ids), origin)
    }

    // The threads of [ids] as undo holds them: those read, else as having [assumed] labels.
    private fun undoItems(ids: List<String>, assumed: List<String>? = null): List<UndoItem> {
        val snoozes = state.value.snoozeDocs
        return ids.mapNotNull { id -> undoItem(id) ?: assumed?.let { UndoItem(id, it, snoozes[id]) } }
    }

    // Done on [items], rows [keys]: out of Gmail's inbox and their snoozes dropped, as one undo.
    private fun archivePlan(items: List<UndoItem>, keys: List<String>): ActionPlan = ActionPlan(
        keys = keys,
        changes = items.map { ThreadChange(it.id, emptyList(), listOf(Labels.INBOX)) },
        unsnoozed = items.filter { it.snooze != null }.mapTo(mutableSetOf()) { it.id },
        cleared = items.mapTo(mutableSetOf()) { it.id },
        closes = true,
        undo = UndoPlan(UndoKind.DONE, items),
    )

    /**
     * Move to inbox: put threads that are neither in the inbox nor snoozed back in it, through the
     * label queue, with undo.
     */
    fun moveToInbox(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        carryOut(moveToInboxPlan(ids), origin)
    }

    // Move to inbox on rows [ids]; undo says "Moved to Inbox" and puts the labels back, as for Not spam.
    private fun moveToInboxPlan(ids: List<String>): ActionPlan {
        val items = gmailThreads(ids).mapNotNull(::undoItem)
        return ActionPlan(
            keys = ids,
            changes = items.map { ThreadChange(it.id, listOf(Labels.INBOX), emptyList()) },
            closes = true,
            undo = UndoPlan(UndoKind.NOT_SPAM, items),
        )
    }

    /**
     * Done on threads and todos that take it and Move to inbox on threads that take that instead
     * (see [inboxPlan]), as one undo.
     */
    fun doneOrMoveToInbox(done: List<String>, inbox: List<String>, todoIds: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        carryOut(moveToInboxPlan(inbox) + doneWithTodosPlan(done, todoIds), origin)
    }

    /**
     * Done all on a bundle: its todos (a repeating one making its next copy) and threads. When
     * Gmail has more of the label's inbox threads than were fetched, every unpinned one is listed by
     * id (up to [MAX_BULK_THREADS], no thread read) and archived after the [shown] ones; undo,
     * offered at once, puts them all back.
     */
    fun doneBundle(labelId: String, shown: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        val now = System.currentTimeMillis()
        val todoIds = bundleTodoIds(labelId)
        sweepBundle(labelId, shown, origin, todoPlan(todoIds, UndoPlan(UndoKind.DONE), { it.withDone(now, it.id).done }, todos::done), ::archivePlan)
    }

    /**
     * Pin all on a bundle: its todos not pinned yet and every thread Done all would take ([doneBundle]),
     * Gmail's other ones listed after, as one undo offered at once. Pinned, they leave the bundle for the inbox.
     */
    fun pinBundle(labelId: String, shown: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        val now = System.currentTimeMillis()
        val todoIds = bundleTodos(labelId).filter { !it.pinned }.map { it.id }
        val todoPins = todoPlan(todoIds, UndoPlan(UndoKind.PIN, pinned = true), { it.withPinToggled(now) }, todos::togglePins)
        sweepBundle(labelId, shown, origin, todoPins) { items, keys -> pinItemsPlan(items, keys, pinned = true) }
    }

    /**
     * Delete all on a bundle: its todos, and every thread Done all would take ([doneBundle]) to the
     * trash, Gmail's other ones listed after, as one undo offered at once.
     */
    fun deleteBundle(labelId: String, shown: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        val todoIds = bundleTodoIds(labelId)
        val removed = if (todoIds.isEmpty()) {
            ActionPlan()
        } else {
            ActionPlan(keys = todoIds.map(::todoRowKey), todos = listOf(TodoStep(todoIds, { null }, todos::remove)), undo = UndoPlan(UndoKind.DELETE))
        }
        sweepBundle(labelId, shown, origin, removed) { items, keys -> trashItemsPlan(items, keys, trash = true) }
    }

    // The todos bundle [labelId] holds in the current list.
    private fun bundleTodos(labelId: String): List<Todo> =
        state.value.sections().asSequence()
            .flatMap { it.entries }
            .firstNotNullOfOrNull { entry -> (entry as? ListEntry.Group)?.takeIf { it.labelId == labelId } }
            ?.todos.orEmpty()

    private fun bundleTodoIds(labelId: String): List<String> = bundleTodos(labelId).map { it.id }

    // Carries out [todoPlan] on a bundle's todos and [plan] on its [shown] threads as Done all
    // sweeps them, as one undo, then, when Gmail has more of the label's inbox threads than were
    // fetched, [plan] on those it lists (not read, so as having the bundle's labels), their undo
    // joining the first one.
    private fun sweepBundle(
        labelId: String,
        shown: List<String>,
        origin: ActOrigin,
        todoPlan: ActionPlan,
        plan: (items: List<UndoItem>, keys: List<String>) -> ActionPlan,
    ) {
        val loaded = bundleSweepIds(swept(shown), emptyList(), emptyList())
        val hasMore = state.value.bundleHasMore(labelId)
        val items = undoItems(gmailThreads(loaded))
        // The todos go first, so the threads' action names the undo when there are any.
        val carried = carryOut(todoPlan + plan(items, loaded), origin)
        if (!hasMore) return
        val inLabel = listOf(Labels.INBOX, labelId)
        val acted = items.mapTo(mutableSetOf()) { it.id }
        carryOutRest(carried.entry) {
            val (ids, _) = bundleListed(acted.toList(), inLabel)
            plan(undoItems(ids.filterNot { it in acted }, inLabel), emptyList())
        }
    }

    /**
     * Snooze every thread and todo of a bundle until [epoch], as one pick and one undo: those Done
     * all would take. When Gmail has more of the label's inbox threads than were fetched, they are listed by
     * id (up to [MAX_BULK_THREADS], no thread read) and snoozed after the [shown] ones; undo,
     * offered at once, takes them all back. False when [epoch] already passed.
     */
    fun snoozeBundle(
        labelId: String,
        shown: List<String>,
        epoch: Long,
        source: SnoozeSource,
        pickedKey: String?,
        origin: ActOrigin = ActOrigin.ROW,
    ): Boolean {
        val loaded = bundleSweepIds(swept(shown), emptyList(), emptyList())
        val hasMore = state.value.bundleHasMore(labelId)
        if (epoch <= System.currentTimeMillis()) {
            refreshNow()
            return false
        }
        val pick = SnoozePick(epoch, source, pickedKey)
        val acted = gmailThreads(loaded).filter { undoItem(it) != null }
        val now = System.currentTimeMillis()
        val todoSnoozes = todoPlan(bundleTodoIds(labelId), UndoPlan(UndoKind.SNOOZE), { it.withSnoozed(epoch, now) }) { todos.snooze(it, epoch) }
        val carried = carryOut((todoSnoozes + snoozePlan(acted, epoch, loaded)).copy(pick = pick), origin)
        if (!hasMore) return carried.acted
        val inLabel = listOf(Labels.INBOX, labelId)
        carryOutRest(carried.entry) {
            val (ids, watermarks) = bundleListed(acted, inLabel)
            snoozePlan(ids.filterNot { it in acted }, epoch, emptyList(), watermarks, inLabel).copy(pick = pick)
        }
        return true
    }

    // Carries out the rest of an action once [rest] has found what it acts on (Gmail listing a
    // bundle's other threads), its undo joining [entry], the action's own one offered meanwhile.
    // Undoing that first stops it; once another action took the undo bar, the rest goes without one.
    private fun carryOutRest(entry: UndoEntry?, rest: suspend () -> ActionPlan) {
        val job = scope.launch {
            val plan = rest()
            if (entry == null) {
                carryOut(plan, origin = null)
            } else {
                val joins = undoState.value === entry
                carryOut(plan.copy(undo = null, pick = null), origin = null)
                if (joins) undoState.value = entry.copy(items = entry.items + plan.undo?.items.orEmpty())
            }
        }
        if (entry != null) undoRest = entry to job
    }

    // A bundle's loaded threads as Done all sees them.
    private fun swept(ids: List<String>): List<SweptThread> =
        ids.map { SweptThread(it, state.value.summaries[it]?.isPinned == true) }

    // What Done all, or a swipe, on a bundle Gmail has more of acts on ([bundleSweepIds]): its
    // [shown] threads and the unpinned ones Gmail lists with [inLabel], with their history ids.
    private suspend fun bundleListed(shown: List<String>, inLabel: List<String>): Pair<List<String>, Map<String, String?>> =
        try {
            val pinned = idsListed(inLabel + Labels.STARRED)
            val every = stubsListed(inLabel)
            bundleSweepIds(shown.map { SweptThread(it, pinned = false) }, every.map { it.id }, pinned) to
                every.associate { it.id to it.historyId }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            shown to emptyMap()
        }

    // Thread ids a Gmail search finds, up to MAX_BULK_THREADS, without reading one.
    private suspend fun idsMatching(query: String): List<String> = withContext(ACTION) {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val page = gmail.listThreads(ListOptions(query = query, pageToken = pageToken, maxResults = MAX_BULK_THREADS))
            ids += page.threads.orEmpty().map { it.id }
            pageToken = page.nextPageToken
        } while (pageToken != null && ids.size < MAX_BULK_THREADS)
        ids.take(MAX_BULK_THREADS)
    }

    // Thread ids carrying every one of [labelIds], up to MAX_BULK_THREADS, without reading one.
    private suspend fun idsListed(labelIds: List<String>): List<String> = stubsListed(labelIds).map { it.id }

    // Threads carrying every one of [labelIds], as Gmail lists them, up to MAX_BULK_THREADS.
    private suspend fun stubsListed(labelIds: List<String>): List<GmailThreadStub> = withContext(ACTION) {
        val stubs = mutableListOf<GmailThreadStub>()
        var pageToken: String? = null
        do {
            val page = gmail.listThreads(ListOptions(labelIds = labelIds, pageToken = pageToken, maxResults = MAX_BULK_THREADS))
            stubs += page.threads.orEmpty()
            pageToken = page.nextPageToken
        } while (pageToken != null && stubs.size < MAX_BULK_THREADS)
        stubs.take(MAX_BULK_THREADS)
    }

    /**
     * The threads a notification's Done or Snooze covers: its loaded row and, for an encrypted reply
     * Gmail split off, the threads holding what it answers, found with the search the server's Done
     * uses (`answeredSearch`), so they go even when not loaded. Offline, only the loaded row.
     */
    private suspend fun notificationThreads(id: String): List<String> {
        val row = state.value.inboxThreadsOf(id)
        val query = state.value.summaries[id]?.answers?.let(::answeredSearch) ?: return row
        val found = runSuspendCatching {
            withContext(ACTION) { gmail.listMessages(query, maxResults = MAX_ANSWERED_SEARCH_RESULTS) }.messages.orEmpty().map { it.threadId }
        }.onFailure { Log.w(TAG, "answered search failed", it) }.getOrDefault(emptyList())
        return (row + found).distinct()
    }

    /**
     * Done tapped on a thread's notification: archive it on the device at once and queue it for
     * Gmail, to be sent even if the app closes. No undo.
     */
    suspend fun doneFromNotification(id: String) {
        queueLoaded.await()
        if (id !in state.value.summaries) {
            val cached = runSuspendCatching { cache.summaries(listOf(id)) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) state.update { model -> model.copy(summaries = model.summaries + cached.associateBy { it.id }) }
        }
        val threads = notificationThreads(id)
        val plan = ActionPlan(
            keys = listOf(threads.first()),
            changes = threads.map { ThreadChange(it, emptyList(), listOf(Labels.INBOX)) },
            unsnoozed = threads.toSet(),
            closes = true,
            fromNotification = true,
        )
        carryOut(plan, ActOrigin.NOTIFICATION).saving?.join()
        runSuspendCatching { cache.putSummaries(threads.mapNotNull { state.value.summaries[it] }) }
        // The receiver's process may end before the flush does; the worker sends it then.
        onPendingChanges()
    }

    /** Pin or unpin (Gmail's star). */
    fun togglePin(id: String, origin: ActOrigin = ActOrigin.ROW) {
        val pinned = state.value.rowSummary(id)?.isPinned == true
        carryOut(pinPlan(listOf(id), !pinned), origin)
    }

    /**
     * Snooze until [epoch]: save it in Firestore. The thread stays in Gmail's inbox, and one snoozed
     * from outside it is put there, so it shows again when the snooze ends. False when [epoch] already passed.
     */
    fun snooze(id: String, epoch: Long, source: SnoozeSource, pickedKey: String?, origin: ActOrigin = ActOrigin.ROW): Boolean =
        snoozeAll(listOf(id), epoch, source, pickedKey, origin)

    /** Snooze several threads until [epoch], as one pick and one undo. False when [epoch] already passed. */
    fun snoozeAll(ids: List<String>, epoch: Long, source: SnoozeSource, pickedKey: String?, origin: ActOrigin = ActOrigin.ROW): Boolean {
        val acted = gmailThreads(ids).filter { undoItem(it) != null }
        if (acted.isEmpty()) return false
        if (epoch <= System.currentTimeMillis()) {
            refreshNow()
            return false
        }
        return carryOut(snoozePlan(acted, epoch, ids).copy(pick = SnoozePick(epoch, source, pickedKey)), origin).acted
    }

    // Snooze threads [ids] (rows [keys]) until [epoch], as one undo; see [snoozeAll]. A thread not
    // read yet that Gmail [listed] (with its history id) counts for undo as having [assumed]
    // labels; others not read are skipped.
    private fun snoozePlan(
        ids: List<String>,
        epoch: Long,
        keys: List<String>,
        listed: Map<String, String?> = emptyMap(),
        assumed: List<String> = emptyList(),
    ): ActionPlan {
        val model = state.value
        val items = ids.mapNotNull { id -> undoItem(id) ?: UndoItem(id, assumed, model.snoozeDocs[id]).takeIf { id in listed } }.distinctBy { it.id }
        return ActionPlan(
            keys = keys,
            changes = items.filterNot { Labels.INBOX in it.labelIds }.map { ThreadChange(it.id, listOf(Labels.INBOX), emptyList()) },
            snoozes = items.associate { it.id to SnoozeDoc(epoch, model.summaries[it.id]?.historyId ?: listed[it.id]) },
            cleared = items.mapTo(mutableSetOf()) { it.id },
            closes = true,
            undo = UndoPlan(UndoKind.SNOOZE, items),
        )
    }

    /** End a snooze now; the thread, still in the inbox, shows there again. */
    fun wakeNow(id: String) = wakeAll(listOf(id))

    /** End several snoozes now. */
    fun wakeAll(ids: List<String>) {
        carryOut(ActionPlan(keys = ids, unsnoozed = gmailThreads(ids).toSet()), ActOrigin.ROW)
    }

    /** Pin (or unpin) several threads, as one undo. */
    fun setPinned(ids: List<String>, pinned: Boolean, origin: ActOrigin = ActOrigin.ROW) {
        carryOut(pinPlan(ids, pinned), origin)
    }

    // Pin (or unpin) rows [ids], as one undo.
    private fun pinPlan(ids: List<String>, pinned: Boolean): ActionPlan = pinItemsPlan(gmailThreads(ids).mapNotNull(::undoItem), ids, pinned)

    // Pin (or unpin) [items], rows [keys], as one undo.
    private fun pinItemsPlan(items: List<UndoItem>, keys: List<String>, pinned: Boolean): ActionPlan = ActionPlan(
        keys = keys,
        changes = items.map {
            if (pinned) ThreadChange(it.id, listOf(Labels.STARRED), emptyList()) else ThreadChange(it.id, emptyList(), listOf(Labels.STARRED))
        },
        undo = UndoPlan(UndoKind.PIN, items, pinned = pinned),
    )

    /** Mark threads read, or unread (queued while offline); a thread open is closed when marked unread, as opening reads it. */
    fun setUnread(ids: List<String>, unread: Boolean, origin: ActOrigin = ActOrigin.ROW) {
        val threads = gmailThreads(ids)
        val changes = threads.map {
            if (unread) ThreadChange(it, listOf(Labels.UNREAD), emptyList()) else ThreadChange(it, emptyList(), listOf(Labels.UNREAD))
        }
        val plan = ActionPlan(
            keys = ids,
            changes = changes,
            cleared = if (unread) emptySet() else threads.toSet(),
            closes = unread,
            undo = changeUndo(if (unread) UndoKind.UNREAD else UndoKind.READ, changes),
        )
        carryOut(plan, origin)
    }

    // Undo of label changes: the threads as they were, and the labels the changes touch.
    private fun changeUndo(kind: UndoKind, changes: List<ThreadChange>): UndoPlan =
        UndoPlan(kind, changes.mapNotNull { undoItem(it.threadId) }, touched = changes.flatMap { it.add + it.remove })

    /** Take back the last action the undo bar offers. */
    fun undoLast() {
        val entry = undoState.value ?: return
        // What the action still had to do goes undone.
        undoRest?.let { (restOf, job) -> if (restOf === entry) job.cancel() }
        undoRest = null
        dispatch(ListEvent.Undo)
        entry.todos?.let(todos::restore)
        // A label made since the action is on its threads under Gmail's id by now.
        val restore = gmailIds(entry.labels ?: undoLabels(entry.kind, emptyList()))
        val changes = entry.items.mapNotNull { item ->
            val (add, remove) = undoChange(item.labelIds, state.value.summaries[item.id]?.labelIds.orEmpty(), restore)
            ThreadChange(item.id, add, remove).takeIf { add.isNotEmpty() || remove.isNotEmpty() }
        }
        changeAll(changes)
        if (restoresSnooze(entry.kind)) {
            for (item in entry.items) {
                val snooze = item.snooze
                if (snooze != null) snoozeDocs.write(item.id, snooze) else snoozeDocs.delete(item.id)
            }
        }
        entry.statsUndo?.let(snoozeStats::undo)
        undoState.value = null
    }

    /** "Done all" on the section with [key]: its unpinned threads and todos, as one undo. */
    fun doneSection(key: String) {
        val section = state.value.shownSections.sections.firstOrNull { it.key == key } ?: return
        doneWithTodos(doneAllIds(section), doneAllTodoIds(section))
    }

    /** Done on threads and todos together (the selection, a section's Done all), as one undo. */
    fun doneWithTodos(selectedThreads: List<String>, todoIds: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        carryOut(doneWithTodosPlan(selectedThreads, todoIds), origin)
    }

    // Done on rows [selectedThreads] and todos [todoIds], as one undo.
    private fun doneWithTodosPlan(selectedThreads: List<String>, todoIds: List<String>): ActionPlan {
        val now = System.currentTimeMillis()
        val threadIds = selectedThreads.distinct()
        return archivePlan(undoItems(gmailThreads(threadIds)), threadIds) +
            todoPlan(todoIds, UndoPlan(UndoKind.DONE), { it.withDone(now, it.id).done }, todos::done)
    }

    /** Snooze selected threads and todos until [epoch], as one pick and one undo. False when nothing was snoozed. */
    fun snoozeWithTodos(
        ids: List<String>,
        todoIds: List<String>,
        epoch: Long,
        source: SnoozeSource,
        pickedKey: String?,
        origin: ActOrigin = ActOrigin.ROW,
    ): Boolean {
        if (todoIds.isEmpty()) return snoozeAll(ids, epoch, source, pickedKey, origin)
        val now = System.currentTimeMillis()
        if (epoch <= now) {
            refreshNow()
            return false
        }
        val rows = ids.distinct()
        val plan = snoozePlan(gmailThreads(rows), epoch, rows) +
            todoPlan(todoIds, UndoPlan(UndoKind.SNOOZE), { it.withSnoozed(epoch, now) }) { todos.snooze(it, epoch) }
        return carryOut(plan.copy(pick = SnoozePick(epoch, source, pickedKey)), origin).acted
    }

    /** Pin (or unpin) selected threads and flip the pins of selected todos, as one undo. */
    fun setPinnedWithTodos(selected: List<String>, todoIds: List<String>, pinned: Boolean, origin: ActOrigin = ActOrigin.ROW) {
        val now = System.currentTimeMillis()
        val plan = pinPlan(selected.distinct(), pinned) +
            todoPlan(todoIds, UndoPlan(UndoKind.PIN, pinned = pinned), { it.withPinToggled(now) }, todos::togglePins)
        carryOut(plan, origin)
    }

    /**
     * A new todo, open to edit where it will live: at the top of the inbox's list (or the Todos
     * view's) and scrolled into view. It is saved once it has text, and gone if left empty.
     */
    fun newTodo() {
        if (!todos.snapshot.value.enabled) return
        val view = state.value.view
        if (view != View.INBOX && view != View.Todos) setView(View.INBOX)
        undoState.value = null
        dispatch(ListEvent.Reveal(todoRowKey(todos.start())))
    }

    /**
     * Enter in a todo with text: a new todo just above it, the newer, open to edit, as [newTodo].
     * False when it can't have one there (done, snoozed, on an email, or in a list that wouldn't
     * show it), so Enter only ends the editing.
     */
    fun newTodoAbove(id: String): Boolean {
        if (!startsTodoAbove(id)) return false
        // Its edit ends first, so the new one goes above where the edit leaves it.
        stopEditing(id)
        val todo = todos.snapshot.value.get(id) ?: return false
        val newId = UUID.randomUUID().toString()
        dispatch(ListEvent.Reveal(todoRowKey(todos.start(Position.positionAbove(todo.activeKey(), newId), todo.pinned, id = newId))))
        return true
    }

    /**
     * Whether Enter in the todo [id], once it has text, starts the next one above it ([newTodoAbove]):
     * not on one done, snoozed or on an email, and only where the next one shows, the inbox and the
     * Todos view.
     */
    fun startsTodoAbove(id: String): Boolean {
        val todo = todos.snapshot.value.get(id) ?: return false
        val view = state.value.view
        return todo.matches(TodoFilter.ACTIVE, System.currentTimeMillis()) && (view == View.INBOX || view == View.Todos)
    }

    /** The todo of an email (a row's Gmail threads, lead first), or null when it has none. */
    fun emailTodoOf(threadIds: List<String>): EmailTodo? = rowEmailTodo(todos.snapshot.value.byThread, threadIds)

    /** Open an email's todo to edit; an email without one gets a new one, saved once it has text. */
    fun editThreadTodo(threadId: String) {
        if (todos.snapshot.value.enabled) todos.startEmail(state.value.threadsOf(threadId))
    }

    /** Mark a todo done, or make a done one active again. */
    fun toggleTodoDone(id: String) {
        val todo = todos.snapshot.value.get(id) ?: return
        val now = System.currentTimeMillis()
        val plan = if (todo.state == TodoState.DONE) {
            todoPlan(listOf(id), undo = null, { it.withReactivated(now) }, todos::reactivate)
        } else {
            todoPlan(listOf(id), UndoPlan(UndoKind.DONE), { it.withDone(now, it.id).done }, todos::done)
        }
        carryOut(plan, ActOrigin.ROW)
    }

    /** End a todo's snooze now; a repeating one stops repeating. */
    fun wakeTodo(id: String) {
        val now = System.currentTimeMillis()
        val todo = todos.snapshot.value.get(id) ?: return
        if (todo.state != TodoState.DONE && !todo.isSnoozed(now)) return
        carryOut(todoPlan(listOf(id), undo = null, { it.withReactivated(now) }, todos::reactivate), ActOrigin.ROW)
    }

    /** Snooze a todo until [epoch], as one pick of the snooze menu. False when [epoch] already passed. */
    fun snoozeTodo(id: String, epoch: Long, source: SnoozeSource, pickedKey: String?): Boolean {
        val now = System.currentTimeMillis()
        if (epoch <= now) {
            refreshNow()
            return false
        }
        val plan = todoPlan(listOf(id), UndoPlan(UndoKind.SNOOZE), { it.withSnoozed(epoch, now) }) { todos.snooze(it, epoch) }
        return carryOut(plan.copy(pick = SnoozePick(epoch, source, pickedKey)), ActOrigin.ROW).acted
    }

    /** Give a todo a repeat rule, from the snooze menu. */
    fun setTodoRepeat(id: String, draft: RepeatDraft) = todos.setRepeat(id, draft)

    /** Pin or unpin a todo. */
    fun toggleTodoPin(id: String) {
        val todo = todos.snapshot.value.get(id) ?: return
        val now = System.currentTimeMillis()
        val plan = todoPlan(listOf(id), UndoPlan(UndoKind.PIN, pinned = !todo.pinned), { it.withPinToggled(now) }, todos::togglePins)
        carryOut(plan, ActOrigin.ROW)
    }

    /** Delete todos of either kind; an email's leaves its email as it is. */
    fun deleteTodos(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        if (ids.isEmpty()) return
        carryOut(deleteTodosPlan(ids), origin)
    }

    /**
     * The selection's Delete: threads [threadIds] to the trash (or, when [untrash], out of it to the
     * inbox) and todos [todoIds] deleted, as one undo.
     */
    fun deleteWithTodos(threadIds: List<String>, untrash: Boolean, todoIds: List<String>, origin: ActOrigin = ActOrigin.ROW) {
        val threads = trashItemsPlan(gmailThreads(threadIds).mapNotNull(::undoItem), threadIds, trash = !untrash)
        // The todos go first, so the threads' action names the undo when there are any.
        carryOut(if (todoIds.isEmpty()) threads else deleteTodosPlan(todoIds) + threads, origin)
    }

    // Delete todos [ids] of either kind, as one undo.
    private fun deleteTodosPlan(ids: List<String>): ActionPlan {
        val model = state.value
        val onEmails = todos.snapshot.value.emailTodos.mapTo(hashSetOf()) { it.id }
        // An email's todo is listed as its email's row (the Todos view's, a tagged label's).
        val keys = ids.map { id -> if (id in onEmails) threadRowKey(model.rowKeyOf(id)) else todoRowKey(id) }
        val step = TodoStep(ids, { null }, todos::remove, deletesEmail = true)
        return ActionPlan(keys = keys, todos = listOf(step), undo = UndoPlan(UndoKind.DELETE))
    }

    // A change to todos [ids]: [show] is each as the list shows it after (null: gone), [carry] the
    // store's change, which returns what undo puts back. Nothing for no todos.
    private fun todoPlan(ids: List<String>, undo: UndoPlan?, show: (Todo) -> Todo?, carry: (List<String>) -> TodoUndo?): ActionPlan =
        if (ids.isEmpty()) ActionPlan() else ActionPlan(keys = ids.map(::todoRowKey), todos = listOf(TodoStep(ids, show, carry)), undo = undo)

    // An action that moves or changes the todo being edited closes its editor.
    private fun stopEditing(id: String) {
        if (todos.snapshot.value.editingId == id) todos.setEditing(null)
    }

    /** Show another page of the current list's todos (a search, the Todos view); a new list starts at one. */
    fun showMoreTodos() {
        state.update { model ->
            val list = model.todoListKey
            val pages = if (model.todoPages.list == list) model.todoPages.pages else 1
            model.copy(todoPages = TodoPageCount(list, pages + 1))
        }
    }

    private var openingTodo: Job? = null

    /**
     * Show a todo from its reminder: the Todos view, scrolled to it and
     * highlighted, once Firestore has answered (on a cold start the todos aren't loaded yet).
     */
    fun openTodo(id: String) {
        setView(View.Todos)
        openingTodo?.cancel()
        openingTodo = scope.launch {
            val snapshot = withTimeoutOrNull(TODO_OPEN_WAIT_MS) { todos.snapshot.first { it.loaded } } ?: todos.snapshot.value
            if (snapshot.get(id) != null) todos.highlight(id)
        }
    }

    /** Mark a todo done from its reminder's Done; false when it can't be read. */
    suspend fun todoDoneFromNotification(id: String): Boolean = todos.doneFromReminder(id)

    /** A todo's editor closed: its edit ends, as [TodoStore.setEditing] ends every edit. */
    fun finishTodoEdit(id: String) = stopEditing(id)

    /** Open a thread from outside the list (a notification, a todo's link, a draft); its notification goes. */
    fun open(id: String) {
        val key = state.value.rowKeyOf(id)
        if (state.value.openId == key) {
            clearNotifications(gmailThreads(listOf(key)))
            scope.launch { loadDetail(key, fromCache = false) }
        }
        dispatch(ListEvent.OpenThread(key))
    }

    /** Open a thread from a notification: the thread, then a reply to its last message starting with [text]. */
    fun replyFromNotification(id: String, text: String = "") {
        pendingReply = state.value.rowKeyOf(id)
        pendingReplyText = text
        open(id)
    }

    private fun startPendingReply(detail: ThreadDetail) {
        if (pendingReply != detail.id) return
        val last = detail.messages.lastOrNull() ?: return
        pendingReply = null
        sending.composeTo(ComposeKind.REPLY, detail.threadOf(last), last, pendingReplyText)
        pendingReplyText = ""
    }

    /**
     * Reply with [text] to the last message of [id] from a notification, without opening the app:
     * read the thread from Gmail (or, offline, from the device when it already holds [messageId]),
     * then save the send to go with the undo delay. Returns its outbox and local ids; null when the
     * thread can't be read, is encrypted, or the reply can't go as is.
     */
    suspend fun replyInlineFromNotification(id: String, messageId: String, text: String): Pair<String, String>? {
        // A thread Gmail split off a conversation is answered with the rest of its row: at its newest message.
        val threads = listOf(id) + (state.value.inboxThreadsOf(id) - id)
        if (threads.any { summary(it)?.encrypted == true }) return null
        val details = threads.map { threadForReply(it, if (it == id) messageId else null) ?: return null }
        val detail = mergeDetails(details)
        if (detail.messages.any { it.lock != null }) return null
        val last = detail.messages.lastOrNull() ?: return null
        return sending.replyFromNotification(detail.threadOf(last), last, text)
    }

    // [id] read for a reply, from Gmail, or offline from the device (holding [messageId] when given).
    private suspend fun threadForReply(id: String, messageId: String?): ThreadDetail? =
        try {
            withContext(Dispatchers.Default + ACTION) { threadDetail(gmail.getThread(id, full = true)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runSuspendCatching { cache.thread(id) }.getOrNull()?.takeIf { cached -> messageId == null || cached.messages.any { it.id == messageId } }
        }

    /**
     * Snooze tapped on a thread's notification: snooze it until [epoch] (or, once that passed, the
     * first suggestion now) on the device at once, putting it in the inbox when it isn't, with its
     * changes queued to go even if the app closes. No undo. False when the thread is unknown.
     */
    suspend fun snoozeFromNotification(id: String, epoch: Long): Boolean {
        queueLoaded.await()
        val known = summary(id) ?: run {
            withContext(ACTION) { fetchSummaries(listOf(GmailThreadStub(id))) }
            state.value.summaries[id]
        } ?: return false
        if (id !in state.value.summaries) state.update { model -> model.copy(summaries = model.summaries + (id to known)) }
        val now = System.currentTimeMillis()
        val until = epoch.takeIf { it > now }
            ?: notificationSnoozeTime(snoozeStats.stats.value, Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val threadIds = notificationThreads(id)
        val missing = threadIds.filter { it !in state.value.summaries }
        if (missing.isNotEmpty()) withContext(ACTION) { fetchSummaries(missing.map(::GmailThreadStub)) }
        val threads = threadIds.mapNotNull { state.value.summaries[it] }
        val plan = ActionPlan(
            keys = listOf(threads.first().id),
            changes = threads.filterNot { it.isInInbox }.map { ThreadChange(it.id, listOf(Labels.INBOX), emptyList()) },
            snoozes = threads.associate { it.id to SnoozeDoc(until, it.historyId) },
            cleared = threads.mapTo(mutableSetOf()) { it.id },
            closes = true,
            fromNotification = true,
        )
        val carried = carryOut(plan, ActOrigin.NOTIFICATION)
        carried.saving?.let { saving ->
            saving.join()
            onPendingChanges()
        }
        // Offline, the writes are kept on the device by then; online, Firestore has them.
        withTimeoutOrNull(SNOOZE_WRITE_WAIT_MS) { runSuspendCatching { carried.written.forEach { it.await() } } }
        SnoozeAlarm.checkSoon(context)
        return true
    }

    // Notifications about these threads go: they were read, done, snoozed or opened here.
    private fun clearNotifications(ids: Collection<String>) {
        if (ids.isNotEmpty()) Notifier.cancelThreads(context, ids)
    }

    /** Close the open thread. */
    fun close() = dispatch(ListEvent.CloseThread)

    // Loads the open row [id]: its thread, or each of a joined row's, shown as one (see [mergeDetails]).
    // A row whose threads are all saved shows from the device and asks Gmail nothing: the saved
    // copy's history sync keeps it current (see [SavedMailHost]).
    private suspend fun loadDetail(id: String, fromCache: Boolean) {
        val threadIds = listOf(id) + (state.value.threadsOf(id) - id)
        if (fromCache && savedMail.hasAll(threadIds)) {
            val saved = savedDetail(threadIds)
            if (saved != null) {
                if (state.value.openId == id && state.value.detail == null) {
                    state.update { it.copy(detail = rowReadOver(id, saved), detailError = null) }
                    startPendingReply(saved)
                    // Not waited for: their writes may queue behind the saved copy's.
                    scope.launch {
                        runSuspendCatching { cache.touchThreads(threadIds) }
                        for (threadId in threadIds) savedMail.noteOpened(threadId)
                    }
                    val unread = threadIds.mapNotNull { state.value.summaries[it] }.filter { Labels.UNREAD in it.labelIds }
                    changeAll(unread.map { ThreadChange(it.id, emptyList(), listOf(Labels.UNREAD)) })
                }
                return
            }
        }
        try {
            // A joined row shows the messages of each of its Gmail threads but those gone from Gmail;
            // only the lead's failure fails the load.
            val results = coroutineScope {
                threadIds.map { member -> async { runSuspendCatching { gmail.getThread(member, full = true) } } }.awaitAll()
            }
            val lead = results.first().getOrThrow()
            val threads = listOf(lead) + threadIds.zip(results).drop(1).mapNotNull { (member, result) ->
                val error = result.exceptionOrNull()
                when {
                    error == null -> result.getOrThrow()
                    isNotFound(error) -> {
                        removeGone(listOf(member))
                        null
                    }
                    else -> {
                        Log.w(TAG, "joined thread load failed: $member", error)
                        null
                    }
                }
            }
            val plains = withContext(Dispatchers.Default) { threads.map(::threadDetail) }
            val summaries = withContext(Dispatchers.Default) { threads.map(::summarizeThread) }
            val reveals = threads.map { encryption.reveal(it) }
            val detail = mergeDetails(plains.zip(reveals) { plain, reveal -> revealed(plain, reveal.mapValues { it.value.shown }) })
            if (state.value.openId == id) state.update { it.copy(detail = detail, detailError = null) }
            startPendingReply(detail)
            dropSnoozesArchivedElsewhere(summaries, emptySet())
            putSummaries(summaries.map(::withQueued))
            // Only while it is still open: Mark unread may have closed it during the fetch.
            if (state.value.openId == id) {
                val unread = plains.filter { plain -> plain.messages.any { it.unread } }
                changeAll(unread.map { ThreadChange(it.id, emptyList(), listOf(Labels.UNREAD)) })
            }
            // The plain cache never holds decrypted text; the encrypted cache has it. Saved before
            // it counts as saved, so opening it again reads nothing.
            for ((plain, reveal) in plains.zip(reveals)) runSuspendCatching { cache.putThread(revealed(plain, reveal.mapValues { it.value.cacheable })) }
            forgetBodies(plains.map { it.id })
            savedMail.noteRead(threads, opened = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (state.value.openId == id && state.value.detail == null) {
                val text = if (isOffline(error)) {
                    "This thread isn't saved on this device yet. It opens once you're back online."
                } else {
                    errorText(error, gmail.gate.pausedUntil.value)
                }
                state.update { it.copy(detailError = text) }
                if (isRateLimited(error)) retryDetailAfterPause(id)
            }
        }
    }

    /** Load the open thread again after it failed to load. */
    fun retryDetail() {
        val id = state.value.openId ?: return
        detailRetry?.cancel()
        state.update { it.copy(detailError = null) }
        scope.launch { withContext(VISIBLE) { loadDetail(id, fromCache = false) } }
    }

    // Loads the open thread again once Gmail's pause ends, as its error says; opening another
    // thread or closing this one leaves it undone.
    private fun retryDetailAfterPause(id: String) {
        detailRetry?.cancel()
        detailRetry = scope.launch {
            delay(maxOf(gmail.gate.pausedUntil.value - System.currentTimeMillis(), FIRST_BACKOFF_MS))
            if (state.value.openId == id && state.value.detail == null) loadDetail(id, fromCache = false)
        }
    }

    /**
     * Row [id]'s saved copy, read from the device alone for drawing it beside the open thread: no
     * fetch, and it is neither marked read nor counted as opened. Null when it isn't all saved.
     */
    suspend fun savedPreview(id: String): ThreadDetail? {
        val threadIds = listOf(id) + (state.value.threadsOf(id) - id)
        return if (savedMail.hasAll(threadIds)) savedDetail(threadIds) else null
    }

    // The saved bodies of [threadIds] (lead first) as one thread: from memory when held, else from
    // the device, without a write first; null when one has none.
    private suspend fun savedDetail(threadIds: List<String>): ThreadDetail? {
        val parts = threadIds.map { threadId ->
            savedBodies[threadId] ?: run {
                val stored = runSuspendCatching { cache.storedThread(threadId) }.getOrNull() ?: return null
                revealed(stored, encryption.restore(stored.messages + stored.drafts).associateBy { it.id }).also { body ->
                    savedBodies[threadId] = body
                }
            }
        }
        return mergeDetails(parts)
    }

    // Saved bodies whose stored copies changed are read again.
    private fun forgetBodies(ids: Collection<String>) {
        savedBodies.keys.removeAll(ids.toSet())
    }

    // A saved body with its row's read state laid over it: the row has the changes queued here,
    // which history brings to the saved body only once Gmail has them.
    private fun rowReadOver(id: String, saved: ThreadDetail): ThreadDetail {
        val row = state.value.rowSummary(id) ?: return saved
        if (Labels.UNREAD in row.labelIds || saved.messages.none { it.unread }) return saved
        return saved.copy(messages = saved.messages.map { if (it.unread) it.copy(unread = false) else it })
    }

    // The open row again from the saved copy once its sync changed a saved body; new mail in it is
    // marked read, as the open thread's is.
    private suspend fun reshowSaved(changed: Collection<String>, newMail: Boolean) {
        val model = state.value
        val id = model.openId ?: return
        val threadIds = listOf(id) + (model.threadsOf(id) - id)
        if (changed.none { it in threadIds } || model.detail?.id != id || !savedMail.hasAll(threadIds)) return
        val saved = savedDetail(threadIds) ?: return
        if (state.value.openId != id) return
        state.update { it.copy(detail = rowReadOver(id, saved)) }
        if (newMail && saved.messages.any { it.unread }) {
            val unread = threadIds.mapNotNull { state.value.summaries[it] }.filter { Labels.UNREAD in it.labelIds }
            changeAll(unread.map { ThreadChange(it.id, emptyList(), listOf(Labels.UNREAD)) })
        }
    }

    // Gmail's search can't see inside encrypted mail: fetch it a page at a time and match it here.
    private fun searchEncrypted(view: View.Search) {
        val plan = searchPlan(searchTagTerms(view.query, state.value.labelTags))
        val query = encryptedSearchQuery(plan) ?: return
        if (encryption.status.value != EncryptionStatus.UNLOCKED) return
        encryptedSearch?.cancel()
        setList(view.key) { it.copy(searchingEncrypted = true) }
        encryptedSearch = scope.launch {
            try {
                encryption.searchEncrypted(plan, query) { threadIds ->
                    fetchSummaries(threadIds.filterNot { it in state.value.summaries }.map { GmailThreadStub(it) })
                    setList(view.key) { list ->
                        val summaries = state.value.summaries
                        list.copy(ids = (list.ids + threadIds).distinct().sortedByDescending { summaries[it]?.lastDate ?: 0L })
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "encrypted search failed", error)
            } finally {
                setList(view.key) { it.copy(searchingEncrypted = false) }
            }
        }
    }

    private fun revealed(detail: ThreadDetail, views: Map<String, MessageView>): ThreadDetail {
        if (views.isEmpty()) return detail
        val messages = detail.messages.map { views[it.id] ?: it }
        val first = messages.firstOrNull()
        val subject = if (first?.lock != null && first.subject.isNotBlank()) first.subject else detail.subject
        return detail.copy(subject = subject, messages = messages, drafts = detail.drafts.map { views[it.id] ?: it })
    }

    // Gmail can't be reached: search what this device has.
    private suspend fun searchCache(view: View.Search) {
        val plan = searchPlan(searchTagTerms(view.query, state.value.labelTags))
        val ids = try {
            val terms = plan.terms
            val bodyHits = (terms.include + terms.exclude).associateWith { cache.threadsContaining(it) }
            val labelNames = state.value.labels.associate { it.id to it.name }
            val decrypted = encryption.searchCached(plan)
            val found = cache.allSummaries().filter { summary ->
                summary.id in decrypted ||
                    matchesOffline(
                        summary,
                        plan,
                        labelNames,
                        savedHolds = { term -> summary.id in bodyHits[term].orEmpty() },
                        saved = { cache.storedThread(summary.id)?.let(::savedText) },
                    )
            }
            val known = state.value.summaries
            val missing = found.filterNot { it.id in known }
            if (missing.isNotEmpty()) state.update { model -> model.copy(summaries = model.summaries + missing.associateBy { it.id }) }
            found.sortedByDescending { it.lastDate }.map { it.id }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "offline search failed", error)
            emptyList()
        }
        setList(view.key) { LoadedList(ids, null, loading = false, loaded = true, fromCache = true) }
    }

    /** Show [text] in the app's dark pill, from any screen. */
    fun showNotice(text: String) = sending.showNotice(text)

    /** Run a search (the whole query text, chips included). */
    fun search(query: String) {
        setView(View.Search(query))
    }

    /** Mark a thread important or not (Gmail's IMPORTANT label). */
    fun setImportant(id: String, important: Boolean) {
        val changes = gmailThreads(listOf(id)).mapNotNull { threadId ->
            val thread = state.value.summaries[threadId] ?: return@mapNotNull null
            val delta = importantChange(thread.labelIds, important)
            ThreadChange(threadId, delta.add, delta.remove).takeIf { delta.add.isNotEmpty() || delta.remove.isNotEmpty() }
        }
        carryOut(ActionPlan(keys = listOf(id), changes = changes), ActOrigin.ROW)
    }

    /** Add and remove labels on threads (the label sheet); one made while another's undo is on offer joins that undo. */
    fun changeLabels(ids: List<String>, add: List<String>, remove: List<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        val changes = gmailThreads(ids).map { ThreadChange(it, add, remove) }
        carryOut(ActionPlan(keys = ids, changes = changes, undo = changeUndo(UndoKind.LABEL, changes)), ActOrigin.ROW)
    }

    /** Move threads into one of Gmail's categories (the label sheet). */
    fun changeCategory(ids: List<String>, category: String) {
        val moves = gmailThreads(ids).mapNotNull { id ->
            val thread = state.value.summaries[id] ?: return@mapNotNull null
            val (add, remove) = categoryChange(thread.labelIds, category)
            ThreadChange(id, add, remove).takeIf { add.isNotEmpty() || remove.isNotEmpty() }
        }
        val inInbox = moves.map { it.threadId }.filter { state.value.summaries[it]?.isInInbox == true }
        carryOut(ActionPlan(keys = ids, changes = moves, undo = changeUndo(UndoKind.MOVE, moves)), ActOrigin.ROW)
        // A thread leaving a bundle is in no list the inbox reads until the lists are fetched again.
        for (id in inInbox) setList(View.INBOX.key) { list -> list.copy(ids = keepListed(list.ids, id) { state.value.summaries[it]?.lastDate }) }
    }

    /** Mark spam: add SPAM, remove INBOX, and drop any snooze. */
    fun reportSpam(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) = moveToSpam(ids, spam = true, origin)

    /** Not spam: back to the inbox. */
    fun notSpam(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) = moveToSpam(ids, spam = false, origin)

    // Spam drops any snooze, as on web; undo writes it back.
    private fun moveToSpam(ids: List<String>, spam: Boolean, origin: ActOrigin) {
        val items = gmailThreads(ids).mapNotNull(::undoItem)
        val plan = ActionPlan(
            keys = ids,
            changes = items.map {
                if (spam) ThreadChange(it.id, listOf(Labels.SPAM), listOf(Labels.INBOX)) else ThreadChange(it.id, listOf(Labels.INBOX), listOf(Labels.SPAM))
            },
            unsnoozed = if (spam) items.filter { it.snooze != null }.mapTo(mutableSetOf()) { it.id } else emptySet(),
            cleared = if (spam) items.mapTo(mutableSetOf()) { it.id } else emptySet(),
            closes = true,
            undo = UndoPlan(if (spam) UndoKind.SPAM else UndoKind.NOT_SPAM, items),
        )
        carryOut(plan, origin)
    }

    /** Delete: move to the trash (add TRASH, remove INBOX) and drop any snooze; undo takes it back out. */
    fun delete(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) = moveToTrash(ids, trash = true, origin)

    /** Take threads out of the trash, back to the inbox. */
    fun untrash(ids: List<String>, origin: ActOrigin = ActOrigin.ROW) = moveToTrash(ids, trash = false, origin)

    private fun moveToTrash(ids: List<String>, trash: Boolean, origin: ActOrigin) {
        carryOut(trashItemsPlan(gmailThreads(ids).mapNotNull(::undoItem), ids, trash), origin)
    }

    // To the trash (or out of it, back to the inbox) [items], rows [keys], as one undo.
    private fun trashItemsPlan(items: List<UndoItem>, keys: List<String>, trash: Boolean): ActionPlan = ActionPlan(
        keys = keys,
        changes = items.map {
            if (trash) ThreadChange(it.id, listOf(Labels.TRASH), listOf(Labels.INBOX)) else ThreadChange(it.id, listOf(Labels.INBOX), listOf(Labels.TRASH))
        },
        unsnoozed = if (trash) items.filter { it.snooze != null }.mapTo(mutableSetOf()) { it.id } else emptySet(),
        cleared = if (trash) items.mapTo(mutableSetOf()) { it.id } else emptySet(),
        closes = true,
        undo = UndoPlan(if (trash) UndoKind.TRASH else UndoKind.NOT_SPAM, items),
    )

    /**
     * Create a label by its full name (`cc.hafa.inbox.core.label.nestedName`); returns it. It shows at once under a local
     * id, which threads and filters can use right away, and is made in Gmail when online.
     */
    fun createLabel(fullName: String, color: GmailLabelColor?): GmailLabel {
        val full = fullName.trim()
        val labelId = newLocalId()
        queueAccount { seq -> AccountChange.CreateLabel(seq, labelId, full, color) }
        return state.value.labels.firstOrNull { it.id == labelId } ?: GmailLabel(labelId, full)
    }

    /**
     * Rename (or move, by changing the path) a label; nested labels move with it, and so do the
     * todos' tags naming them. Shown at once, sent when online.
     */
    fun renameLabel(id: String, newName: String) {
        val labels = state.value.labels
        val old = labels.firstOrNull { it.id == id }?.name ?: return
        val name = newName.trim()
        val moves = renames(labels, id, name)
        if (moves.isEmpty()) return
        for (move in moves) {
            for (gmailId in gmailIds(listOf(move.id))) queueAccount { seq -> AccountChange.PatchLabel(seq, gmailId, name = move.name) }
        }
        // The todos' tags naming it, and labels nested under it, follow.
        if (labels.firstOrNull { it.id == id }?.type == "user") {
            setPendingRetags(pendingRetags + TagRename(old, name))
            runRetags()
        }
    }

    private fun setPendingRetags(retags: List<TagRename>) {
        pendingRetags = retags
        scope.launch {
            retagSaves.withLock { runSuspendCatching { cache.putRetags(pendingRetags) }.onFailure { Log.w(TAG, "cache write failed", it) } }
        }
    }

    // Carry the todos' tags through queued label renames in order; one that fails (offline, say)
    // stops the run and waits for the next.
    private fun runRetags() {
        if (retagRun?.isActive == true) return
        retagRun = scope.launch {
            while (true) {
                val next = pendingRetags.firstOrNull() ?: break
                try {
                    todos.retag(next.from, next.to)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "todo tag rename waits", error)
                    break
                }
                setPendingRetags(pendingRetags.filter { it !== next })
            }
        }
    }

    /** Set a label's color (one of Gmail's palette). Shown at once, sent when online. */
    fun colorLabel(id: String, color: GmailLabelColor) {
        for (gmailId in gmailIds(listOf(id))) queueAccount { seq -> AccountChange.PatchLabel(seq, gmailId, color = color) }
    }

    /**
     * Save a filter, in place of [replacing] when given (Gmail has no edit, so the old one is
     * deleted once the new one is made). Shown at once, sent when online.
     */
    fun saveFilter(editor: FilterEditor, replacing: String?) {
        val (criteria, asked) = editorToFilter(editor)
        val action = asked.copy(addLabelIds = asked.addLabelIds?.let(::gmailIds), removeLabelIds = asked.removeLabelIds?.let(::gmailIds))
        val gmailReplacing = replacing?.let { gmailIds(listOf(it)).firstOrNull() }
        queueAccount { seq -> AccountChange.CreateFilter(seq, newLocalId(), criteria, action, gmailReplacing) }
    }

    /**
     * Queue label changes to every thread matching a search (a new filter applied to mail already
     * there); the search runs when the queue reaches it.
     */
    fun applyToMatching(query: String, add: List<String>, remove: List<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        queueAccount { seq -> AccountChange.LabelMatching(seq, query, gmailIds(add), gmailIds(remove)) }
    }

    /** Delete a filter: gone at once, deleted in Gmail when online. */
    fun deleteFilter(id: String) {
        if (madeIds.containsKey(id) && madeIds[id] == null) return
        queueAccount { seq -> AccountChange.DeleteFilter(seq, madeIds[id] ?: id) }
    }

    // Label ids with each local one Gmail already made named by Gmail's id, and refused ones left out.
    private fun gmailIds(ids: List<String>): List<String> =
        ids.mapNotNull { id -> if (madeIds.containsKey(id)) madeIds[id] else id }

    private fun queueAccount(build: (Long) -> AccountChange) {
        setAccountQueue(enqueueAccount(state.value.accountQueue, build))
        scope.launch { flush() }
    }

    // Keep the label and filter queue, on screen and on the device, and show labels with it.
    private fun setAccountQueue(queue: List<AccountChange>) {
        state.update { it.copy(accountQueue = queue, labels = labelsWithPending(it.labels, queue)) }
        if (!accountQueueSaves) return
        scope.launch {
            runSuspendCatching { cache.putAccountQueue(queue) }.onFailure { Log.w(TAG, "queue write failed", it) }
        }
    }

    // Send queued label and filter changes in order, including ones queued meanwhile; returns
    // whether the queue emptied rather than a change having to wait.
    private suspend fun flushAccount(): Boolean {
        var labelsChanged = false
        try {
            while (true) {
                val change = nextAccountChange(state.value.accountQueue) ?: return true
                try {
                    sendAccountChange(change)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (retryLater(error)) {
                        onPendingChanges()
                        return false
                    }
                    Log.w(TAG, "account change dropped: $change", error)
                    refused(change, error)
                }
                if (change is AccountChange.CreateLabel || change is AccountChange.PatchLabel) labelsChanged = true
            }
        } finally {
            if (labelsChanged) scope.launch { loadLabels() }
        }
    }

    // Send one change and take it off the queue; throws what Gmail or the network answered.
    private suspend fun sendAccountChange(change: AccountChange) {
        fun sent() = changeSent(state.value.accountQueue, change.seq)
        when (change) {
            is AccountChange.CreateLabel -> {
                val label = try {
                    gmail.createLabel(change.name, change.color)
                } catch (error: GmailException) {
                    // A try that timed out but was made, or the same label made elsewhere: use Gmail's.
                    val existing = if (error.status == HttpURLConnection.HTTP_CONFLICT) labelNamed(gmail.listLabels(), change.name) else null
                    existing ?: throw error
                }
                labelCreated(change, label)
            }
            is AccountChange.PatchLabel -> {
                gmail.updateLabel(change.labelId, name = change.name, color = change.color)
                setAccountQueue(sent())
            }
            is AccountChange.CreateFilter -> {
                val replacing = change.replacing
                val made = try {
                    gmail.createFilter(GmailFilter(criteria = change.criteria, action = change.action))
                } catch (error: GmailException) {
                    // Saving an edit that changed nothing: Gmail refuses the duplicate, and the old one stands.
                    if (replacing == null || !isDuplicateFilter(error)) throw error
                    madeIds[change.filterId] = replacing
                    setAccountQueue(filterMade(state.value.accountQueue, change.seq, replacing))
                    return
                }
                val madeId = made.id ?: change.filterId
                madeIds[change.filterId] = madeId
                filters.made(made)
                setAccountQueue(filterMade(state.value.accountQueue, change.seq, madeId))
            }
            is AccountChange.DeleteFilter -> {
                try {
                    gmail.deleteFilter(change.filterId)
                } catch (error: GmailException) {
                    if (!isNotFound(error)) throw error
                }
                filters.deleted(change.filterId)
                setAccountQueue(sent())
            }
            is AccountChange.LabelMatching -> {
                val ids = idsMatching(change.query)
                setAccountQueue(sent())
                changeAll(ids.map { ThreadChange(it, change.add, change.remove) })
            }
        }
    }

    // Gmail made a label made on the device: name it by Gmail's id everywhere the local one shows.
    private fun labelCreated(change: AccountChange.CreateLabel, label: GmailLabel) {
        val localId = change.labelId
        madeIds[localId] = label.id
        state.update { model ->
            val listed = model.labels.any { it.id == label.id }
            model.copy(labels = model.labels.flatMap { if (it.id != localId) listOf(it) else if (listed) emptyList() else listOf(label) })
        }
        setAccountQueue(labelMade(state.value.accountQueue, change.seq, label.id))
        relabelThreads(localId, label.id)
        state.value.labelPrefDocs[localId]?.let { prefs ->
            val patch = prefs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
            state.update { model ->
                val docs = model.labelPrefDocs
                model.copy(labelPrefDocs = docs - localId + (label.id to docs[label.id].orEmpty() + patch))
            }
            labelPrefDocs.move(localId, label.id, patch)
        }
        (state.value.view as? View.Label)?.takeIf { it.labelId == localId }?.let { setView(View.Label(label.id)) }
    }

    // Rename a label on shown threads and in queued thread changes, or drop it with [to] null.
    private fun relabelThreads(from: String, to: String?) {
        fun swap(ids: List<String>) = if (to == null) ids.filter { it != from } else ids.map { if (it == from) to else it }
        putSummaries(state.value.summaries.values.filter { from in it.labelIds }.map { it.copy(labelIds = swap(it.labelIds)) })
        val before = state.value.queue
        val queue = relabelChanges(before, from, to)
        val changed = queue.filterIndexed { index, change -> change !== before[index] }
        state.update { it.copy(queue = queue) }
        if (changed.isNotEmpty()) {
            scope.launch { for (change in changed) saveQueued(change) }
        }
    }

    // Gmail refused a queued change for good: drop it and what depended on it, and say so.
    private fun refused(change: AccountChange, error: Exception) {
        when (change) {
            is AccountChange.CreateLabel -> madeIds[change.labelId] = null
            is AccountChange.CreateFilter -> madeIds[change.filterId] = null
            else -> Unit
        }
        setAccountQueue(changeRefused(state.value.accountQueue, change.seq))
        if (change is AccountChange.CreateLabel) {
            state.update { model -> model.copy(labels = model.labels.filter { it.id != change.labelId }) }
            relabelThreads(change.labelId, null)
        }
        val what = when (change) {
            is AccountChange.CreateLabel -> "make the label “${change.name}”"
            is AccountChange.PatchLabel -> "change a label"
            is AccountChange.CreateFilter -> "save a filter"
            is AccountChange.DeleteFilter -> "delete a filter"
            is AccountChange.LabelMatching -> "label the mail a filter matches"
        }
        showNotice("Couldn't $what. Gmail said: ${error.message ?: "error"}")
    }

    /**
     * Unsubscribe from [message]'s list by its preferred route: one-click through the
     * `unsubscribe` function, or the request mail through the send path. Link routes are opened
     * by the screen.
     */
    suspend fun unsubscribe(message: MessageView): UnsubscribeResult {
        val route = parseUnsubscribe(message.listUnsubscribe, message.listUnsubscribePost)
        return when (route.preferred) {
            UnsubscribeMethod.ONE_CLICK -> try {
                val data = mapOf("messageId" to message.id, "url" to route.oneClickUrl, "post" to message.listUnsubscribePost)
                val response = functions.getHttpsCallable("unsubscribe").call(data).await()
                val ok = (response.getData() as? Map<*, *>)?.get("ok") == true
                if (ok) UnsubscribeResult.Unsubscribed else UnsubscribeResult.Failed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                when {
                    isReauth(error) || error is ReauthNeeded -> UnsubscribeResult.NeedsSignIn
                    isOffline(error) -> UnsubscribeResult.Offline
                    else -> UnsubscribeResult.Failed
                }
            }
            UnsubscribeMethod.MAILTO -> {
                val mail = route.mailto ?: return UnsubscribeResult.Failed
                try {
                    sending.sendNow(
                        DraftContent(
                            localId = "",
                            from = sending.fromFor(message),
                            to = listOf(Person("", mail.address)),
                            subject = mail.subject.trim().ifEmpty { "Unsubscribe" },
                            markdown = mail.body.trim().ifEmpty { "Please unsubscribe me from this list." },
                        ),
                    )
                    UnsubscribeResult.Requested
                } catch (error: InvalidAddressError) {
                    Log.w(TAG, "unsubscribe address refused", error)
                    UnsubscribeResult.Failed
                }
            }
            UnsubscribeMethod.URL -> route.url?.let { UnsubscribeResult.OpenLink(it) } ?: UnsubscribeResult.Failed
            null -> UnsubscribeResult.Failed
        }
    }

    /**
     * The bytes of a message's part, fetched by [attachmentId] and kept in memory while recently used
     * by the part's place in the message ([partId]), since Gmail gives the same part a new attachment
     * id on every fetch. Callers asking at once share one fetch, which runs on when they all stop.
     */
    suspend fun attachmentBytes(messageId: String, partId: String, attachmentId: String): ByteArray {
        val key = "$messageId/$partId"
        attachments.get(key)?.let { return it }
        // Lazy, so it can't finish (and leave the map) before it is in the map.
        val pending = fetchingAttachments.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val encoded = gmail.getAttachment(messageId, attachmentId)
                    withContext(Dispatchers.Default) { decodeBase64Url(encoded) }.also { attachments.put(key, it) }
                } finally {
                    fetchingAttachments.remove(key)
                }
            }
        }
        return pending.await()
    }

    /**
     * A `data:` URL for an inline part, from its data or by fetching it; null when it can't be had.
     * Throws when the fetch failed for want of a network ([isOffline]), so the caller can try again
     * once online.
     */
    suspend fun inlineDataUrl(messageId: String, part: InlinePart): String? {
        val inline = part.data
        val bytes = if (inline != null) {
            withContext(Dispatchers.Default) { decodeBase64Url(inline) }
        } else {
            part.attachmentId?.let { attachmentId ->
                try {
                    attachmentBytes(messageId, part.partId, attachmentId)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (isOffline(error)) {
                        throw error
                    } else {
                        null
                    }
                }
            }
        } ?: return null
        val encoded = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(bytes) }
        return "data:${part.mimeType};base64,$encoded"
    }

    /** Let the list on screen say where it sits (read just before each new layout); null once it leaves. */
    fun trackAnchor(source: (() -> ListAnchor?)?) {
        anchorSource = source
    }

    /** Apply [event] to the list, then carry out the work it asks for: a bundle's next page, an action, a todo to edit. */
    fun dispatch(event: ListEvent<Row, Todo>) {
        var effect: ListEffect? = null
        var draft: String? = null
        state.update { model ->
            var list = listStep(model.list, event)
            draft = draftsOpening(model.view == View.Fixed(Mailbox.DRAFTS), event, model.list.open, list.open)
            if (draft != null) list = list.copy(open = null)
            effect = list.effect
            model.copy(list = list)
        }
        draft?.let { key -> sending.openThreadDraft(state.value.threadsOf(key).toSet()) { open(key) } }
        // A bundle's next page is asked for in modelChanged, which sees every step's, layouts too.
        when (val asked = effect) {
            is ListEffect.Edit -> todos.setEditing(asked.todoId)
            is ListEffect.Act -> command(asked)
            is ListEffect.LoadMore, null -> Unit
        }
    }

    // An action key on what the list says it acts on: the open thread, the selection, or the focused
    // row (a bundle's threads for a bundle). Snooze and Labels open their menu on the screen.
    private fun command(act: ListEffect.Act) {
        val target = act.target
        val model = state.value
        val threads = model.selectedThreads(target.threads)
        val todoRows = target.todos.mapNotNull { id -> model.todos.firstOrNull { it.id == id } }
        val plan = selectionPlan(threads)
        val todoPlan = todoSelectionPlan(threads, todoRows.map { SelectedTodo(it.id, it.state == TodoState.DONE, it.pinned) })
        fun origin(action: SetAction) = when (target.from) {
            TargetSource.THREAD -> ActOrigin.THREAD
            TargetSource.SELECTION -> ActOrigin.selection(action)
            TargetSource.ROW -> ActOrigin.ROW
        }
        val bundle = target.bundle
        if (target.from == TargetSource.SELECTION) {
            // A key acts on the selection only as the selection bar would offer it.
            val offered = selectionActions(target.threads.size, target.todos.size)
            if (SELECTION_KEY_ACTIONS[act.action] !in offered) return
        }
        // On a bundle's header only Done and Snooze act, on every thread in it.
        if (bundle != null) {
            if (act.action == ListCommands.DONE) doneBundle(bundle, plan.done, origin(SetAction.DONE))
            if (act.action == ListCommands.SNOOZE) listSheets.tryEmit(act)
            return
        }
        when (act.action) {
            ListCommands.DONE -> {
                val inbox = inboxPlan(threads.map { it.id to model.filtered(requireNotNull(model.rowSummary(it.id)), model.rowStatus(it.id)) }).inbox
                doneOrMoveToInbox(plan.done, inbox, todoPlan.done, origin(SetAction.DONE))
            }
            ListCommands.PIN -> setPinnedWithTodos(todoPlan.pin.threads, todoPlan.pin.todos, todoPlan.pin.on, origin(SetAction.PIN))
            ListCommands.SPAM -> if (plan.spam.on) reportSpam(plan.spam.ids, origin(SetAction.SPAM)) else notSpam(plan.spam.ids, origin(SetAction.SPAM))
            ListCommands.READ -> setUnread(target.threads, unread = false, origin(SetAction.UNREAD))
            ListCommands.UNREAD -> setUnread(target.threads, unread = true, origin(SetAction.UNREAD))
            ListCommands.SNOOZE, ListCommands.LABEL -> listSheets.tryEmit(act)
            else -> Log.w(TAG, "no action ${act.action}")
        }
    }

    // Carries out [plan] in one step: the list hears first which of its rows the change takes out
    // (as from [origin]; null for the rest of an action it already heard of), so focus, the open
    // thread, selection and undo go with them; then its changes are queued and written, and its
    // undo offered. Nothing happens for a plan with nothing to change.
    private fun carryOut(plan: ActionPlan, origin: ActOrigin?): Carried {
        // The store's own todos, since the model's copy of them can lag a change the store just made.
        val live = todos.snapshot.value
        val todoIds = plan.todos.flatMap { it.ids }
        if (plan.threads.isEmpty() && todoIds.none { live.textOf(it) != null }) return Carried()
        todoIds.forEach(::stopEditing)
        if (origin != null) {
            val after = layoutKeys(shown(state.value, plan).layout)
            dispatch(ListEvent.Act(plan.keys.filterNot { it in after }, origin.from, plan.undo != null, origin.clearSelection))
        }
        val open = state.value.openId
        if (plan.closes && open != null && (open in plan.keys || open in plan.threads)) close()
        clearNotifications(plan.cleared)
        val saving = changeAll(plan.changes, plan.fromNotification)
        val written = plan.snoozes.map { (id, snooze) -> snoozeDocs.write(id, snooze) }
        val snoozed = plan.snoozes.keys
        if (snoozed.isNotEmpty()) state.update { it.copy(woken = it.woken - snoozed, skippedInbox = it.skippedInbox - snoozed) }
        plan.unsnoozed.forEach(snoozeDocs::delete)
        val todoUndo = plan.todos.fold(null as TodoUndo?) { joined, step -> step.carry(step.ids)?.let { joinTodoUndo(joined, it) } ?: joined }
        val acted = plan.threads.isNotEmpty() || todoUndo != null
        val pick = plan.pick?.takeIf { acted }
        val statsUndo = pick?.let { snoozeStats.commit(System.currentTimeMillis(), it.epoch, it.source, it.key) }
        val entry = plan.undo?.let { offerUndo(it, todoUndo, statsUndo) }
        return Carried(acted, entry, saving, written)
    }

    // Offers to undo an action, unless it changed nothing undo could put back; a label change made
    // while another's undo is still on offer joins that undo.
    private fun offerUndo(undo: UndoPlan, todoUndo: TodoUndo?, statsUndo: SnoozeStatsUndo?): UndoEntry? {
        if (undo.items.isEmpty() && todoUndo == null) return null
        val previous = undoState.value
        val entry = if (undo.kind == UndoKind.LABEL && previous?.kind == UndoKind.LABEL) {
            UndoEntry(
                undo.kind,
                mergeUndoItems(previous.items, undo.items, UndoItem::id),
                labels = undoLabels(undo.kind, previous.labels.orEmpty() + undo.touched.orEmpty()),
            )
        } else {
            UndoEntry(undo.kind, undo.items, statsUndo, undo.pinned, todoUndo, undo.touched?.let { undoLabels(undo.kind, it) })
        }
        undoState.value = entry
        return entry
    }

    // The model as it shows once [plan] is carried out: what the list's step is computed against.
    private fun shown(model: MailModel, plan: ActionPlan): MailModel {
        val snoozed = plan.snoozes.keys
        val snoozes = model.copy(
            snoozeDocs = model.snoozeDocs - plan.unsnoozed + plan.snoozes,
            woken = model.woken - snoozed,
            skippedInbox = model.skippedInbox - snoozed,
        )
        val summaries = snoozes.summaries.toMutableMap()
        for (change in plan.changes) {
            val thread = summaries[change.threadId] ?: continue
            summaries[change.threadId] = thread.withChange(gmailIds(change.add), gmailIds(change.remove))
        }
        return plan.todos.fold(snoozes.copy(summaries = summaries)) { next, step -> next.withTodos(step) }
    }

    // Every change to the model goes through here before anyone sees it: another open thread drops
    // the last one's messages, a bundle that opened holds its place, and whenever the sections
    // change the list takes them as its new layout, told first where the screen sits.
    private fun settleModel(before: MailModel, after: MailModel): MailModel {
        var model = after
        // A thread opened before it loaded (from a notification) may since have joined another's
        // row, as a split encrypted reply does: the row opens in its place.
        val opened = model.list.open
        val lead = opened?.let(model::rowKeyOf)
        val joinedInto = model.takeIf { lead != opened }
        if (lead != null && joinedInto != null) model = model.copy(list = listStep(model.list, ListEvent.OpenThread(lead)))
        if (model.list.open != before.list.open) {
            // The open row stays listed and joined until it closes, even once it leaves the list.
            val heldJoin = model.list.open?.let { (joinedInto ?: model.copy(heldJoin = emptyList())).threadsOf(it) }.orEmpty()
            model = model.copy(detail = null, detailError = null, heldJoin = heldJoin)
        }
        val held = holdPlaces(model.held, heldNow(model), release = false)
        if (held !== model.held) model = model.copy(held = held)
        if (sameLayoutInputs(before, model)) return model
        val layout = model.layout
        if (layout == model.list.layout) return model
        var list = model.list
        val anchor = anchorSource?.invoke()?.takeIf { before.view == model.view }
        if (anchor != null) list = listStep(list, ListEvent.Anchor(anchor.key, anchor.offset))
        list = listStep(list, ListEvent.Layout(layout))
        // The layout step must not drop what the change's own step asked for.
        val asked = model.list.effect.takeIf { model.list !== before.list }
        if (list.effect == null && asked != null) list = list.copy(effect = asked)
        return model.copy(list = list)
    }

    // What follows a change once it is in: a bundle page the list asked for loads, a newly open
    // thread loads and its notification goes, a newly open bundle fetches its next page, and a closed bundle lets go of its place after its close plays.
    private fun modelChanged(before: MailModel, after: MailModel) {
        val asked = after.list.effect
        if (after.list !== before.list && asked is ListEffect.LoadMore) loadMoreBundle(asked.labelId)
        val open = after.list.open
        if (open != null && open != before.list.open) {
            // A reply asked for on a thread whose row opened in its place goes from that row.
            if (pendingReply != null && pendingReply == before.list.open && pendingReply in after.heldJoin) pendingReply = open
            clearNotifications(after.threadsOf(open))
            detailRetry?.cancel()
            scope.launch { loadDetail(open, fromCache = true) }
        }
        val bundle = after.list.bundle?.labelId
        if (bundle != null && bundle != before.list.bundle?.labelId) fetchOpenedBundle(bundle)
        val letGo = after.held.bundles.keys.any { it != bundle } || after.held.threads.keys.any { it != after.list.open }
        if (letGo && releaseHeld?.isActive != true) {
            releaseHeld = scope.launch {
                delay(HELD_RELEASE_MS)
                state.update { model ->
                    val held = holdPlaces(model.held, heldNow(model), release = true)
                    if (held === model.held) model else model.copy(held = held)
                }
            }
        }
    }

    // What the saved copy asks of the mail around it.
    private inner class SavedMailHost : OfflineHost {
        override fun snoozed(): Set<String> {
            val now = System.currentTimeMillis()
            return state.value.snoozeDocs.filterValues { doc -> doc.snoozeUntil?.let { it > now } == true }.keys
        }

        // Rows only the saved copy needs go to the device's cache, not the screen's, which keeps the
        // inbox's and those already loaded.
        override fun changedHere(): Set<String> = locallyChanged()

        override suspend fun save(threads: List<GmailThread>, changedBefore: Set<String>) {
            val fresh = withContext(Dispatchers.Default) { threads.map(::summarizeThread) }
            val rows = fresh.map(::withQueued)
            val known = state.value.summaries
            val (shown, stored) = rows.partition { it.id in known || Labels.INBOX in it.labelIds }
            putSummaries(shown)
            runSuspendCatching { cache.putSummaries(stored) }.onFailure { Log.w(TAG, "cache write failed", it) }
            dropSnoozesArchivedElsewhere(fresh, changedBefore)
            for (thread in threads) {
                val plain = withContext(Dispatchers.Default) { threadDetail(thread) }
                val reveal = encryption.reveal(thread)
                runSuspendCatching { cache.putThread(revealed(plain, reveal.mapValues { it.value.cacheable })) }
                    .onFailure { Log.w(TAG, "cache write failed", it) }
            }
            val ids = threads.map { it.id }
            forgetBodies(ids)
            reshowSaved(ids, newMail = true)
        }

        override suspend fun append(threadId: String, messages: List<GmailMessage>, labels: Map<String, List<String>>): Boolean {
            val changedBefore = locallyChanged()
            val stored = runSuspendCatching { cache.storedThread(threadId) }.getOrNull() ?: return false
            val added = withContext(Dispatchers.Default) { withAddedMessages(stored, messages) }
            val newest = messages.mapNotNull { it.historyId }.fold(stored.historyId) { best, next ->
                if (runCatching { compareHistoryIds(next, best) > 0 }.getOrDefault(false)) next else best
            }
            val reveal = encryption.reveal(GmailThread(id = threadId, messages = messages))
            val body = revealed(added, reveal.mapValues { it.value.cacheable }).copy(historyId = newest)
            if (runSuspendCatching { cache.putThread(body) }.onFailure { Log.w(TAG, "cache write failed", it) }.isFailure) return false
            forgetBodies(listOf(threadId))
            val base = state.value.summaries[threadId] ?: runSuspendCatching { cache.summaries(listOf(threadId)) }.getOrDefault(emptyList()).firstOrNull()
            val fresh = savedSummary(body, labels, base, newest)
            val row = withQueued(fresh)
            if (threadId in state.value.summaries || Labels.INBOX in row.labelIds) {
                putSummaries(listOf(row))
            } else {
                runSuspendCatching { cache.putSummaries(listOf(row)) }.onFailure { Log.w(TAG, "cache write failed", it) }
            }
            dropSnoozesArchivedElsewhere(listOf(fresh), changedBefore)
            reshowSaved(listOf(threadId), newMail = true)
            return true
        }

        override suspend fun relabel(messages: Map<String, Map<String, List<String>>>) {
            for ((id, own) in messages) {
                runSuspendCatching {
                    val stored = cache.storedThread(id) ?: return@runSuspendCatching
                    val next = stored.relabeled(id, own)
                    if (next !== stored) cache.putThread(next)
                }.onFailure { Log.w(TAG, "cache write failed", it) }
            }
            for ((id, own) in messages) savedBodies[id]?.let { body -> savedBodies[id] = body.relabeled(id, own) }
            state.update { model ->
                val open = model.detail ?: return@update model
                val next = messages.entries.fold(open) { detail, (id, own) -> detail.relabeled(id, own) }
                if (next === open) model else model.copy(detail = next)
            }
            val labels = messages.mapValues { (_, own) -> threadLabels(own) }
            val missing = labels.keys.filterNot { it in state.value.summaries }
            val stored = runSuspendCatching { cache.summaries(missing) }.getOrDefault(emptyList()).associateBy { it.id }
            val rows = labels.mapNotNull { (id, own) ->
                (state.value.summaries[id] ?: stored[id])?.let { summary ->
                    withQueued(summary.copy(labelIds = own.sorted(), unread = Labels.UNREAD in own))
                }
            }
            putSummaries(rows)
            // A snoozed thread archived elsewhere is Done; its fresh row tells (see [dropSnoozesArchivedElsewhere]).
            val snoozedOut = labels.filter { (id, own) -> id in state.value.snoozeDocs && Labels.INBOX !in own }.keys
            if (snoozedOut.isNotEmpty()) scope.launch(BACKGROUND) { fetchSummaries(snoozedOut.map { GmailThreadStub(it) }) }
        }

        override fun remove(ids: List<String>) = removeGone(ids)

        // Lists built from Gmail's pages until now come from the saved copy.
        override fun rebuilt() = refresh(force = true)

        override fun caughtUp() = refresh(force = true)

        override fun mayFill(): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
            return network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    // The places the list holds now: the open bundle's and thread's, and the pinning of the thread or
    // todo the keyboard focused, from when each was first held.
    private fun heldNow(model: MailModel): HeldPlaces {
        val labelId = model.list.bundle?.labelId
        val threadId = model.list.open
        val focusedId = pinHeldThread(model)
        val todoId = rowTodoId(pinHeldFocus(model.list.focus, model.list.focusBy))
        val todoKey = todoId?.let(::todoRowKey)
        val keptBundle = labelId?.let(model.held.bundles::get)
        val keptThread = threadId?.let(model.held.threads::get)
        val keptPin = focusedId?.let(model.held.pins::get)
        val keptTodoPin = todoKey?.let(model.held.pins::get)
        val missing = (labelId != null && keptBundle == null) ||
            (threadId != null && keptThread == null) ||
            (focusedId != null && keptPin == null) ||
            (todoKey != null && keptTodoPin == null)
        val fresh = if (missing) {
            openPlaces(model.sections(), setOfNotNull(labelId), threadId, focusedId, focusedTodo = todoId)
        } else {
            HeldPlaces()
        }
        val thread = threadId?.let { id -> (keptThread ?: fresh.threads[id] ?: model.todosViewPlaceOf(id))?.let { mapOf(id to it) } }
        val bundle = labelId?.let { id -> (keptBundle ?: fresh.bundles[id])?.let { mapOf(id to it) } }
        val pin = focusedId?.let { id -> (keptPin ?: fresh.pins[id])?.let { mapOf(id to it) } }
        val todoPin = todoKey?.let { key -> (keptTodoPin ?: fresh.pins[key])?.let { mapOf(key to it) } }
        return HeldPlaces(thread.orEmpty(), bundle.orEmpty(), pin.orEmpty() + todoPin.orEmpty())
    }

    // The thread the keyboard focused when it isn't open, whose pinning moves it only once focus leaves.
    private fun pinHeldThread(model: MailModel): String? =
        rowThreadId(pinHeldFocus(model.list.focus, model.list.focusBy))?.takeIf { it != model.list.open }

    // A bundle opened fetches its next page once (after its first, while that is on its way).
    private fun fetchOpenedBundle(labelId: String) {
        if (labelId in bundlesPaged) return
        scope.launch {
            bundleRequests[labelId]?.await()
            if (labelId !in bundlesPaged) loadBundle(labelId, more = true)
        }
    }
}

// A history scan for newer mail on snoozed threads, read a few pages per check.
private class WakeScan(
    // the history id it reads from
    val start: String,
) {
    // Gmail's token for the next page; null once it reached the end
    var pageToken: String? = null

    // the records read so far, oldest first
    val records = mutableListOf<GmailHistoryRecord>()
}
