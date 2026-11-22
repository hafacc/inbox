package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The same Gmail calls as shared/src/bundle-fetch.spec.ts, against the same fake mailbox, so both
// apps keep to one budget: the inbox's pages of 25 load until the screen holds a dozen rows, each
// bundle label they show (or the device remembers) fetches its own first page before the next
// inbox page, inbox pages leave out bundles whose list came back, and once they leave any out the
// inbox's pinned threads come from their own list.

private const val INBOX_PAGE = 25
private const val SCREEN_ROWS = 12
private const val MIN_GROUP = 3
private const val BULK_PAGE = 500
private const val THREADS = 300
private const val SLOTS = 20
private const val GITHUB_SLOT = 2

// A GitHub thread pinned far below the bundle's first ten.
private const val DEEP_PINNED = "t282"

private val LABELS = listOf(QueryLabel("CATEGORY_PROMOTIONS", "Promotions"), QueryLabel("Label_gh", "GitHub"))

private data class Thread(val id: String, val labels: List<String>)

// 300 inbox threads, newest first: most promotions, some GitHub (or none), a few plain.
private fun mailbox(github: Boolean = true): List<Thread> = (0 until THREADS).map { index ->
    val slot = index % SLOTS
    val labels = when {
        slot < GITHUB_SLOT -> listOf("INBOX")
        slot == GITHUB_SLOT && github -> listOf("INBOX", "Label_gh")
        else -> listOf("INBOX", "CATEGORY_PROMOTIONS")
    }
    val id = "t$index"
    Thread(id, if (id == DEEP_PINNED && github) labels + PINNED_LABEL else labels)
}

private data class Page(val ids: List<String>, val next: String?)

private class FakeGmail(private val threads: List<Thread>) {
    var lists = 0
    var reads = 0

    // Labels whose own list Gmail fails.
    val failing = mutableSetOf<String>()

    fun list(labelIds: List<String>, q: String, max: Int, token: String?): Page {
        lists++
        if (labelIds.any { it in failing }) error("503")
        val left = q.split(" ").filter { it.isNotEmpty() }
        val matching = threads.filter { thread ->
            labelIds.all { it in thread.labels } &&
                left.none { term ->
                    thread.labels.any { label -> inboxExclusion(LABELS.filter { it.id == label }).split(" ").contains(term) }
                }
        }
        var start = 0
        if (token != null) {
            val (query, offset) = token.split("|")
            assertEquals(q, query)
            start = offset.toInt()
        }
        val end = start + max
        return Page(matching.drop(start).take(max).map { it.id }, if (end < matching.size) "$q|$end" else null)
    }

    fun labelsOf(id: String): List<String> = threads.firstOrNull { it.id == id }?.labels.orEmpty()
}

private data class BundleState(val ids: List<String>, val next: String?, val paged: Boolean)

private class Client(val gmail: FakeGmail, cached: Iterable<String> = emptyList(), remembered: Iterable<String> = emptyList()) {
    val summaries = cached.toMutableSet()
    var inbox = listOf<String>()
    var inboxNext: String? = null
    var tokenQuery = ""
    var pinned = listOf<String>()
    var pinnedFresh = false

    // Bundle lists asked for this session; only those that came back leave the inbox.
    val requested = mutableSetOf<String>()
    val bundles = linkedMapOf<String, BundleState>()
    val stale = mutableSetOf<String>()
    private val remembered = remembered.toSet()

    // What the device keeps for the next session.
    fun remember(): List<String> = bundlesToRemember(bundles.map { (labelId, bundle) -> BundleList(labelId, bundle.ids, bundle.next != null) })

    private fun read(ids: List<String>) {
        for (id in ids) if (summaries.add(id)) gmail.reads++
    }

    private fun fetchDueBundles() {
        val due = bundlesToFetch(LABELS.map { it.id }, inbox.map(gmail::labelsOf), requested, remembered)
        for (labelId in due) {
            requested += labelId
            loadBundle(labelId)
        }
    }

    fun exclusion(): String = inboxExclusion(LABELS.filter { it.id in bundles }, LABELS)

    // A bundle's first page (a paged one keeps its later pages), or with [more] its next page.
    fun loadBundle(labelId: String, more: Boolean = false): Boolean {
        val current = bundles[labelId]
        val page = runCatching {
            gmail.list(
                listOf("INBOX", labelId),
                "",
                if (more) BUNDLE_PAGE_SIZE else BUNDLE_FIRST_PAGE,
                if (more) current?.next else null,
            )
        }.getOrElse {
            stale += labelId
            return false
        }
        stale -= labelId
        read(page.ids)
        val merged = if (!more && current?.paged == true && page.next != null) mergeFirstPage(current.ids, page.ids) else null
        bundles[labelId] = BundleState(
            ids = if (more) current?.ids.orEmpty() + page.ids else merged ?: page.ids,
            next = if (merged != null) current?.next else page.next,
            paged = more || merged != null,
        )
        return true
    }

    fun loadInbox(more: Boolean = false) {
        fetchDueBundles()
        val query = exclusion()
        val request = if (more) inboxNextPage(query, inboxNext, tokenQuery) else InboxPageRequest(query, null)
        if (!more) pinnedFresh = false
        if (query.isNotEmpty() && !pinnedFresh) {
            pinned = gmail.list(listOf("INBOX", PINNED_LABEL), "", PINNED_PAGE, null).ids
            read(pinned)
            pinnedFresh = true
        }
        val page = gmail.list(listOf("INBOX"), request.q, INBOX_PAGE, request.pageToken)
        read(page.ids)
        inbox = if (more) (inbox + page.ids).distinct() else page.ids
        inboxNext = page.next
        tokenQuery = request.q
        fetchDueBundles()
    }

    // A refresh after history touched bundles: their first page again, failed ones retried.
    fun refresh(touched: List<String>) {
        stale += touched
        loadInbox()
        for (labelId in stale.toList()) loadBundle(labelId)
    }

    fun ids(): List<String> = inboxThreadIds(
        inbox,
        bundles.map { (labelId, bundle) -> BundleList(labelId, bundle.ids, bundle.next != null) } +
            BundleList(PINNED_LABEL, pinned, false),
        gmail::labelsOf,
    )

    fun rows(): Int {
        val perLabel = mutableMapOf<String, Int>()
        var singles = 0
        for (id in ids()) {
            val labels = gmail.labelsOf(id)
            val label = labels.firstOrNull { entry -> LABELS.any { it.id == entry } }
            if (label == null || PINNED_LABEL in labels) singles++ else perLabel[label] = (perLabel[label] ?: 0) + 1
        }
        return singles + perLabel.values.sumOf { if (it >= MIN_GROUP) 1 else it }
    }

    // The list's end stays in view, so pages load until the screen is full.
    fun fillScreen() {
        loadInbox()
        while (rows() < SCREEN_ROWS && inboxNext != null) loadInbox(more = true)
    }

    // A bundle opened: its next page, once.
    fun open(labelId: String) {
        val bundle = bundles[labelId] ?: return
        if (!bundle.paged && bundle.next != null) loadBundle(labelId, more = true)
    }

    // Done all on a bundle Gmail has more of: every unpinned inbox thread with its label, by id.
    fun doneAllIds(labelId: String): List<String> {
        fun every(labelIds: List<String>): List<String> {
            val ids = mutableListOf<String>()
            var token: String? = null
            do {
                val page = gmail.list(labelIds, "", BULK_PAGE, token)
                ids += page.ids
                token = page.next
            } while (token != null)
            return ids
        }
        val pinned = every(listOf("INBOX", labelId, PINNED_LABEL)).toSet()
        return every(listOf("INBOX", labelId)).filterNot { it in pinned }
    }
}

private fun held(client: Client, labelId: String): Int =
    (client.inbox.filter { labelId in client.gmail.labelsOf(it) } + client.bundles[labelId]?.ids.orEmpty()).toSet().size

class BundleFetchTest {
    @Test
    fun `a cold load lists the inbox twice, each bundle's first ten and the pinned threads`() {
        val gmail = FakeGmail(mailbox())
        val client = Client(gmail)
        client.fillScreen()
        // Inbox page one, a list per bundle, the pinned list, then the inbox again without the bundles.
        assertEquals(5, gmail.lists)
        assertEquals(55, gmail.reads)
        assertEquals(19, held(client, "CATEGORY_PROMOTIONS"))
        assertEquals(10, held(client, "Label_gh"))
        assertTrue(DEEP_PINNED in client.ids())
    }

    @Test
    fun `a warm load leaves remembered bundles out of the first inbox page and reads nothing it has`() {
        val threads = mailbox()
        val first = Client(FakeGmail(threads))
        first.fillScreen()
        assertEquals(listOf("CATEGORY_PROMOTIONS", "Label_gh"), first.remember())
        val gmail = FakeGmail(threads)
        val client = Client(gmail, threads.map { it.id }, first.remember())
        client.fillScreen()
        // A list per remembered bundle, the pinned list, then the inbox once, already without them.
        assertEquals(4, gmail.lists)
        assertEquals(0, gmail.reads)
        assertEquals(10, held(client, "CATEGORY_PROMOTIONS"))
        assertEquals(10, held(client, "Label_gh"))
        assertTrue(DEEP_PINNED in client.ids())
    }

    @Test
    fun `a remembered label with no inbox mail left costs one empty list, then is forgotten`() {
        val threads = mailbox(github = false)
        val gmail = FakeGmail(threads)
        val client = Client(gmail, threads.map { it.id }, listOf("CATEGORY_PROMOTIONS", "Label_gh"))
        client.fillScreen()
        assertEquals(4, gmail.lists)
        assertEquals(emptyList<String>(), client.bundles["Label_gh"]?.ids)
        assertEquals(listOf("CATEGORY_PROMOTIONS"), client.remember())
    }

    @Test
    fun `opening a bundle lists its next 50 once, Show more 50 more`() {
        val gmail = FakeGmail(mailbox())
        val client = Client(gmail)
        client.fillScreen()
        val lists = gmail.lists
        val reads = gmail.reads
        client.open("CATEGORY_PROMOTIONS")
        client.open("CATEGORY_PROMOTIONS")
        assertEquals(1, gmail.lists - lists)
        assertTrue(gmail.reads - reads <= BUNDLE_PAGE_SIZE)
        assertEquals(60, client.bundles["CATEGORY_PROMOTIONS"]?.ids?.size)
        client.loadBundle("CATEGORY_PROMOTIONS", more = true)
        assertEquals(2, gmail.lists - lists)
        assertEquals(110, client.bundles["CATEGORY_PROMOTIONS"]?.ids?.size)
    }

    @Test
    fun `a failed bundle list leaves its threads in the inbox until a refresh fetches it`() {
        val gmail = FakeGmail(mailbox())
        gmail.failing += "Label_gh"
        val client = Client(gmail)
        client.fillScreen()
        assertEquals("-category:promotions", client.exclusion())
        assertEquals(setOf("Label_gh"), client.stale)
        assertTrue(held(client, "Label_gh") > 0)
        gmail.failing.clear()
        val lists = gmail.lists
        client.refresh(emptyList())
        // The inbox's first page, the pinned list, and the bundle that failed.
        assertEquals(3, gmail.lists - lists)
        assertTrue(client.stale.isEmpty())
        assertEquals("-category:promotions -label:github", client.exclusion())
    }

    @Test
    fun `a refresh of an opened bundle lists its first ten and keeps the pages past them`() {
        val gmail = FakeGmail(mailbox())
        val client = Client(gmail)
        client.fillScreen()
        client.open("CATEGORY_PROMOTIONS")
        val lists = gmail.lists
        val reads = gmail.reads
        client.refresh(listOf("CATEGORY_PROMOTIONS"))
        assertEquals(3, gmail.lists - lists)
        assertEquals(0, gmail.reads - reads)
        assertEquals(60, client.bundles["CATEGORY_PROMOTIONS"]?.ids?.size)
    }

    @Test
    fun `Done all on a bundle Gmail has more of lists its ids without reading a thread`() {
        val gmail = FakeGmail(mailbox())
        val client = Client(gmail)
        client.fillScreen()
        val lists = gmail.lists
        val reads = gmail.reads
        val ids = client.doneAllIds("Label_gh")
        assertEquals(2, gmail.lists - lists)
        assertEquals(0, gmail.reads - reads)
        assertEquals(14, ids.size)
        assertFalse(DEEP_PINNED in ids)
    }
}
