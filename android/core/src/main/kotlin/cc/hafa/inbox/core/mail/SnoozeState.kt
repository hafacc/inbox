package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord

/**
 * `users/{uid}/threads/{threadId}` as this app reads it. Stored, `snoozeUntil` is a local wall time
 * with no zone (`YYYY-MM-DDTHH:mm:ss`, as in latr), read in this device's zone (see [isoToEpoch]).
 */
data class SnoozeDoc(
    /** when the snooze ends, epoch milliseconds in this device's zone; null when not snoozed */
    val snoozeUntil: Long?,
    /** the thread's Gmail historyId when it was snoozed */
    val snoozeWatermark: String?,
)

/** Where a thread stands with its snooze. */
enum class SnoozeStatus {
    /** never snoozed, or cleared */
    NONE,

    /** still hidden */
    SNOOZED,

    /** the time passed with no new message */
    LAPSED,

    /** a newer message arrived in the inbox */
    WOKEN,
}

/** A thread's snooze status at [now]; derived, so nothing is written when a snooze ends. */
fun snoozeStatus(doc: SnoozeDoc?, now: Long, hasNewerMessage: Boolean): SnoozeStatus {
    val until = doc?.snoozeUntil ?: return SnoozeStatus.NONE
    return when {
        hasNewerMessage -> SnoozeStatus.WOKEN
        until > now -> SnoozeStatus.SNOOZED
        else -> SnoozeStatus.LAPSED
    }
}

/** Snoozed threads that got a message after their watermark, by where the message arrived. */
data class NewerMail(
    /** threads a newer message arrived in the inbox in; their snoozes end */
    val inInbox: Set<String>,
    /** threads a newer message arrived in outside the inbox (a Gmail filter skipped it); still snoozed */
    val skippedInbox: Set<String>,
)

/**
 * Sort the messages `history.list` records added after each thread's watermark by whether they
 * arrived in the inbox.
 *
 * Labels are per message, so only the new message's own labels count. Gmail can report a message's
 * current labels rather than those it arrived with, so a message that gains INBOX in a later record
 * of the same scan arrived without it. Drafts, spam, trash and mail the account sent itself (unless
 * it landed in the inbox, a note to self) count as neither.
 */
fun newerMail(records: List<GmailHistoryRecord>, watermarks: Map<String, String>): NewerMail {
    val gainedInbox = records
        .flatMap { it.labelsAdded.orEmpty() }
        .filter { Labels.INBOX in it.labelIds.orEmpty() }
        .mapTo(mutableSetOf()) { it.message.id }
    val inInbox = linkedSetOf<String>()
    val skippedInbox = linkedSetOf<String>()
    for (record in records) {
        for (added in record.messagesAdded.orEmpty()) {
            val message = added.message
            val watermark = watermarks[message.threadId] ?: continue
            if (compareHistoryIds(record.id, watermark) <= 0) continue
            val labels = message.labelIds.orEmpty()
            val arrivedInInbox = Labels.INBOX in labels && message.id !in gainedInbox
            if (arrivedInInbox && Labels.DRAFT !in labels) {
                inInbox += message.threadId
            } else if (listOf(Labels.DRAFT, Labels.SENT, Labels.SPAM, Labels.TRASH).none { it in labels }) {
                skippedInbox += message.threadId
            }
        }
    }
    return NewerMail(inInbox, skippedInbox)
}

/** The date a thread sorts by in the inbox: its newest message, or when its snooze ended if later. */
fun inboxSortDate(lastDate: Long, doc: SnoozeDoc?, status: SnoozeStatus): Long {
    val until = doc?.snoozeUntil
    return if (status == SnoozeStatus.LAPSED && until != null) maxOf(lastDate, until) else lastDate
}

/**
 * Whether a fresh Gmail copy shows a still-snoozed thread was archived elsewhere (in Gmail itself,
 * say), which counts as Done, so its snooze should be dropped. A snoozed thread stays in the inbox.
 *
 * Only for a snooze still in the future, only when this device has no change of its own on the
 * thread still on its way to Gmail, and only for a copy newer than the snooze: a copy at or below the
 * watermark predates the snooze (a thread snoozed from outside the inbox gains INBOX after it).
 */
fun snoozeArchivedElsewhere(
    doc: SnoozeDoc?,
    gmailLabelIds: List<String>,
    gmailHistoryId: String?,
    now: Long,
    hasLocalChange: Boolean,
): Boolean {
    val until = doc?.snoozeUntil ?: return false
    if (until <= now || hasLocalChange || Labels.INBOX in gmailLabelIds) return false
    val watermark = doc.snoozeWatermark ?: return false
    return gmailHistoryId != null && compareHistoryIds(gmailHistoryId, watermark) > 0
}
