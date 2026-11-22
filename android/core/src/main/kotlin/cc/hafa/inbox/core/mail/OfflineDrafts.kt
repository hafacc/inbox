package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.send.GmailDraftRef
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.SendingDraft
import cc.hafa.inbox.core.send.outboxDrafts
import kotlinx.serialization.Serializable

// Drafts offline: the saved copy holds every thread with a draft, but Gmail's messages don't say
// which draft they are, and drafts.update needs the draft id. The saved copy keeps Gmail's drafts
// listing beside it, listed again whenever a saved draft message has no draft id, so a draft opens
// in the composer offline. Once the saved copy is filled, Drafts is built from it and the device's
// own drafts, online or not: a new message not in Gmail yet has a row of its own by its local id,
// and a draft the device knows went stays out until the saved copy drops it. Matches web's
// `shared/src/offline-drafts.ts`; both run shared/testdata/offline-draft-fixtures.json.

private const val DRAFT = "DRAFT"

/** A draft message the saved copy holds. */
data class SavedDraftMessage(
    /** Gmail message id */
    val messageId: String,
    /** Gmail thread id of its saved thread */
    val threadId: String,
)

/** Gmail's drafts as the saved copy last listed them. */
@Serializable
data class SavedDraftRefs(
    /** the listing, newest first */
    val refs: List<GmailDraftRef> = emptyList(),
    /** saved draft messages the listing didn't name, so listing again for them alone is skipped */
    val unmatched: List<String> = emptyList(),
)

/** A draft this device holds, as Drafts sees it. */
data class OfflineLocalDraft(
    /** its id on this device */
    val localId: String,
    /** the Gmail thread it is in, when known */
    val threadId: String?,
    /** its Gmail message id as last saved there, when it was */
    val messageId: String?,
    /** where it stands */
    val state: LocalDraftState,
)

/** What Drafts is built from. */
data class OfflineDraftsInput(
    /** the saved copy's draft messages */
    val saved: List<SavedDraftMessage>,
    /** the saved listing of Gmail's drafts */
    val refs: List<GmailDraftRef> = emptyList(),
    /** outbox docs, any status */
    val docs: List<OutboxDoc> = emptyList(),
    /** drafts this device is sending */
    val sending: List<SendingDraft> = emptyList(),
    /** drafts the device knows went (see [sentDrafts]) */
    val sent: List<GmailDraftRef> = emptyList(),
    /** drafts this device holds */
    val local: List<OfflineLocalDraft> = emptyList(),
)

/** A saved draft's parts as offline editing sees them. */
data class OfflineDraftParts(
    /** whether it is encrypted (its raw is needed to decrypt it) */
    val encrypted: Boolean,
    /** whether the saved copy has every header the composer reads back (Bcc included) */
    val headersKnown: Boolean,
    /** its attachments and inline images: whether the saved copy holds each one's bytes */
    val filesHeld: List<Boolean>,
)

/** The draft messages in saved threads' records (thread id to message id to labels). */
fun savedDraftMessages(threads: Map<String, Map<String, List<String>>>): List<SavedDraftMessage> =
    threads.flatMap { (threadId, messages) ->
        messages.filterValues { DRAFT in it }.keys.map { SavedDraftMessage(it, threadId) }
    }

/** Whether Gmail's drafts should be listed again: a saved draft message is new to the listing. */
fun draftsListingWanted(saved: List<SavedDraftMessage>, known: SavedDraftRefs): Boolean {
    val named = known.refs.map { it.messageId }.toSet() + known.unmatched
    return saved.any { it.messageId !in named }
}

/** What to keep after listing Gmail's drafts ([listed], newest first). */
fun afterDraftsListing(listed: List<GmailDraftRef>, saved: List<SavedDraftMessage>): SavedDraftRefs {
    val named = listed.map { it.messageId }.toSet()
    return SavedDraftRefs(listed, saved.map { it.messageId }.filter { it !in named }.sorted())
}

/**
 * The drafts the device knows went, kept until the saved copy no longer holds them as drafts:
 * [previous] plus every draft a sent doc names whose message [known] gives (the saved listing and
 * this device's sends), less those whose message isn't a saved draft any more.
 */
fun sentDrafts(
    previous: List<GmailDraftRef>,
    docs: List<OutboxDoc>,
    known: List<GmailDraftRef>,
    saved: List<SavedDraftMessage>,
): List<GmailDraftRef> {
    val savedIds = saved.map { it.messageId }.toSet()
    val byDraft = known.reversed().associateBy { it.draftId }
    val next = LinkedHashMap<String, GmailDraftRef>()
    for (record in previous) next[record.messageId] = record
    for (doc in docs) {
        if (doc.status != OutboxStatus.SENT) continue
        byDraft[doc.draftId]?.let { next[it.messageId] = it }
    }
    return next.values.filter { it.messageId in savedIds }.sortedBy { it.messageId }
}

/** Saved draft messages that show nowhere: held by the Outbox, or known to have gone. */
fun offlineHiddenDrafts(input: OfflineDraftsInput): Set<String> =
    outboxDrafts(input.refs, input.docs, input.sending).messageIds + input.sent.map { it.messageId }

// Saved draft messages the device's own drafts replace: thrown away or being sent here.
private fun replacedHere(local: List<OfflineLocalDraft>): Set<String> =
    local.filter { it.state != LocalDraftState.DRAFT }.mapNotNull { it.messageId }.toSet()

/**
 * The threads Drafts shows offline, sorted: each saved thread with a draft message that isn't
 * hidden ([offlineHiddenDrafts]) or thrown away or sent here, and each thread a draft of this
 * device's is kept in.
 */
fun offlineDraftThreads(input: OfflineDraftsInput): List<String> {
    val hidden = offlineHiddenDrafts(input)
    val replaced = replacedHere(input.local)
    val saved = input.saved.filter { it.messageId !in hidden && it.messageId !in replaced }.map { it.threadId }
    val local = input.local.filter { draft ->
        draft.state == LocalDraftState.DRAFT && (draft.messageId == null || draft.messageId !in hidden)
    }.mapNotNull { it.threadId }
    return (saved + local).toSortedSet().toList()
}

/**
 * This device's drafts in no Gmail thread yet, by local id, sorted: a new message kept as a draft
 * whose thread isn't known, unless its message (once saved to Gmail) is hidden
 * ([offlineHiddenDrafts]) or already a saved draft message, whose thread shows it.
 */
fun localOnlyDrafts(input: OfflineDraftsInput): List<String> {
    val hidden = offlineHiddenDrafts(input)
    val saved = input.saved.mapTo(mutableSetOf()) { it.messageId }
    return input.local.filter { draft ->
        draft.state == LocalDraftState.DRAFT && draft.threadId == null &&
            (draft.messageId == null || (draft.messageId !in hidden && draft.messageId !in saved))
    }.map { it.localId }.sorted()
}

/**
 * The saved draft to open offline: the newest the listing names whose message is [messageId], or
 * that is in one of [threadIds], among those that show (as in [offlineDraftThreads]); null when the
 * saved copy can't say which draft it is.
 */
fun offlineDraftOf(input: OfflineDraftsInput, messageId: String? = null, threadIds: Set<String> = emptySet()): GmailDraftRef? {
    val hidden = offlineHiddenDrafts(input)
    val replaced = replacedHere(input.local)
    val savedThread = input.saved.associate { it.messageId to it.threadId }
    for (ref in input.refs) {
        val threadId = savedThread[ref.messageId] ?: continue
        if (ref.messageId in hidden || ref.messageId in replaced) continue
        if (ref.messageId == messageId || threadId in threadIds) return ref.copy(threadId = threadId)
    }
    return null
}

/**
 * Whether a saved draft can be edited offline: Gmail's drafts.update replaces the whole draft, so
 * only one whose saved copy holds all of it (not encrypted, every header, every file's bytes).
 */
fun offlineEditable(parts: OfflineDraftParts): Boolean = !parts.encrypted && parts.headersKnown && parts.filesHeld.all { it }

/** What offline editing needs to know of a saved draft [message]. */
fun savedDraftParts(message: MessageView): OfflineDraftParts = OfflineDraftParts(
    encrypted = message.lock != null,
    headersKnown = true,
    filesHeld = message.body.attachments.map { it.data != null } + message.body.inline.map { it.data != null },
)
