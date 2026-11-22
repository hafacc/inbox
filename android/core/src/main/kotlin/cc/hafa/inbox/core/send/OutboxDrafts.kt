package cc.hafa.inbox.core.send

import kotlinx.serialization.Serializable

/** A Gmail draft as `drafts.list` gives it. */
@Serializable
data class GmailDraftRef(
    /** Gmail draft id */
    val draftId: String,
    /** Gmail message id of its message */
    val messageId: String,
    /** Gmail thread id of its message */
    val threadId: String,
)

/** The Gmail drafts the Outbox holds. */
data class OutboxDrafts(
    /** Gmail message ids of the drafts, hidden among a thread's drafts */
    val messageIds: Set<String> = emptySet(),
    /** threads whose every draft is in the Outbox, left out of Drafts and its count */
    val threadIds: Set<String> = emptySet(),
)

/** The draft ids outbox docs name that the outbox still holds: every doc but a sent one. */
fun outboxDraftIds(docs: List<OutboxDoc>): Set<String> =
    docs.filter { it.status != OutboxStatus.SENT }.map { it.draftId }.toSet()

/** A draft this device is sending: queued here, or held by an outbox doc it has. */
data class SendingDraft(
    /** its Gmail draft id, once saved there */
    val draftId: String?,
    /** its Gmail message id as last saved, when known */
    val messageId: String?,
    /** its Gmail thread, when known */
    val threadId: String?,
)

/**
 * Which of Gmail's drafts the Outbox holds: those outbox [docs] or this device's sends ([sending])
 * name. A scheduled send stays a Gmail draft until it goes (the server sends from the draft), but
 * shows only in the Outbox: Drafts, its count and a thread's drafts leave these out. [drafts] is
 * Gmail's last listing and [listedFor] the drafts the Outbox held when it was made; a held draft
 * the listing leaves out but wasn't made for (it is newer) still leaves Drafts by the thread the
 * device or its doc names, so it never shows in Drafts and the Outbox at once while the listing
 * catches up. Matches web's `shared/src/outbox-drafts.ts`.
 */
fun outboxDrafts(
    drafts: List<GmailDraftRef>,
    docs: List<OutboxDoc>,
    sending: List<SendingDraft> = emptyList(),
    listedFor: Set<String> = emptySet(),
): OutboxDrafts {
    val held = outboxDraftIds(docs) + sending.mapNotNull { it.draftId }
    val (outbox, others) = drafts.partition { it.draftId in held }
    val draftThreads = others.map { it.threadId }.toSet()
    val listed = drafts.map { it.draftId }.toSet()
    // A draft not yet saved isn't in Drafts at all.
    fun unlisted(draftId: String?) = draftId != null && draftId !in listed && draftId !in listedFor
    val newer = sending.filter { unlisted(it.draftId) }.mapNotNull { it.threadId } +
        docs.filter { it.status != OutboxStatus.SENT && unlisted(it.draftId) }.mapNotNull { it.threadId?.takeIf(String::isNotEmpty) }
    return OutboxDrafts(
        messageIds = outbox.map { it.messageId }.toSet() + sending.mapNotNull { it.messageId },
        threadIds = (outbox.map { it.threadId } + newer).filter { it !in draftThreads }.toSet(),
    )
}

/** Drafts' count: Gmail's thread count for the `DRAFT` label less the threads only the Outbox holds. */
fun draftThreadCount(labelThreads: Long, outbox: OutboxDrafts): Long =
    (labelThreads - outbox.threadIds.size).coerceAtLeast(0)

/**
 * Whether a thread shows only in the Outbox: it has no message but drafts ([sentOrReceived] is how
 * many it has that aren't drafts) and every draft in it is one the Outbox holds.
 */
fun onlyInOutbox(threadId: String, sentOrReceived: Int, outbox: OutboxDrafts): Boolean =
    sentOrReceived == 0 && threadId in outbox.threadIds
