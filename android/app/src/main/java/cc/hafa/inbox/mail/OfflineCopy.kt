package cc.hafa.inbox.mail

import android.util.Log
import cc.hafa.inbox.core.gmail.CallPriority
import cc.hafa.inbox.core.gmail.GmailClient
import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.gmail.GmailMessage
import cc.hafa.inbox.core.gmail.GmailPriority
import cc.hafa.inbox.core.gmail.GmailThread
import cc.hafa.inbox.core.gmail.ListOptions
import cc.hafa.inbox.core.gmail.isNetworkError
import cc.hafa.inbox.core.gmail.isNotFound
import cc.hafa.inbox.core.gmail.mapLimited
import cc.hafa.inbox.core.mail.DEFAULT_OFFLINE_DAYS
import cc.hafa.inbox.core.mail.HistoryNext
import cc.hafa.inbox.core.mail.HistoryRead
import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.OfflineThread
import cc.hafa.inbox.core.mail.PINNED_LABEL
import cc.hafa.inbox.core.mail.SavedDraftMessage
import cc.hafa.inbox.core.mail.SavedDraftRefs
import cc.hafa.inbox.core.mail.SyncMode
import cc.hafa.inbox.core.mail.SyncRun
import cc.hafa.inbox.core.mail.SyncState
import cc.hafa.inbox.core.mail.afterDraftsListing
import cc.hafa.inbox.core.mail.afterHistory
import cc.hafa.inbox.core.mail.applyHistory
import cc.hafa.inbox.core.mail.compareHistoryIds
import cc.hafa.inbox.core.mail.draftsListingWanted
import cc.hafa.inbox.core.mail.dropsUnlisted
import cc.hafa.inbox.core.mail.offlineDays
import cc.hafa.inbox.core.mail.offlinePlan
import cc.hafa.inbox.core.mail.offlineQueries
import cc.hafa.inbox.core.mail.offlineTier
import cc.hafa.inbox.core.mail.readOrder
import cc.hafa.inbox.core.mail.savedDraftMessages
import cc.hafa.inbox.core.mail.sentDrafts
import cc.hafa.inbox.core.mail.syncAfter
import cc.hafa.inbox.core.mail.syncMode
import cc.hafa.inbox.core.mail.threadLabels
import cc.hafa.inbox.core.pgp.isEncryptedMessage
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.send.GmailDraftRef
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.data.MailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** What the saved copy knows of one saved thread besides its row and bodies. */
data class SavedThread(
    /** the thread's history id when it was read */
    val historyId: String,
    /** message id to its label ids */
    val messages: Map<String, List<String>>,
    /** its newest message's date, epoch milliseconds */
    val newestAt: Long,
    /** when it was last opened on this device, epoch milliseconds, or null if never */
    val openedAt: Long?,
    /** whether any of its messages is encrypted, so it is kept at any age */
    val encrypted: Boolean,
)

// Whether history id [historyId] is past [reached]; an unreadable one isn't.
private fun newerThan(historyId: String, reached: String): Boolean =
    runCatching { compareHistoryIds(historyId, reached) > 0 }.getOrDefault(false)

/**
 * The saved copy's state after [run] ([syncAfter]), keeping a history id newer than the one it
 * reached: a rebuild reaches the id from its start, and history syncs running beside it may have
 * moved past that.
 */
internal fun settledState(before: SyncState, run: SyncRun): SyncState {
    val after = syncAfter(before, run)
    val kept = before.historyId
    val reached = after.historyId
    return if (kept != null && reached != null && newerThan(kept, reached)) after.copy(historyId = kept) else after
}

/** A saved-copy record of a thread read whole. */
fun savedThread(thread: GmailThread, openedAt: Long?): SavedThread {
    val messages = thread.messages.orEmpty()
    return SavedThread(
        historyId = thread.historyId.orEmpty(),
        messages = messages.associate { it.id to it.labelIds.orEmpty() },
        newestAt = messages.maxOfOrNull { it.internalDate?.toLongOrNull() ?: 0L } ?: 0L,
        openedAt = openedAt,
        encrypted = messages.any(::isEncryptedMessage),
    )
}

// A saved thread's record with its messages' labels now and the messages just added to it.
private fun withMessages(saved: SavedThread, messages: Map<String, List<String>>, fresh: List<GmailMessage>): SavedThread {
    var historyId = saved.historyId
    for (message in fresh) {
        val next = message.historyId.orEmpty()
        if (historyId.isEmpty() || (next.isNotEmpty() && newerThan(next, historyId))) historyId = next
    }
    return saved.copy(
        historyId = historyId,
        messages = messages,
        newestAt = maxOf(saved.newestAt, fresh.maxOfOrNull { it.internalDate?.toLongOrNull() ?: 0L } ?: 0L),
        encrypted = saved.encrypted || fresh.any(::isEncryptedMessage),
    )
}

/** What Settings shows of the saved copy. */
data class OfflineInfo(
    /** threads saved */
    val count: Int = 0,
    /** days of mail saved besides the inbox */
    val days: Int = DEFAULT_OFFLINE_DAYS,
    /** whether a whole rebuild filled the copy */
    val filled: Boolean = false,
    /** whether the saved records and settings were read */
    val loaded: Boolean = false,
)

/** What the saved copy asks of the app around it. */
interface OfflineHost {
    /** threads whose snooze is still ahead */
    fun snoozed(): Set<String>

    /** threads with a change made here not yet known to be in Gmail (queued, or undoable) */
    fun changedHere(): Set<String>

    /** save rows and bodies of threads just read whole; [changedBefore] is [changedHere] from before the read */
    suspend fun save(threads: List<GmailThread>, changedBefore: Set<String>)

    /**
     * add [messages] new to saved thread [threadId] to its saved body and row, [labels] being every
     * message's labels after; false when its body isn't saved, so it has to be read whole
     */
    suspend fun append(threadId: String, messages: List<GmailMessage>, labels: Map<String, List<String>>): Boolean

    /** set saved threads' rows and bodies to new labels (thread id to its messages' labels) */
    suspend fun relabel(messages: Map<String, Map<String, List<String>>>)

    /** forget threads Gmail no longer has */
    fun remove(ids: List<String>)

    /** a rebuild came back whole: lists can be built from the saved copy again */
    fun rebuilt()

    /** threads a sync read after returning are saved: lists from the saved copy may show more */
    fun caughtUp()

    /** whether a first fill may run now: only on a network that isn't metered */
    fun mayFill(): Boolean
}

/** What an incremental sync read and did. */
data class SyncOutcome(
    /** the history records applied, oldest first */
    val records: List<GmailHistoryRecord>,
    /** threads history named that aren't saved, whose rows may be out of date */
    val touched: List<String>,
)

private const val TAG = "OfflineCopy"
private const val HISTORY_KEY = "offlineHistoryId"
private const val FILLED_KEY = "offlineFilled"
private const val DAYS_KEY = "offlineDays"
private const val RETRY_KEY = "offlineRetry"
private const val DRAFTS_KEY = "offlineDrafts"
private const val SENT_KEY = "offlineSentDrafts"
private val STRING_LIST = ListSerializer(String.serializer())
private val DRAFT_REFS = ListSerializer(GmailDraftRef.serializer())

// Threads read whole at once; each batch is saved before the next, so a fill cut short keeps them.
private const val BATCH = 25
private const val READS_AT_ONCE = 4
private const val HISTORY_PAGE = 500
private const val LIST_PAGE = 500
private val HIDDEN = setOf(Labels.SPAM, Labels.TRASH)

// A sync's reads wait behind what the screen asks for; a rebuild's (the first fill) yield to
// every other call and are paced, so opening a thread stays quick while it runs.
private val BACKGROUND = GmailPriority(CallPriority.BACKGROUND)
private val SAVING = GmailPriority(CallPriority.SAVING)

/**
 * The labels a list of the inbox's own threads asks for, when the saved copy can answer it: the
 * inbox, a bundle, pinned, and focus mode's query. Its query may only leave bundles out (`-label:`,
 * `-category:`), which the inbox's rows do anyway. Null for any other list.
 */
fun savedListLabels(options: ListOptions): List<String>? {
    if (Labels.INBOX !in options.labelIds || options.includeSpamTrash) return null
    val extra = mutableListOf<String>()
    for (term in options.query.orEmpty().split(Regex("\\s+"))) {
        when {
            term.isEmpty() || term.startsWith("-label:") || term.startsWith("-category:") -> Unit
            term == "is:starred" -> extra += PINNED_LABEL
            else -> return null
        }
    }
    return (options.labelIds + extra).distinct()
}

/**
 * The device's saved copy of mail for reading offline (design.md, "Offline"): which threads are
 * saved with their bodies, kept in step with Gmail's history from a history id it keeps across
 * restarts, or rebuilt in full when it has none or Gmail no longer knows it. The web's
 * web/src/lib/offline-copy.ts is the same.
 */
class OfflineCopy(
    private val gmail: GmailClient,
    private val cache: MailCache,
    private val host: OfflineHost,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val index = LinkedHashMap<String, SavedThread>()
    private val indexLock = Mutex()
    private var syncState = SyncState(historyId = null, filled = false)
    private var days = DEFAULT_OFFLINE_DAYS
    // The running rebuild and the running history sync; a history sync may run beside a rebuild.
    private var rebuilding: Job? = null
    private var syncing: Job? = null
    private var rebuildWanted = false

    // Gmail no longer knows the saved history id; only a whole rebuild replaces it.
    private var expired = false

    // Threads whose read Gmail refused (not the network), read again at each sync until one works.
    private val retry = linkedSetOf<String>()

    // Gmail's drafts as last listed, and the drafts the device knows went (core OfflineDrafts.kt).
    @Volatile private var draftRefs = SavedDraftRefs()

    @Volatile private var sent: List<GmailDraftRef> = emptyList()
    private val sentLock = Mutex()

    // Whether the last sync or rebuild came back whole.
    private var lastWhole = false
    private val loaded = CompletableDeferred<Unit>()
    private val infoState = MutableStateFlow(OfflineInfo())

    // Bodies saved from here on (a thread opened at start) are kept by [load]'s prune.
    private val createdAt = now()

    /** What Settings shows: how many threads are saved and how many days. */
    val info: StateFlow<OfflineInfo> = infoState

    /** Whether a whole rebuild filled the copy, so the inbox's lists come from it. */
    val filled: Boolean get() = syncState.filled

    /** Whether a sync or rebuild is running. */
    val busy: Boolean get() = rebuilding?.isActive == true || syncing?.isActive == true

    /** Read the saved records and settings, and drop bodies saved before this start that no record names. */
    suspend fun load() {
        runSuspendCatching { cache.pruneUnsavedThreads(before = createdAt) }.onFailure { Log.w(TAG, "cache prune failed", it) }
        try {
            val entries = cache.offlineThreads()
            val historyId = cache.meta(HISTORY_KEY, String.serializer())?.takeIf { it.isNotEmpty() }
            val wasFilled = cache.meta(FILLED_KEY, Boolean.serializer()) ?: false
            val savedDays = cache.meta(DAYS_KEY, Int.serializer())
            retry += cache.meta(RETRY_KEY, STRING_LIST).orEmpty()
            draftRefs = cache.meta(DRAFTS_KEY, SavedDraftRefs.serializer()) ?: SavedDraftRefs()
            sent = cache.meta(SENT_KEY, DRAFT_REFS).orEmpty()
            indexLock.withLock { index.putAll(entries) }
            syncState = SyncState(historyId, wasFilled)
            if (savedDays != null) days = offlineDays(savedDays)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "saved mail read failed", error)
        }
        loaded.complete(Unit)
        publish()
    }

    /** Wait for [load]. */
    suspend fun ready() = loaded.await()

    private suspend fun publish() {
        val count = indexLock.withLock { index.size }
        infoState.value = OfflineInfo(count, days, syncState.filled, loaded.isCompleted)
    }

    /** Whether every one of [ids] is saved, once the saved records are read. */
    suspend fun hasAll(ids: List<String>): Boolean {
        ready()
        return indexLock.withLock { ids.isNotEmpty() && ids.all(index::containsKey) }
    }

    /** Every saved thread carrying all of [labels]. */
    suspend fun savedIds(labels: List<String>): List<String> = indexLock.withLock {
        index.filter { (_, saved) -> labels.all { it in threadLabels(saved.messages) } }.keys.toList()
    }

    /** The saved threads' draft messages. */
    suspend fun savedDrafts(): List<SavedDraftMessage> = indexLock.withLock { savedDraftMessages(index.mapValues { it.value.messages }) }

    /** Gmail's drafts as last listed, newest first. */
    val savedDraftRefs: List<GmailDraftRef> get() = draftRefs.refs

    /** The drafts the device knows went that the saved copy still holds. */
    val sentDrafts: List<GmailDraftRef> get() = sent

    /**
     * Note the outbox [docs]: a draft a sent one names is kept out of Drafts until the saved copy
     * drops it, even once the doc is gone. [known] are the drafts this device is sending.
     */
    suspend fun noteSent(docs: List<OutboxDoc>, known: List<GmailDraftRef>) {
        ready()
        val saved = savedDrafts()
        sentLock.withLock { putSent(sentDrafts(sent, docs, known + draftRefs.refs, saved)) }
    }

    private suspend fun putSent(next: List<GmailDraftRef>) {
        if (next == sent) return
        sent = next
        runSuspendCatching { cache.putMeta(SENT_KEY, DRAFT_REFS, next) }.onFailure { Log.w(TAG, "saved mail write failed", it) }
    }

    // List Gmail's drafts when a saved draft message isn't in the last listing, so each opens
    // offline; and forget sent drafts the saved copy no longer holds.
    private suspend fun settleDrafts() {
        if (draftsListingWanted(savedDrafts(), draftRefs)) {
            try {
                val listed = mutableListOf<GmailDraftRef>()
                var pageToken: String? = null
                do {
                    val page = gmail.listDrafts(pageToken)
                    for (draft in page.drafts.orEmpty()) {
                        val message = draft.message ?: continue
                        listed += GmailDraftRef(draft.id, message.id, message.threadId)
                    }
                    pageToken = page.nextPageToken
                } while (pageToken != null)
                draftRefs = afterDraftsListing(listed, savedDrafts())
                runSuspendCatching { cache.putMeta(DRAFTS_KEY, SavedDraftRefs.serializer(), draftRefs) }
                    .onFailure { Log.w(TAG, "saved mail write failed", it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "drafts listing failed", error)
            }
        }
        val saved = savedDrafts()
        sentLock.withLock { putSent(sentDrafts(sent, emptyList(), emptyList(), saved)) }
    }

    /** Note threads read whole elsewhere (an opened thread), whose rows and bodies are saved already. */
    suspend fun noteRead(threads: List<GmailThread>, opened: Boolean) {
        ready()
        val at = now()
        val entries = indexLock.withLock {
            threads.map { thread -> thread.id to savedThread(thread, if (opened) at else index[thread.id]?.openedAt) }
        }
        remember(entries)
    }

    /** Note that a saved thread was opened, so it stays saved as recently opened. */
    suspend fun noteOpened(id: String) {
        ready()
        val saved = indexLock.withLock { index[id] } ?: return
        remember(listOf(id to saved.copy(openedAt = now())))
    }

    /** Change how many days of mail are saved; more days fetch the older mail, fewer drop it. */
    suspend fun setDays(value: Int) {
        ready()
        val next = offlineDays(value)
        if (next == days) return
        val more = next > days
        days = next
        runSuspendCatching { cache.putMeta(DAYS_KEY, Int.serializer(), next) }.onFailure { Log.w(TAG, "offline setting write failed", it) }
        publish()
        if (more) {
            rebuildWanted = true
            sync()
        } else {
            trim()
        }
    }

    /**
     * Bring the copy up to date. Once filled, reads history from the saved id and returns what it
     * applied, even while a rebuild runs; a rebuild it needs (the first fill, more days, or an id
     * Gmail no longer knows) starts in the background. Returns null when it read no history: before
     * the first fill, with the id expired, while another history sync runs, or when history couldn't
     * be read. Threads past the first batch are read after it returns.
     */
    suspend fun sync(): SyncOutcome? {
        ready()
        val mode = syncMode(syncState.historyId, syncState.filled)
        if (rebuilding?.isActive != true && (rebuildWanted || expired || mode == SyncMode.REBUILD)) startRebuild()
        if (mode == SyncMode.REBUILD || expired || syncing?.isActive == true) return null
        val answer = CompletableDeferred<SyncOutcome?>()
        // At the caller's urgency, which a refresh the user waits for may raise.
        syncing = scope.launch(currentCoroutineContext()[GmailPriority] ?: BACKGROUND) {
            try {
                incremental(answer)
            } finally {
                answer.complete(null)
            }
        }
        return answer.await()
    }

    /** Wait for a running sync or rebuild; whether the copy is filled and the last one came back whole. */
    suspend fun settled(): Boolean {
        syncing?.join()
        rebuilding?.join()
        return lastWhole && syncState.filled
    }

    private fun startRebuild() {
        // The first fill waits for a network that isn't metered; later rebuilds read only what changed.
        if (!syncState.filled && !host.mayFill()) return
        rebuildWanted = false
        rebuilding = scope.launch(SAVING) {
            try {
                rebuild()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "saved mail rebuild failed", error)
            }
        }
    }

    // Reads history and applies it, completing [answer] once the first batch of threads is read;
    // the rest are read before the history id moves on.
    private suspend fun incremental(answer: CompletableDeferred<SyncOutcome?>) {
        lastWhole = false
        val start = syncState.historyId ?: return
        val records = mutableListOf<GmailHistoryRecord>()
        var reached: String? = null
        var read = HistoryRead.COMPLETE
        try {
            var pageToken: String? = null
            do {
                val page = gmail.listHistory(start, pageToken, HISTORY_PAGE, types = null)
                records += page.history.orEmpty()
                reached = page.historyId ?: reached
                pageToken = page.nextPageToken
            } while (pageToken != null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            read = if (isNotFound(error)) HistoryRead.EXPIRED else HistoryRead.FAILED
        }
        when (afterHistory(read)) {
            HistoryNext.STOP -> return
            HistoryNext.REBUILD -> {
                expired = true
                if (rebuilding?.isActive != true) startRebuild()
                return
            }
            HistoryNext.APPLY -> Unit
        }
        val savedMessages = indexLock.withLock { index.mapValues { it.value.messages } }
        val changes = applyHistory(records, savedMessages::get, days)
        val relabeled = indexLock.withLock {
            changes.relabeled.mapNotNull { (id, messages) -> index[id]?.let { id to it.copy(messages = messages) } }
        }
        remember(relabeled)
        if (relabeled.isNotEmpty()) host.relabel(relabeled.associate { (id, saved) -> id to saved.messages })
        forget(changes.removed)
        val addedRead = readAdded(changes.added)
        val ordered = readOrder(records, changes.fetch)
        val (checked, checkedWhole) = encryptedAmong(changes.check)
        val retried = retry - ordered.toSet() - checked.toSet()
        val fetch = ordered + checked + retried
        // The first batch (the inbox's newest) is saved before the sync answers; the rest after.
        val firstRead = read(fetch.take(BATCH))
        answer.complete(SyncOutcome(records, changes.touched))
        val rest = fetch.drop(BATCH)
        val restRead = read(rest)
        trim()
        settleDrafts()
        settle(SyncRun(SyncMode.INCREMENTAL, listed = true, read = addedRead && firstRead && restRead && checkedWhole, reached = reached))
        // Threads refused before don't count, so a refused one can't make every sync reload the lists.
        if (rest.any { it !in retried }) host.caughtUp()
    }

    private suspend fun rebuild() {
        lastWhole = false
        val reached = try {
            gmail.getProfile().historyId
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return
        }
        // Thread id to the history id its list gave (none for a snoozed thread no list named).
        val listed = LinkedHashMap<String, String?>()
        var complete = true
        for ((position, search) in offlineQueries(days).withIndex()) {
            val before = listed.size
            try {
                var pageToken: String? = null
                do {
                    val page = gmail.listThreads(ListOptions(query = search.query, maxResults = LIST_PAGE, pageToken = pageToken))
                    for (stub in page.threads.orEmpty()) if (stub.id !in listed) listed[stub.id] = stub.historyId
                    pageToken = page.nextPageToken
                    // Past the cap the oldest threads would only be dropped again.
                    val cap = search.cap
                    if (cap != null && listed.size - before >= cap) break
                } while (pageToken != null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                complete = false
                break
            }
            if (position == 0) for (id in host.snoozed()) if (id !in listed) listed[id] = null
        }
        val wanted = indexLock.withLock {
            listed.filter { (id, historyId) ->
                val saved = index[id]
                saved == null || (historyId != null && saved.historyId != historyId)
            }.keys.toList()
        }
        val read = read(wanted)
        if (dropsUnlisted(SyncMode.REBUILD, complete)) settleUnlisted(listed.keys, reached)
        trim()
        settleDrafts()
        settle(SyncRun(SyncMode.REBUILD, listed = complete, read = read, reached = reached))
        if (complete && read) {
            expired = false
            host.rebuilt()
        }
    }

    // Saved threads a whole rebuild didn't list are out of the inbox and not recent: those opened
    // lately stay without INBOX, the rest go. A thread read after the listing began (history id past
    // [reached]) may have arrived since, so it stays as read.
    private suspend fun settleUnlisted(listed: Set<String>, reached: String) {
        val snoozed = host.snoozed()
        val at = now()
        val kept = mutableListOf<Pair<String, SavedThread>>()
        val dropped = mutableListOf<String>()
        indexLock.withLock {
            for ((id, saved) in index) {
                if (id in listed || newerThan(saved.historyId, reached)) continue
                val messages = saved.messages.mapValues { (_, labels) -> labels.filter { it != Labels.INBOX } }
                val labels = threadLabels(messages)
                // A whole listing names every thread with a draft and every recent and encrypted thread,
                // so this one is none of those now.
                val tier = offlineTier(OfflineThread(id, labels - Labels.DRAFT, 0, saved.openedAt, encrypted = false), id in snoozed, days, at)
                if (tier == null) {
                    dropped += id
                } else if (labels.size != threadLabels(saved.messages).size) {
                    kept += id to saved.copy(messages = messages)
                }
            }
        }
        remember(kept)
        if (kept.isNotEmpty()) host.relabel(kept.associate { (id, saved) -> id to saved.messages })
        drop(dropped)
    }

    // Read threads whole, a batch at a time, saving each batch; whether every read came back. A
    // thread Gmail no longer has is forgotten; once the network is down the rest wait.
    private suspend fun read(ids: List<String>): Boolean {
        var complete = true
        for (batch in ids.chunked(BATCH)) {
            val changedBefore = host.changedHere()
            val results = mapLimited(batch, READS_AT_ONCE) { gmail.getThread(it, full = true) }
            val retryBefore = retry.toSet()
            val threads = mutableListOf<GmailThread>()
            val gone = mutableListOf<String>()
            var offline = false
            results.forEachIndexed { position, result ->
                val error = result.exceptionOrNull()
                val id = batch[position]
                when {
                    error == null -> {
                        threads += result.getOrThrow()
                        retry -= id
                    }
                    isNotFound(error) -> {
                        gone += id
                        retry -= id
                    }
                    isNetworkError(error) -> {
                        complete = false
                        offline = true
                    }
                    else -> {
                        // Gmail refused this one thread: the sync goes on without it and reads it next time.
                        retry += id
                    }
                }
            }
            if (retry != retryBefore) {
                runSuspendCatching { cache.putMeta(RETRY_KEY, STRING_LIST, retry.toList()) }.onFailure { Log.w(TAG, "saved mail write failed", it) }
            }
            if (threads.isNotEmpty()) {
                host.save(threads, changedBefore)
                noteRead(threads, opened = false)
            }
            forget(gone)
            if (offline) return false
        }
        return complete
    }

    // Read saved threads' new messages one by one and add them to their saved bodies (a message
    // never changes once sent, so the rest isn't read again); whether every read came back. A
    // thread whose message couldn't be read, or whose body isn't saved, is read whole instead.
    private suspend fun readAdded(added: Map<String, List<String>>): Boolean {
        val wanted = added.flatMap { (threadId, ids) -> ids.map { threadId to it } }
        if (wanted.isEmpty()) return true
        val results = mapLimited(wanted, READS_AT_ONCE) { (_, id) -> gmail.getMessage(id) }
        var complete = true
        val read = linkedMapOf<String, MutableList<GmailMessage>>()
        val gone = mutableSetOf<String>()
        val whole = linkedSetOf<String>()
        results.forEachIndexed { position, result ->
            val (threadId, id) = wanted[position]
            val error = result.exceptionOrNull()
            when {
                error == null -> read.getOrPut(threadId) { mutableListOf() } += result.getOrThrow()
                isNotFound(error) -> gone += id
                else -> {
                    // The copy already names the message, so a later sync wouldn't ask for it again.
                    whole += threadId
                    if (isNetworkError(error)) complete = false
                }
            }
        }
        val entries = mutableListOf<Pair<String, SavedThread>>()
        val emptied = mutableListOf<String>()
        for (threadId in added.keys) {
            val saved = indexLock.withLock { index[threadId] } ?: continue
            val messages = saved.messages.filterKeys { it !in gone }
            if (messages.isEmpty()) {
                emptied += threadId
                continue
            }
            val fresh = read[threadId].orEmpty()
            if (fresh.isNotEmpty() && threadId !in whole && !host.append(threadId, fresh, messages)) whole += threadId
            entries += threadId to withMessages(saved, messages, fresh)
        }
        remember(entries)
        forget(emptied)
        if (whole.isNotEmpty() && retry.addAll(whole)) {
            runSuspendCatching { cache.putMeta(RETRY_KEY, STRING_LIST, retry.toList()) }.onFailure { Log.w(TAG, "saved mail write failed", it) }
        }
        return complete
    }

    // Which of [ids] (threads not saved, with new mail) are encrypted and out of spam and trash, read
    // only as far as their headers; and whether every read came back.
    private suspend fun encryptedAmong(ids: List<String>): Pair<List<String>, Boolean> {
        val results = mapLimited(ids, READS_AT_ONCE) { gmail.getThread(it, full = false) }
        val encrypted = mutableListOf<String>()
        var complete = true
        results.forEachIndexed { position, result ->
            val error = result.exceptionOrNull()
            when {
                error == null -> {
                    val messages = result.getOrThrow().messages.orEmpty()
                    val hidden = messages.any { message -> message.labelIds.orEmpty().any { it in HIDDEN } }
                    if (!hidden && messages.any(::isEncryptedMessage)) encrypted += ids[position]
                }
                isNotFound(error) -> Unit
                else -> {
                    complete = false
                }
            }
        }
        return encrypted to complete
    }

    // Drop what the plan no longer keeps.
    private suspend fun trim() {
        val threads = indexLock.withLock {
            index.map { (id, saved) -> OfflineThread(id, threadLabels(saved.messages), saved.newestAt, saved.openedAt, saved.encrypted) }
        }
        drop(offlinePlan(threads, host.snoozed(), days, now()).drop)
    }

    private suspend fun remember(entries: List<Pair<String, SavedThread>>) {
        if (entries.isEmpty()) return
        indexLock.withLock { index.putAll(entries) }
        runSuspendCatching { cache.putOfflineThreads(entries) }.onFailure { Log.w(TAG, "saved mail write failed", it) }
        publish()
    }

    // Drop threads from the copy; their rows stay.
    private suspend fun drop(ids: List<String>) {
        if (ids.isEmpty()) return
        indexLock.withLock { ids.forEach(index::remove) }
        runSuspendCatching { cache.deleteOfflineThreads(ids) }.onFailure { Log.w(TAG, "saved mail write failed", it) }
        publish()
    }

    // Drop threads Gmail no longer has, rows and all.
    private suspend fun forget(ids: List<String>) {
        if (ids.isEmpty()) return
        drop(ids)
        host.remove(ids)
    }

    private suspend fun settle(run: SyncRun) {
        lastWhole = run.listed && run.read && run.reached != null
        val after = settledState(syncState, run)
        if (after == syncState) return
        syncState = after
        runSuspendCatching {
            cache.putMeta(HISTORY_KEY, String.serializer(), after.historyId.orEmpty())
            cache.putMeta(FILLED_KEY, Boolean.serializer(), after.filled)
        }.onFailure { Log.w(TAG, "saved mail write failed", it) }
        publish()
    }
}
