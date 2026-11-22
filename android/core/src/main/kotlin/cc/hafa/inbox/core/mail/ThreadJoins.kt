// Gmail files an encrypted reply in a thread of its own, since its outer Subject is the hidden one
// ("...") rather than "Re: …". Its In-Reply-To and References stay readable, so the list joins it
// back to the thread it answers and shows them as one row. Gmail itself stays split. Matches web's
// shared/src/thread-joins.ts; both run shared/testdata/thread-join-fixtures.json.
package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.pgp.OBSCURED_SUBJECT

/** Most `Message-ID`s the search for the threads an encrypted reply answers names. */
const val MAX_ANSWERED_SEARCH_IDS = 20

/**
 * The Gmail search for the threads holding the messages an encrypted reply answers, so Done and
 * Snooze from its notification reach the whole row even when those threads aren't loaded: the
 * first [MAX_ANSWERED_SEARCH_IDS] of a summary's [answers] ([JoinThread.answers]) as `rfc822msgid:`
 * terms; null for none.
 */
fun answeredSearch(answers: List<String>): String? =
    answers.take(MAX_ANSWERED_SEARCH_IDS)
        .map { "rfc822msgid:" + it.removePrefix("<").removeSuffix(">") }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" OR ")

/** What joining reads of one loaded Gmail thread. */
data class JoinThread(
    /** Gmail thread id */
    val id: String,
    /** whether its first message carries the hidden subject encrypted mail goes out with */
    val hiddenSubject: Boolean,
    /** the `Message-ID`s of its messages */
    val messageIds: List<String>,
    /** the ids its messages' `In-Reply-To` and `References` name; read only when [hiddenSubject] */
    val answers: List<String>,
    /** its first message's date, epoch milliseconds */
    val firstDate: Long,
)

/** What joining reads of a loaded thread's summary. */
fun ThreadSummary.joinThread(): JoinThread = JoinThread(id, subject == OBSCURED_SUBJECT, messageIds, answers, firstDate)

/**
 * The loaded threads that show as one row, each group oldest first (its first thread leads and
 * gives the row its id); threads joined to nothing are left out.
 *
 * A thread with the hidden subject joins the loaded thread holding a message it answers, and
 * joins chain, so a reply to a reply lands in the same row. A thread that isn't loaded joins
 * nothing: no extra reads go out to find it.
 */
fun joinThreads(threads: List<JoinThread>): List<List<String>> {
    val holder = mutableMapOf<String, String>()
    for (thread in threads) {
        for (messageId in thread.messageIds) holder.putIfAbsent(messageId, thread.id)
    }
    val parent = mutableMapOf<String, String>()
    fun root(id: String): String {
        var at = id
        while (true) at = parent[at] ?: return at
    }
    for (thread in threads) {
        if (!thread.hiddenSubject) continue
        for (answered in thread.answers) {
            val other = holder[answered]
            if (other == null || other == thread.id) continue
            val from = root(thread.id)
            val to = root(other)
            if (from != to) parent[from] = to
        }
    }
    val order = threads.withIndex().associate { (index, thread) -> thread.id to index }
    val byId = threads.associateBy { it.id }
    val groups = linkedMapOf<String, MutableList<String>>()
    for (thread in threads) {
        val group = groups.getOrPut(root(thread.id)) { mutableListOf() }
        if (thread.id !in group) group += thread.id
    }
    val joined = groups.values.filter { it.size > 1 }.map { group ->
        group.sortedWith(compareBy<String>({ byId.getValue(it).firstDate }, { order.getValue(it) }))
    }
    return joined.sortedBy { order.getValue(it.first()) }
}

/**
 * Each joined thread among [ids] (see [joinThreads]), by its id: the threads of its row, the lead
 * first. Threads with no summary, or joined to nothing, are left out.
 */
fun joinedGroups(ids: Collection<String>, summaries: Map<String, ThreadSummary>): Map<String, List<String>> {
    val groups = joinThreads(ids.mapNotNull { summaries[it]?.joinThread() })
    return groups.flatMap { group -> group.map { it to group } }.toMap()
}

/**
 * One row's summary for a joined group, [members] lead first: the lead's id and subject, the
 * newest member's snippet, senders in order without repeats, the counts added up, the latest dates
 * and history id, every label, unread when any member is, and encrypted when any member is.
 */
fun joinSummaries(members: List<ThreadSummary>): ThreadSummary {
    val lead = members.first()
    val newest = members.reduce { best, member -> if (member.lastDate > best.lastDate) member else best }
    val senders = mutableListOf<Person>()
    for (member in members) {
        for (person in member.senders) {
            val index = senders.indexOfFirst { it.email == person.email }
            if (index == -1) {
                senders += person
            } else if (senders[index].name.isEmpty() && person.name.isNotEmpty()) {
                senders[index] = senders[index].copy(name = person.name)
            }
        }
    }
    return lead.copy(
        historyId = members.map { it.historyId }.reduce { best, historyId -> if (compareHistoryIds(historyId, best) > 0) historyId else best },
        snippet = newest.snippet,
        senders = senders,
        messageCount = members.sumOf { it.messageCount },
        lastDate = members.maxOf { it.lastDate },
        sentDate = members.maxOf { it.sentDate },
        labelIds = members.flatMap { it.labelIds }.toSortedSet().toList(),
        unread = members.any { it.unread },
        encrypted = members.any { it.encrypted },
    )
}

/** What a joined row's snooze reads of one member. */
data class JoinSnoozeMember(
    /** its own snooze status */
    val status: SnoozeStatus,
    /** its snooze's end, epoch milliseconds, or null */
    val snoozeUntil: Long?,
    /** its Gmail history id when snoozed, or null */
    val watermark: String?,
    /** its latest Gmail history id */
    val historyId: String,
    /** whether it is in the inbox */
    val inInbox: Boolean,
)

/** A joined row's snooze: its status and when it ends. */
data class JoinedSnooze(
    /** the row's snooze status */
    val status: SnoozeStatus,
    /** the latest end of its members' snoozes, epoch milliseconds, or null */
    val snoozeUntil: Long?,
)

/**
 * A joined row's snooze. Snoozing the row snoozes each member, and any new reply ends it: a member
 * woken by a new message, or a member with no snooze (a reply Gmail split off after the snooze)
 * that is in the inbox with history newer than the snooze. Otherwise the row is snoozed while any
 * member is, lapsed once they all have, and not snoozed when none ever was.
 */
fun joinedSnooze(members: List<JoinSnoozeMember>): JoinedSnooze {
    val snoozed = members.filter { it.status != SnoozeStatus.NONE }
    if (snoozed.isEmpty()) return JoinedSnooze(SnoozeStatus.NONE, null)
    val until = snoozed.maxOf { it.snoozeUntil ?: 0L }
    val watermarks = snoozed.mapNotNull { it.watermark }
    val newerReply = members.any { member ->
        member.status == SnoozeStatus.NONE && member.inInbox && watermarks.any { compareHistoryIds(member.historyId, it) > 0 }
    }
    val status = when {
        newerReply || snoozed.any { it.status == SnoozeStatus.WOKEN } -> SnoozeStatus.WOKEN
        snoozed.any { it.status == SnoozeStatus.SNOOZED } -> SnoozeStatus.SNOOZED
        else -> SnoozeStatus.LAPSED
    }
    return JoinedSnooze(status, until)
}

/**
 * The open row's threads as one: every message (and draft) of [members] by date, each keeping its
 * own thread's id; the first member's id, and its subject unless that is the hidden one and
 * another member's isn't; the latest history id.
 */
fun mergeDetails(members: List<ThreadDetail>): ThreadDetail {
    if (members.size == 1) return members.first()
    val lead = members.first()
    fun owned(detail: ThreadDetail, messages: List<MessageView>) = messages.map { if (it.threadId.isEmpty()) it.copy(threadId = detail.id) else it }
    return ThreadDetail(
        id = lead.id,
        historyId = members.map { it.historyId }.reduce { best, historyId -> if (compareHistoryIds(historyId, best) > 0) historyId else best },
        subject = members.map { it.subject }.firstOrNull { it.isNotBlank() && it != OBSCURED_SUBJECT } ?: lead.subject,
        messages = members.flatMap { owned(it, it.messages) }.sortedBy { it.date },
        drafts = members.flatMap { owned(it, it.drafts) }.sortedBy { it.date },
    )
}
