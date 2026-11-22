package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.label.LABEL_SWATCHES
import cc.hafa.inbox.core.label.LabelIcons
import cc.hafa.inbox.core.label.LabelPrefs
import cc.hafa.inbox.core.label.labelPrefsFrom
import cc.hafa.inbox.core.label.sortLabels
import cc.hafa.inbox.core.pgp.DecryptedPreview
import cc.hafa.inbox.core.search.searchPlan
import cc.hafa.inbox.core.search.searchEmailTodos
import cc.hafa.inbox.core.search.searchTodos
import cc.hafa.inbox.core.send.OutboxDrafts
import cc.hafa.inbox.core.send.draftThreadCount
import cc.hafa.inbox.core.send.onlyInOutbox
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoFilter
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.emailTodosByThread
import cc.hafa.inbox.core.todo.matches
import cc.hafa.inbox.core.todo.newestFirst
import cc.hafa.inbox.core.todo.rowEmailTodo
import cc.hafa.inbox.core.todo.todoLimit as pageLimit
import cc.hafa.inbox.core.todo.todosLoaded
import cc.hafa.inbox.core.todo.TodosViewBucket
import cc.hafa.inbox.core.todo.TodosViewEmail
import cc.hafa.inbox.core.todo.TodosViewEntry
import cc.hafa.inbox.core.todo.TodosViewPlace
import cc.hafa.inbox.core.todo.limitTodosView
import cc.hafa.inbox.core.todo.todosView
import cc.hafa.inbox.core.todo.todosViewCount
import cc.hafa.inbox.core.todo.todosViewPlace
import java.time.ZoneId

/** How many pages of todos a list shows (see [todoLimit]); counted afresh when the list changes. */
data class TodoPageCount(
    /** the list counted in ([MailModel.todoListKey]) */
    val list: String = "",
    /** how many pages it shows */
    val pages: Int = 1,
)

/** A list's ids and how far it has loaded. */
data class LoadedList(
    /** thread ids loaded so far, in list order */
    val ids: List<String> = emptyList(),
    /** Gmail's token for the next page; null when there is none */
    val nextPageToken: String? = null,
    /** whether a page is being fetched */
    val loading: Boolean = false,
    /** whether the first page has arrived */
    val loaded: Boolean = false,
    /** why the last fetch failed */
    val error: String? = null,
    /** whether the ids came from the device's cache because Gmail couldn't be reached */
    val fromCache: Boolean = false,
    /** whether a search is still looking through encrypted mail (docs/pgp-contract.md "Offline cache and search") */
    val searchingEncrypted: Boolean = false,
    /** the Gmail query [nextPageToken] belongs to */
    val query: String = "",
    /** whether a later page (a bundle's "Show more") is being fetched, not a refresh of the first */
    val loadingMore: Boolean = false,
    /** why the last later page failed to load, or null when it didn't */
    val moreError: String? = null,
)

/**
 * Everything the signed-in screens show, as plain data: lists, threads, snoozes and the open thread.
 *
 * Derived views of it ([inboxRows], [sections], …) are pure, so the inbox can be rebuilt on any clock.
 * Each is computed once per instance, on first read.
 */
data class MailModel(
    /** the account's Gmail address */
    val email: String,
    /** the clock lists are computed against */
    val now: Long,
    /** the mailbox shown */
    val view: View = View.INBOX,
    /** every thread seen, by id */
    val summaries: Map<String, ThreadSummary> = emptyMap(),
    /** loaded thread ids per view key */
    val lists: Map<String, LoadedList> = emptyMap(),
    /** snoozes from Firestore, by thread id */
    val snoozeDocs: Map<String, SnoozeDoc> = emptyMap(),
    /** snoozed threads a newer message woke */
    val woken: Set<String> = emptySet(),
    /** snoozed threads that got a newer message a Gmail filter kept out of the inbox */
    val skippedInbox: Set<String> = emptySet(),
    /** the account's Gmail labels */
    val labels: List<GmailLabel> = emptyList(),
    /** whether [labels] came from the device or Gmail yet, rather than being empty for want of either */
    val labelsLoaded: Boolean = false,
    /** stored per-label settings, `users/{uid}/labelPrefs/{labelId}`, by label id */
    val labelPrefDocs: Map<String, Map<String, Any?>> = emptyMap(),
    /** threads with the DRAFT label, as Gmail counts them */
    val draftLabelThreads: Long = 0,
    /** the Gmail drafts of scheduled sends, which show only in the Outbox */
    val outboxDrafts: OutboxDrafts = OutboxDrafts(),
    /** whether Gmail was last unreachable */
    val offline: Boolean = false,
    /** whether emails with a todo that this device hasn't got are being read (Todos view shows Loading meanwhile) */
    val readingTodoEmails: Boolean = false,
    /** label changes not yet sent to Gmail */
    val queue: List<LabelChange> = emptyList(),
    /** label and filter changes not yet sent to Gmail */
    val accountQueue: List<AccountChange> = emptyList(),
    /** how the list is used: its rows, focus, the open thread and bundle, selection (see [listStep]) */
    val list: ListState<Row, Todo> = ListState(),
    /** the open thread's messages, once loaded */
    val detail: ThreadDetail? = null,
    /** why the open thread couldn't load */
    val detailError: String? = null,
    /** places open bundles keep until they close */
    val held: HeldPlaces = HeldPlaces(),
    /** subject and start of text of encrypted threads this device decrypted, by thread id */
    val previews: Map<String, DecryptedPreview> = emptyMap(),
    /** whether the Show todos setting is on */
    val todosOn: Boolean = false,
    /** live todos of their own (tombstones left out) as lists place them, the one being edited where it sat; empty while todos are off */
    val todos: List<Todo> = emptyList(),
    /** live todos on emails (tombstones left out); empty while todos are off */
    val emailTodos: List<EmailTodo> = emptyList(),
    /** how many pages of todos the list [TodoPageCount.list] shows */
    val todoPages: TodoPageCount = TodoPageCount(),
    /** whether focus mode is on */
    val focusOn: Boolean = false,
    /** bundle labels whose open list's end is on screen */
    val bundleEndsShown: Set<String> = emptySet(),
    /** the open row's threads, lead first, kept listed and joined while it is open; empty otherwise */
    val heldJoin: List<String> = emptyList(),
) {
    /** The thread open. */
    val openId: String? get() = list.open

    /** Threads in Drafts, those only the Outbox holds left out. */
    val draftCount: Long get() = draftThreadCount(draftLabelThreads, outboxDrafts)

    /** The key of the list [view] loads: in focus mode, the inbox's pinned threads alone. */
    fun listKey(view: View): String = if (view == View.INBOX && focusOn) FOCUS_KEY else view.key

    /** User labels in list order ([sortLabels]): the ones a thread can be given and filters can apply. */
    val userLabels: List<GmailLabel> by lazy { sortLabels(labels.filter { it.type == "user" }) }

    /** The user labels a todo's `#tag` names ([LabelTags]). */
    val labelTags: LabelTags by lazy { LabelTags(labels) }

    /** User labels plus Gmail's categories (in their fixed colors), by name: label views, chips and label settings. */
    val listLabels: List<GmailLabel> by lazy {
        labels.mapNotNull { label ->
            CATEGORY_NAMES[label.id]?.let { name ->
                label.copy(name = name, color = LabelIcons.categoryTones[label.id]?.let { LABEL_SWATCHES[it] })
            } ?: label.takeIf { it.type == "user" }
        }.let(::sortLabels)
    }

    /** [listLabels] by id. */
    val listLabelsById: Map<String, GmailLabel> by lazy { listLabels.associateBy { it.id } }

    /** Each of [listLabels]' settings, defaults filled in, by label id. */
    val labelPrefs: Map<String, LabelPrefs> by lazy {
        listLabels.associate { it.id to labelPrefsFrom(labelPrefDocs[it.id], it.id, it.labelListVisibility) }
    }

    /** Labels whose threads fold into one inbox row. */
    val bundleLabelIds: List<String> by lazy { listLabels.filter { labelPrefs[it.id]?.bundle == true }.map { it.id } }

    /** Whether Gmail has more of a bundle's inbox threads than were fetched. */
    fun bundleHasMore(labelId: String): Boolean = lists[bundleKey(labelId)]?.nextPageToken != null

    /** Whether a bundle's next page is being fetched (not a refresh of its first, which shows nothing). */
    fun bundleLoading(labelId: String): Boolean = lists[bundleKey(labelId)]?.loadingMore == true

    /** Whether a bundle's last next page failed to load. */
    fun bundleError(labelId: String): String? = lists[bundleKey(labelId)]?.moreError

    // Bundle labels Gmail is known to have no more of: their own list loaded, with no next page.
    private val exhaustedBundles: Set<String> by lazy {
        bundleLabelIds.filterTo(mutableSetOf()) { labelId ->
            lists[bundleKey(labelId)]?.let { it.loaded && !it.loading && it.nextPageToken == null } == true
        }
    }

    /** Bundle labels whose next page to fetch now (see [bundlesToTopUp]); none outside the inbox. */
    val bundlesToTopUp: List<String> by lazy {
        if (view != View.INBOX || focusOn) return@lazy emptyList()
        val shown = inboxRows.flatMap { it.thread.labelIds }.groupingBy { it }.eachCount()
        val open = openBundles
        val tail = openBundleTail(list)
        bundlesToTopUp(
            bundleLabelIds.map { labelId ->
                val list = lists[bundleKey(labelId)]
                BundleSupply(
                    labelId = labelId,
                    shown = shown[labelId] ?: 0,
                    more = list?.nextPageToken != null,
                    loading = list?.loading == true,
                    failed = list?.moreError != null,
                    open = labelId in open,
                    endShown = labelId in bundleEndsShown,
                    afterOpen = tail?.takeIf { it.labelId == labelId }?.after,
                )
            },
        )
    }

    /** The labels of the bundles open in the list. */
    val openBundles: Set<String> get() = setOfNotNull(list.bundle?.labelId)

    /** The current view's sections as the list shows them: in the inbox with focus mode on, only what focus mode shows. */
    val shownSections: FocusResult by lazy {
        if (view == View.INBOX && focusOn) focusSections(sections(), list.open, held.pins) else FocusResult(sections(), 0)
    }

    /** [shownSections] as the list's layout, for a [ListEvent.Layout]. */
    val layout: List<LayoutSection<Row, Todo>> by lazy { listLayout(shownSections.sections, ::bundleHasMore, ::bundleLoading) }

    /** The labels the menu lists. */
    val drawerLabels: List<GmailLabel> by lazy { listLabels.filter { labelPrefs[it.id]?.sidebar == true } }

    /** The snooze status of a thread right now. */
    fun statusOf(id: String): SnoozeStatus = snoozeStatus(snoozeDocs[id], now, id in woken)

    private fun row(thread: ThreadSummary, status: SnoozeStatus): Row {
        val doc = snoozeDocs[thread.id]
        return Row(thread, status, doc?.snoozeUntil, inboxSortDate(thread.lastDate, doc, status))
    }

    // The row of [group] (one thread, or a joined row's threads lead first), Sent's dated by
    // [dated]; null when a thread has no summary.
    private fun groupRow(group: List<String>, dated: (ThreadSummary) -> ThreadSummary = { it }): Row? {
        if (group.size == 1) {
            val id = group.single()
            return summaries[id]?.let { row(dated(it), statusOf(id)) }
        }
        val members = group.map { summaries[it] ?: return null }
        val thread = dated(joinSummaries(members))
        val snooze = joinedSnooze(
            members.map { member ->
                val doc = snoozeDocs[member.id]
                JoinSnoozeMember(statusOf(member.id), doc?.snoozeUntil, doc?.snoozeWatermark, member.historyId, member.isInInbox)
            },
        )
        val until = snooze.snoozeUntil
        return Row(thread, snooze.status, until, inboxSortDate(thread.lastDate, until?.let { SnoozeDoc(it, null) }, snooze.status))
    }

    // The ids of the inbox's own lists: Gmail's INBOX, its pinned list and each bundle's, plus
    // threads whose snooze ended (they sort by that end, so may be past the pages loaded).
    private val inboxIds: Set<String> by lazy {
        val bundles = bundleLabelIds.map { labelId ->
            val list = lists[bundleKey(labelId)]
            BundleList(labelId, list?.ids.orEmpty(), list?.nextPageToken != null)
        } + BundleList(PINNED_LABEL, lists[PINNED_KEY]?.ids.orEmpty(), false)
        val ids = linkedSetOf<String>()
        ids += inboxThreadIds(lists[View.INBOX.key]?.ids.orEmpty(), bundles) { summaries[it]?.labelIds }
        if (focusOn) ids += lists[FOCUS_KEY]?.ids.orEmpty()
        for ((id, doc) in snoozeDocs) {
            if (recentlyLapsed(doc.snoozeUntil, now)) ids += id
        }
        ids
    }

    // The inbox's joined rows (see [joinedGroups]), its snoozed threads among them, as they stay in
    // Gmail's INBOX; Snoozed shares them, so a reply split off a snoozed thread wakes it in both.
    private val inboxGroups: Map<String, List<String>> by lazy {
        joined((inboxIds + snoozeDocs.keys).filter { summaries[it]?.isInInbox == true })
    }

    // The open row's threads, lead first: [heldJoin], else the open thread alone.
    private val openThreads: List<String> by lazy { heldJoin.ifEmpty { listOfNotNull(openId) }.filter { it in summaries } }

    // [joinedGroups] of [ids] and the open row's threads, with [heldJoin] kept as one row under its
    // lead, whatever else joins it since.
    private fun joined(ids: Collection<String>): Map<String, List<String>> {
        val held = openThreads
        val groups = joinedGroups(if (held.isEmpty()) ids else (ids + held).distinct(), summaries)
        if (held.size < 2) return groups
        val more = held.flatMap { groups[it].orEmpty() }.filterNot { it in held }.distinct()
        val group = held + more.sortedBy { summaries[it]?.firstDate ?: 0L }
        val touched = groups.values.filter { members -> members.any { it in group } }.flatten().toSet()
        return groups.filterKeys { it !in touched } + group.associateWith { group }
    }

    /**
     * Rows of the inbox: Gmail's INBOX, its pinned list and each bundle's own list, plus threads whose
     * snooze ended (they sort by that end, so may be past the pages loaded), minus those still snoozed.
     * Snoozed threads stay in Gmail's INBOX; one archived elsewhere after its snooze ended stays out.
     * Threads joined into one row show once, under their lead's id.
     */
    val inboxRows: List<Row> by lazy {
        inboxIds.mapTo(linkedSetOf()) { id -> inboxGroups[id]?.first() ?: id }.mapNotNull { lead ->
            val row = groupRow(inboxGroups[lead] ?: listOf(lead)) ?: return@mapNotNull null
            when {
                row.status == SnoozeStatus.SNOOZED -> null
                !row.thread.isInInbox || inOutboxOnly(row.thread) -> null
                else -> row
            }
        }
    }

    /** Rows of Snoozed: every thread still snoozed, joined ones as one row. */
    val snoozedRows: List<Row> by lazy {
        snoozeDocs.keys.mapTo(linkedSetOf()) { id -> inboxGroups[id]?.first() ?: id }.mapNotNull { lead ->
            groupRow(inboxGroups[lead] ?: listOf(lead))?.takeIf { it.status == SnoozeStatus.SNOOZED && !inOutboxOnly(it.thread) }
        }
    }

    // Whether the current view's rows come from the inbox's lists and snoozes, joined as they are.
    private val joinsLikeInbox: Boolean by lazy {
        val current = view
        current == View.INBOX || current == View.Fixed(Mailbox.SNOOZED) ||
            (current is View.Search && snoozedDeviceList(current))
    }

    /**
     * Each thread the current list joins with others into one row (see [joinThreads]), by id: the
     * row's threads, its lead (whose id is the row's) first. Threads with a row of their own are left out.
     */
    val joinedThreads: Map<String, List<String>> by lazy {
        when {
            view == View.Todos -> emptyMap()
            joinsLikeInbox -> inboxGroups
            else -> listedGroups
        }
    }

    /** The Gmail threads of the row [id] names (or that [id] is joined into): one, or each of a joined row's. */
    fun threadsOf(id: String): List<String> = joinedThreads[id] ?: listOf(id)

    /** The Gmail threads of these rows, each once (see [threadsOf]). */
    fun threadsOf(ids: Collection<String>): List<String> = ids.flatMapTo(linkedSetOf(), ::threadsOf).toList()

    /** The Gmail threads of the inbox row [id] shows in (for a notification, whatever list shows): one, or each of a joined row's. */
    fun inboxThreadsOf(id: String): List<String> = inboxGroups[id] ?: listOf(id)

    /** Snoozed rows with a thread that got a newer message a Gmail filter kept out of the inbox, by row key. */
    val rowSkippedInbox: Set<String> by lazy { skippedInbox + skippedInbox.mapNotNull { joinedThreads[it]?.first() } }

    /** The key of the row a thread shows in: its own, or its joined row's lead's. */
    fun rowKeyOf(id: String): String = joinedThreads[id]?.first() ?: id

    /** What the row [id] shows: a joined row's summary ([joinSummaries]), or the thread's own. */
    fun rowSummary(id: String): ThreadSummary? = joinedSummaries[id] ?: summaries[id]

    // Each joined row's summary, by its lead's id.
    private val joinedSummaries: Map<String, ThreadSummary> by lazy {
        joinedThreads.values.filter { it.size > 1 }.distinct().mapNotNull { group ->
            group.mapNotNull { summaries[it] }.takeIf { it.size == group.size }?.let { group.first() to joinSummaries(it) }
        }.toMap()
    }

    /** The snooze status of the row [id]: a joined row's from all its threads ([joinedSnooze]), else the thread's own. */
    fun rowStatus(id: String): SnoozeStatus {
        val group = joinedThreads[id]?.takeIf { it.first() == id } ?: return statusOf(id)
        return groupRow(group)?.status ?: statusOf(id)
    }

    /**
     * [previews] as rows show them: a joined row's under its lead's id, with the lead's decrypted
     * subject (else any of its threads'), and the start of the newest one decrypted.
     */
    val rowPreviews: Map<String, DecryptedPreview> by lazy {
        val joined = joinedThreads.values.filter { it.size > 1 }.distinct().mapNotNull { group ->
            val shown = group.mapNotNull { previews[it] }
            if (shown.isEmpty()) return@mapNotNull null
            val newest = shown.maxBy { it.date }
            val subject = shown.map { it.subject }.firstOrNull { it.isNotEmpty() }.orEmpty()
            group.first() to newest.copy(subject = subject)
        }
        if (joined.isEmpty()) previews else previews + joined
    }

    /** Per Gmail thread id, its email's todo. */
    val threadTodos: Map<String, EmailTodo> by lazy { emailTodosByThread(emailTodos) }

    /** The todo the row [id] shows: that of the first of its threads ([threadsOf], lead first) that has one. */
    fun rowTodo(id: String): EmailTodo? = rowEmailTodo(threadTodos, threadsOf(id))

    /** Active todos of their own. */
    val activeTodos: List<Todo> by lazy { todos.filter { it.matches(TodoFilter.ACTIVE, now) } }

    /** Todos of their own still snoozed. */
    val snoozedTodos: List<Todo> by lazy { todos.filter { it.matches(TodoFilter.SNOOZED, now) } }

    // The emails with a todo this device has, as rows, for the Todos view; the open one held in place.
    private val todosViewEmails: List<TodosViewEmail<Row>> by lazy {
        if (view != View.Todos) {
            emptyList()
        } else {
            listedRows.mapNotNull { row ->
                rowTodo(row.thread.id)?.let { todo ->
                    TodosViewEmail(
                        email = row,
                        todo = todo,
                        inbox = row.thread.isInInbox,
                        snoozed = row.status == SnoozeStatus.SNOOZED,
                        pinned = row.thread.isPinned,
                        sortDate = row.sortDate,
                        held = held.threads[row.thread.id]?.let { TodosViewPlace(it.date, it.pinned) },
                    )
                }
            }
        }
    }

    /** The Todos view's rows under their headings: active todos and the todos of active emails. */
    val todosViewBuckets: List<TodosViewBucket<Row>> by lazy { todosView(todos, todosViewEmails, now) }

    /** Where the email row [id] sits in the Todos view, to hold while it is open; null when it isn't listed there. */
    fun todosViewPlaceOf(id: String): ThreadPlace? =
        todosViewEmails.firstOrNull { it.email.thread.id == id }?.let(::todosViewPlace)?.let { ThreadPlace(it.date, it.pinned, single = true) }

    /** The todos a search finds, newest first (an `is:snoozed` one soonest to wake first); none when it asks for mail only. */
    private val searchTodos: List<Todo> by lazy {
        val current = view
        if (current !is View.Search || !todosOn) return@lazy emptyList()
        val found = searchTodos(todos, current.query, now, labels = labelTags)
        if (searchPlan(current.query).snoozedOnly) found else found.newestFirst()
    }

    /** How many todos the current search finds, shown or not: its own todos and its listed emails' todos. */
    val searchTodoCount: Int get() = searchTodos.size + if (view is View.Search) listedRows.count { rowTodo(it.thread.id) != null } else 0

    /** The list todo pages count in: the view. */
    val todoListKey: String get() = view.key

    /** The most todos the current list (a search, the Todos view) shows until more are asked for. */
    val todoLimit: Int get() = pageLimit(if (todoPages.list == todoListKey) todoPages.pages else 1)

    // The search's todos that may show among the threads loaded so far, before the todo limit: while
    // older threads are still to load, only those no older than the oldest loaded thread, so each
    // page of threads brings its own rather than loading in above them.
    private val searchLoaded: List<Todo> by lazy {
        val current = view as? View.Search ?: return@lazy emptyList()
        val plan = searchPlan(current.query)
        if (plan.todosOnly || plan.snoozedOnly) {
            searchTodos
        } else {
            todosLoaded(searchTodos, listedRows.minOfOrNull { it.sortDate } ?: Long.MAX_VALUE, moreThreads(current))
        }
    }

    /** How many todos the current list leaves out until more are asked for. */
    val todosHidden: Int by lazy {
        when (view) {
            View.Todos -> maxOf(0, todosViewCount(todosViewBuckets) - todoLimit)
            is View.Search -> maxOf(0, searchLoaded.size - todoLimit)
            else -> 0
        }
    }

    // The current mailbox's threads its own filter keeps (not the inbox's, Snoozed's or the Todos
    // view's), and the emails it shows for their todo's text, though Gmail doesn't give them the
    // label or match them to the search; those are held to the filter less its user labels.
    private val listedIds: List<String> by lazy {
        val current = view
        val filter = viewFilter
        val listed = lists[current.key]?.ids.orEmpty()
        val tagged = LabelTags.taggedThreadIds(todoThreadIds, listed)
        val relaxed = filter?.let(labelTags::taggedFilter)
        listed.filter { id ->
            val thread = summaries[id]?.takeUnless(::inOutboxOnly)
            thread != null && (filter == null || keepsThread(filter, filtered(thread)))
        } + tagged.filter { id ->
            val thread = summaries[id]?.takeUnless(::inOutboxOnly)
            thread != null && (relaxed == null || keepsThread(relaxed, filtered(thread)))
        }
    }

    // The emails the current list shows for their todo's text: a label's, by the todo's tags, or a
    // search's ([searchEmailTodos]), whose own state the list's filter then holds to its terms.
    private val todoThreadIds: List<String> by lazy {
        val current = view
        when {
            !todosOn -> emptyList()
            current is View.Label -> labelTags.emailTodosOf(emailTodos, current.labelId).map { it.threadId }
            current == View.Todos -> emailTodos.filterNot { it.deleted }.map { it.threadId }
            current is View.Search && !snoozedDeviceList(current) -> searchEmailTodos(emailTodos, current.query, labelTags)
            else -> emptyList()
        }
    }

    // Whether a search lists Snoozed's own rows, which hold every snoozed email: `is:snoozed` with no Gmail query.
    private fun snoozedDeviceList(search: View.Search): Boolean {
        val plan = searchPlan(search.query)
        return plan.snoozedOnly && !plan.todosOnly && listOptions(search) == null
    }

    /** Emails the current list shows for their todo's text that this device hasn't read yet. */
    val missingTaggedThreads: List<String> by lazy {
        LabelTags.taggedThreadIds(todoThreadIds, emptyList()).filter { it !in summaries }
    }

    // Whether a thread shows only in the Outbox: a scheduled send's draft and nothing else (see [onlyInOutbox]).
    private fun inOutboxOnly(thread: ThreadSummary): Boolean = onlyInOutbox(thread.id, thread.messageCount, outboxDrafts)

    // [listedIds]' joined rows (see [joinedGroups]).
    private val listedGroups: Map<String, List<String>> by lazy { joined(listedIds) }

    // [listedIds]' rows, threads joined into one row once, under their lead's id.
    private val listedRows: List<Row> by lazy {
        listedIds.mapTo(linkedSetOf()) { id -> listedGroups[id]?.first() ?: id }.mapNotNull { lead -> groupRow(listedGroups[lead] ?: listOf(lead), listDated) }
    }

    // How the current list dates a thread: Sent by the user's latest message in it.
    private val listDated: (ThreadSummary) -> ThreadSummary
        get() = if (view == View.Fixed(Mailbox.SENT)) ThreadSummary::sentDated else { thread -> thread }

    private val systemSections: Pair<ZoneId, List<SectionModel>> by lazy {
        val zone = ZoneId.systemDefault()
        zone to computeSections(zone)
    }

    /** The current view's sections. */
    fun sections(zone: ZoneId = ZoneId.systemDefault()): List<SectionModel> {
        val (cachedZone, cached) = systemSections
        return if (zone == cachedZone) cached else computeSections(zone)
    }

    // [rows] with the open row, which stays until it closes though it left the list (read, archived
    // elsewhere, a label taken off) or was never in it (opened from a notification).
    private fun withOpen(rows: List<Row>, dated: (ThreadSummary) -> ThreadSummary = { it }): List<Row> {
        val lead = openId?.let(::rowKeyOf) ?: return rows
        if (rows.any { it.thread.id == lead }) return rows
        return groupRow(threadsOf(lead), dated)?.let { rows + it } ?: rows
    }

    private fun computeSections(zone: ZoneId): List<SectionModel> {
        val current = view
        if (current == View.INBOX) {
            return inboxSections(
                withOpen(inboxRows),
                now,
                bundleLabelIds,
                zone,
                held,
                activeTodos,
                exhaustedBundles,
                todoLabels = { todo -> labelTags.labelIds(todo.text) },
                rowTodoLabels = { row -> rowTodo(row.thread.id)?.let { labelTags.labelIds(it.text) }.orEmpty() },
            )
        }
        if (current == View.Fixed(Mailbox.SNOOZED)) return snoozedSections(withOpen(snoozedRows), now, zone, snoozedTodos)
        if (current == View.Todos) {
            return limitTodosView(todosViewBuckets, todoLimit).map { bucket ->
                SectionModel(
                    "todos:${bucket.label}",
                    bucket.label,
                    bucket.entries.map { entry ->
                        when (entry) {
                            is TodosViewEntry.Own -> TodoEntry(entry.todo)
                            is TodosViewEntry.OnEmail -> ListEntry.Single(entry.email)
                        }
                    },
                    canDoneAll = false,
                )
            }
        }
        val rows = withOpen(listedRows, listDated)
        if (current == View.Fixed(Mailbox.DONE)) {
            // While older threads are still to load, only todos no older than the oldest loaded thread show.
            val oldest = listedRows.minOfOrNull { it.sortDate } ?: Long.MAX_VALUE
            return plainSections(rows, now, zone, held, todosLoaded(todos.filter { it.state == TodoState.DONE }, oldest, moreThreads(current), zone))
        }
        val plan = (current as? View.Search)?.let { searchPlan(it.query) }
        val found = searchLoaded.take(todoLimit)
        return when {
            current is View.Label -> {
                // While older threads are still to load, only todos no older than the oldest loaded thread show.
                val oldest = listedRows.minOfOrNull { it.sortDate } ?: Long.MAX_VALUE
                plainSections(rows, now, zone, held, todosLoaded(labelTags.todosOf(todos, current.labelId), oldest, moreThreads(current), zone))
            }
            plan == null -> plainSections(rows, now, zone, held)
            plan.todosOnly && plan.snoozedOnly -> snoozedSections(rows, now, zone, found)
            plan.todosOnly -> plainSections(rows, now, zone, held, found)
            plan.snoozedOnly -> snoozedSections(if (listOptions(current) == null) withOpen(snoozedRows) else rows, now, zone, found)
            else -> plainSections(rows, now, zone, held, found)
        }
    }

    // Whether [view] may have threads still to load: its first page, or a next one.
    private fun moreThreads(view: View): Boolean {
        val list = lists[view.key]
        return list == null || !list.loaded || list.nextPageToken != null
    }

    /** The current view's filter (see [viewFilter]); null for the Todos view. */
    val viewFilter: ViewFilter? by lazy { filterOf(view) }

    /** [view]'s filter, reading a search's `label:` names against the user labels ([searchLabelName]); null for the Todos view. */
    fun filterOf(view: View): ViewFilter? = view.filtered()?.let { filtered ->
        viewFilter(filtered) { name ->
            val wanted = searchLabelName(name)
            userLabels.firstOrNull { searchLabelName(it.name) == wanted }?.id
        }
    }

    /** A thread as a view's filter sees it now. */
    fun filtered(thread: ThreadSummary, status: SnoozeStatus = statusOf(thread.id)): FilteredThread =
        FilteredThread(thread.labelIds, status == SnoozeStatus.SNOOZED)

    /** The current view's list state. */
    val currentList: LoadedList by lazy {
        if (listOptions(view) == null) LoadedList(loaded = true) else lists[listKey(view)] ?: LoadedList()
    }

    /** Threads and todos still snoozed, for the menu. */
    val snoozedCount: Int get() = snoozedRows.size + snoozedTodos.size

    /** Inbox threads per label, for the menu. */
    val labelCounts: Map<String, Int> by lazy {
        inboxRows.flatMap { it.thread.labelIds }.groupingBy { it }.eachCount()
    }

    /** The soonest snooze of a thread or todo still ahead, when the clock next needs to move. */
    val nextWake: Long? by lazy {
        val todoWakes = todos.filter { it.state != TodoState.DONE }.mapNotNull { todo -> todo.snoozeUntil?.let { isoToEpoch(it) } }
        (snoozeDocs.values.mapNotNull { it.snoozeUntil } + todoWakes).filter { it > now }.minOrNull()
    }
}
