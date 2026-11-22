package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.pgp.ENCRYPTED_MAIL_QUERY

// Which threads a device keeps with their bodies for reading offline, and which of its other saved
// copies it drops first. A port of shared/src/offline-set.ts; both run
// shared/testdata/offline-set-fixtures.json.

/** Most threads saved with bodies but encrypted mail; those in the inbox, snoozed or with a draft are kept past it. */
const val OFFLINE_THREAD_CAP = 3_000

/** Most encrypted threads saved past the inbox, apart from [OFFLINE_THREAD_CAP]. */
const val ENCRYPTED_THREAD_CAP = 10_000

/** Gmail's search for every thread with a draft, all of which are saved. */
const val DRAFT_QUERY = "in:draft"

/** Days of mail saved besides the inbox, unless the device's setting says otherwise. */
const val DEFAULT_OFFLINE_DAYS = 30

/** The most days of mail the setting allows. */
const val MAX_OFFLINE_DAYS = 30

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val INBOX = "INBOX"
private const val DRAFT = "DRAFT"
private const val SPAM = "SPAM"
private const val TRASH = "TRASH"

/** A thread as the offline set weighs it. */
data class OfflineThread(
    /** Gmail thread id */
    val id: String,
    /** every label on any of its messages */
    val labelIds: List<String>,
    /** its newest message's date, epoch milliseconds */
    val newestAt: Long,
    /** when it was last opened on this device, epoch milliseconds, or null if never */
    val openedAt: Long?,
    /** whether any of its messages is encrypted */
    val encrypted: Boolean,
)

/** Why a thread is saved, most important first; a pinned thread out of the inbox is like any other. */
enum class OfflineTier {
    /** in the inbox (pinned ones too) or snoozed */
    INBOX,

    /** holding a draft, at any age */
    DRAFT,

    /** encrypted, at any age (Gmail can't search it) */
    ENCRYPTED,

    /** with a message in the last days */
    RECENT,

    /** opened in the last days */
    OPENED,
}

/** A day count clamped to the setting's range, whole days; anything unreadable is the default. */
fun offlineDays(days: Any?): Int {
    val number = (days as? Number)?.toDouble()
    return if (number == null || !number.isFinite()) {
        DEFAULT_OFFLINE_DAYS
    } else {
        kotlin.math.floor(number).coerceIn(0.0, MAX_OFFLINE_DAYS.toDouble()).toInt()
    }
}

/** Why [thread] is saved, or null when it isn't. [snoozed] says whether a snooze is still ahead. */
fun offlineTier(thread: OfflineThread, snoozed: Boolean, days: Int, now: Long): OfflineTier? {
    val labels = thread.labelIds
    val since = now - days * DAY_MS
    val hidden = SPAM in labels || TRASH in labels
    return when {
        snoozed || INBOX in labels -> OfflineTier.INBOX
        DRAFT in labels -> OfflineTier.DRAFT
        thread.encrypted && !hidden -> OfflineTier.ENCRYPTED
        days > 0 && thread.newestAt >= since && !hidden -> OfflineTier.RECENT
        days > 0 && thread.openedAt != null && thread.openedAt >= since -> OfflineTier.OPENED
        else -> null
    }
}

/** Which threads stay saved, in the order they are worth fetching, and which go. */
data class OfflinePlan(
    /**
     * kept: the inbox tier newest first, then threads with a draft, then encrypted, then recent, newest first, then
     * opened most recent first
     */
    val keep: List<String>,
    /** dropped: in no tier, or past the cap */
    val drop: List<String>,
)

/**
 * Split threads into those kept and those dropped. Every inbox-tier thread and every thread with a
 * draft is kept. Encrypted threads fill up to [encryptedCap], newest first; one past it is weighed
 * as recent or opened mail when it is either. Recent and opened threads fill what the inbox and
 * drafts leave of [cap], so past it the least recently opened go first, then the oldest recent ones.
 */
fun offlinePlan(
    threads: List<OfflineThread>,
    snoozed: Set<String>,
    days: Int,
    now: Long,
    cap: Int = OFFLINE_THREAD_CAP,
    encryptedCap: Int = ENCRYPTED_THREAD_CAP,
): OfflinePlan {
    val byNewest = compareByDescending<OfflineThread> { it.newestAt }.thenBy { it.id }
    val placed = threads.map { it to offlineTier(it, it.id in snoozed, days, now) }
    val encrypted = placed.filter { it.second == OfflineTier.ENCRYPTED }.map { it.first }.sortedWith(byNewest)
    val keptEncrypted = encrypted.take(encryptedCap.coerceAtLeast(0))
    val fallen = encrypted.drop(keptEncrypted.size).map { thread ->
        val plain = thread.copy(encrypted = false)
        plain to offlineTier(plain, false, days, now)
    }
    val tiers = (placed.filter { it.second != OfflineTier.ENCRYPTED } + fallen).groupBy({ it.second }, { it.first })
    val inbox = tiers[OfflineTier.INBOX].orEmpty().sortedWith(byNewest)
    val drafts = tiers[OfflineTier.DRAFT].orEmpty().sortedWith(byNewest)
    val recent = tiers[OfflineTier.RECENT].orEmpty().sortedWith(byNewest)
    val opened = tiers[OfflineTier.OPENED].orEmpty().sortedWith(compareByDescending<OfflineThread> { it.openedAt ?: 0 }.thenBy { it.id })
    val rest = (recent + opened).map { it.id }
    val room = (cap - inbox.size - drafts.size).coerceAtLeast(0)
    return OfflinePlan(
        keep = inbox.map { it.id } + drafts.map { it.id } + keptEncrypted.map { it.id } + rest.take(room),
        drop = tiers[null].orEmpty().map { it.id } + rest.drop(room),
    )
}

/** One Gmail search a full rebuild lists. */
data class OfflineQuery(
    /** the search */
    val query: String,
    /** most threads worth listing from it, or null for all */
    val cap: Int?,
)

/**
 * The Gmail searches a full rebuild lists, in the order their threads are fetched: the inbox, then
 * every thread with a draft, then (with [days] above 0) every thread with a message in the last [days] days, then encrypted mail.
 * `threads.list` leaves out spam and trash by itself.
 */
fun offlineQueries(days: Int): List<OfflineQuery> =
    listOfNotNull(
        OfflineQuery("in:inbox", null),
        OfflineQuery(DRAFT_QUERY, null),
        OfflineQuery("newer_than:${days}d", OFFLINE_THREAD_CAP).takeIf { days > 0 },
        OfflineQuery(ENCRYPTED_MAIL_QUERY, ENCRYPTED_THREAD_CAP),
    )

/** The keys to drop so at most [cap] remain: the least recently used. */
fun evictionKeys(usage: List<Pair<String, Long>>, cap: Int): List<String> =
    if (usage.size <= cap) emptyList() else usage.sortedBy { it.second }.take(usage.size - cap).map { it.first }

/**
 * The keys to drop from a saved store: those [expired] says are too old, then the least recently
 * used past [cap]. Keys without a recorded use count as never used; keys [kept] names are never
 * dropped and don't count toward the cap.
 */
fun pruneKeys(
    keys: List<String>,
    usage: Map<String, Long>,
    cap: Int,
    expired: (key: String, usedAt: Long) -> Boolean = { _, _ -> false },
    kept: Set<String> = emptySet(),
): List<String> {
    val dropped = mutableListOf<String>()
    val counted = mutableListOf<Pair<String, Long>>()
    for (key in keys) {
        if (key in kept) continue
        val usedAt = usage[key] ?: 0
        if (expired(key, usedAt)) dropped += key else counted += key to usedAt
    }
    return dropped + evictionKeys(counted, cap)
}
