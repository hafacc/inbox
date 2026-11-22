package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.filters.GmailFilterAction
import cc.hafa.inbox.core.filters.GmailFilterCriteria
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.gmail.GmailLabelColor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Start of an id made on the device for a label or filter Gmail hasn't made yet. */
const val LOCAL_ID_PREFIX = "local:"

/** Whether an id stands in for one Gmail hasn't given yet. */
fun isLocalId(id: String): Boolean = id.startsWith(LOCAL_ID_PREFIX)

/** A new id to stand in for a label or filter until Gmail makes it. */
fun newLocalId(): String = LOCAL_ID_PREFIX + UUID.randomUUID()

/**
 * A change to the account's labels or filters waiting to be sent, in the order the user made it.
 * A label or filter made on the device has a local id until Gmail makes it; later changes name it
 * by that id and are rewritten to Gmail's once it comes back.
 */
@Serializable
sealed interface AccountChange {
    /** position in the order the user made changes */
    val seq: Long

    /** Make a user label. */
    @Serializable
    @SerialName("createLabel")
    data class CreateLabel(
        override val seq: Long,
        /** the local id it shows under until Gmail makes it */
        val labelId: String,
        /** full name; `/` separates nesting */
        val name: String,
        /** colors, or null for Gmail's default */
        val color: GmailLabelColor? = null,
    ) : AccountChange

    /** Rename a label or change its color. */
    @Serializable
    @SerialName("patchLabel")
    data class PatchLabel(
        override val seq: Long,
        /** label id */
        val labelId: String,
        /** new full name, or null to keep it */
        val name: String? = null,
        /** new colors, or null to keep them */
        val color: GmailLabelColor? = null,
    ) : AccountChange

    /** Make a filter, in place of [replacing] when given. */
    @Serializable
    @SerialName("createFilter")
    data class CreateFilter(
        override val seq: Long,
        /** the local id it shows under until Gmail makes it */
        val filterId: String,
        /** what it matches */
        val criteria: GmailFilterCriteria,
        /** what it does */
        val action: GmailFilterAction,
        /** the filter it takes the place of, deleted once this one is made */
        val replacing: String? = null,
    ) : AccountChange

    /** Delete a filter. */
    @Serializable
    @SerialName("deleteFilter")
    data class DeleteFilter(
        override val seq: Long,
        /** filter id */
        val filterId: String,
    ) : AccountChange

    /** Label the threads a search finds now (a new filter applied to mail already there). */
    @Serializable
    @SerialName("labelMatching")
    data class LabelMatching(
        override val seq: Long,
        /** Gmail search */
        val query: String,
        /** label ids to add */
        val add: List<String>,
        /** label ids to remove */
        val remove: List<String>,
    ) : AccountChange
}

/** Append the change [build] makes from its number, numbered after every change already queued. */
fun enqueueAccount(queue: List<AccountChange>, build: (Long) -> AccountChange): List<AccountChange> =
    queue + build((queue.maxOfOrNull { it.seq } ?: 0L) + 1)

/** The stored queue, then the changes made before it was read, numbered after it. */
fun joinAccountQueues(stored: List<AccountChange>, early: List<AccountChange>): List<AccountChange> =
    early.sortedBy { it.seq }.fold(stored) { queue, change -> enqueueAccount(queue) { seq -> change.renumbered(seq) } }

/** This change numbered [seq] instead. */
fun AccountChange.renumbered(seq: Long): AccountChange = when (this) {
    is AccountChange.CreateLabel -> copy(seq = seq)
    is AccountChange.PatchLabel -> copy(seq = seq)
    is AccountChange.CreateFilter -> copy(seq = seq)
    is AccountChange.DeleteFilter -> copy(seq = seq)
    is AccountChange.LabelMatching -> copy(seq = seq)
}

/** The change to send next: the earliest made. */
fun nextAccountChange(queue: List<AccountChange>): AccountChange? = queue.minByOrNull { it.seq }

private fun List<String>.swapped(from: String, to: String) = map { if (it == from) to else it }

private fun GmailFilterAction.uses(labelId: String) =
    addLabelIds.orEmpty().contains(labelId) || removeLabelIds.orEmpty().contains(labelId)

/** Name Gmail's id wherever later changes name a label or filter by its local id. */
fun resolveLocalId(queue: List<AccountChange>, localId: String, gmailId: String): List<AccountChange> = queue.map { change ->
    when {
        change is AccountChange.PatchLabel && change.labelId == localId -> change.copy(labelId = gmailId)
        change is AccountChange.DeleteFilter && change.filterId == localId -> change.copy(filterId = gmailId)
        change is AccountChange.CreateFilter && (change.replacing == localId || change.action.uses(localId)) -> change.copy(
            action = change.action.copy(
                addLabelIds = change.action.addLabelIds?.swapped(localId, gmailId),
                removeLabelIds = change.action.removeLabelIds?.swapped(localId, gmailId),
            ),
            replacing = if (change.replacing == localId) gmailId else change.replacing,
        )
        change is AccountChange.LabelMatching && (localId in change.add || localId in change.remove) ->
            change.copy(add = change.add.swapped(localId, gmailId), remove = change.remove.swapped(localId, gmailId))
        else -> change
    }
}

/**
 * Forget a label or filter Gmail refused to make: changes to it go, and so do a filter or a labeling
 * of matching mail that would use the label; a filter that was to replace it replaces nothing.
 */
fun withoutLocalId(queue: List<AccountChange>, localId: String): List<AccountChange> = queue.mapNotNull { change ->
    when {
        change is AccountChange.PatchLabel && change.labelId == localId -> null
        change is AccountChange.DeleteFilter && change.filterId == localId -> null
        change is AccountChange.CreateFilter && change.action.uses(localId) -> null
        change is AccountChange.LabelMatching && (localId in change.add || localId in change.remove) -> null
        change is AccountChange.CreateFilter && change.replacing == localId -> change.copy(replacing = null)
        else -> change
    }
}

/** The queue once the change numbered [seq] reached Gmail. */
fun changeSent(queue: List<AccountChange>, seq: Long): List<AccountChange> = queue.filter { it.seq != seq }

/** The queue once Gmail made the label the change numbered [seq] made, as [gmailId]. */
fun labelMade(queue: List<AccountChange>, seq: Long, gmailId: String): List<AccountChange> {
    val change = queue.firstOrNull { it.seq == seq } as? AccountChange.CreateLabel ?: return queue
    return resolveLocalId(changeSent(queue, seq), change.labelId, gmailId)
}

/**
 * The queue once Gmail made the filter the change numbered [seq] made, as [gmailId]. An edit's old
 * filter is deleted next, in that change's place, so a retry doesn't make the new one twice; Gmail
 * naming the old filter itself (an edit that changed nothing) leaves nothing to delete.
 */
fun filterMade(queue: List<AccountChange>, seq: Long, gmailId: String): List<AccountChange> {
    val change = queue.firstOrNull { it.seq == seq } as? AccountChange.CreateFilter ?: return queue
    val replacing = change.replacing
    val next = if (replacing == null || replacing == gmailId) {
        changeSent(queue, seq)
    } else {
        queue.map { if (it.seq == seq) AccountChange.DeleteFilter(seq, replacing) else it }
    }
    return resolveLocalId(next, change.filterId, gmailId)
}

/** The queue once Gmail refused the change numbered [seq] for good: it goes, with what depended on it. */
fun changeRefused(queue: List<AccountChange>, seq: Long): List<AccountChange> {
    val rest = changeSent(queue, seq)
    return when (val change = queue.firstOrNull { it.seq == seq }) {
        is AccountChange.CreateLabel -> withoutLocalId(rest, change.labelId)
        is AccountChange.CreateFilter -> withoutLocalId(rest, change.filterId)
        else -> rest
    }
}

/**
 * Rename a label in queued thread changes, or drop it with [to] null. Unchanged entries are the
 * same objects, so a caller can tell which to save again.
 */
fun relabelChanges(changes: List<LabelChange>, from: String, to: String?): List<LabelChange> {
    fun swap(ids: List<String>) = if (to == null) ids.filter { it != from } else ids.swapped(from, to)
    return changes.map { change ->
        if (from in change.add || from in change.remove) change.copy(add = swap(change.add), remove = swap(change.remove)) else change
    }
}

/**
 * Thread changes that can go to Gmail now: those of threads none of whose queued changes names a
 * label Gmail hasn't made yet (a thread's changes go together, in order).
 */
fun sendableChanges(changes: List<LabelChange>): List<LabelChange> {
    val waiting = changes.filter { change -> (change.add + change.remove).any(::isLocalId) }.mapTo(mutableSetOf()) { it.threadId }
    return changes.filter { it.threadId !in waiting }
}

/** Gmail's labels as the device shows them: queued new labels added, queued renames and colors applied. */
fun labelsWithPending(labels: List<GmailLabel>, queue: List<AccountChange>): List<GmailLabel> {
    var shown = labels
    for (change in queue.sortedBy { it.seq }) {
        when (change) {
            is AccountChange.CreateLabel -> if (shown.none { it.id == change.labelId }) {
                shown = shown + GmailLabel(change.labelId, change.name, "user", "labelShow", change.color)
            }
            is AccountChange.PatchLabel -> shown = shown.map { label ->
                if (label.id == change.labelId) label.copy(name = change.name ?: label.name, color = change.color ?: label.color) else label
            }
            else -> Unit
        }
    }
    return shown
}

/** Gmail's filters as the device shows them: queued new ones added, queued deletes and replaced ones gone. */
fun filtersWithPending(filters: List<GmailFilter>, queue: List<AccountChange>): List<GmailFilter> {
    var shown = filters
    for (change in queue.sortedBy { it.seq }) {
        when (change) {
            is AccountChange.CreateFilter -> shown = shown.filter { it.id != change.replacing && it.id != change.filterId } +
                GmailFilter(change.filterId, change.criteria, change.action)
            is AccountChange.DeleteFilter -> shown = shown.filter { it.id != change.filterId }
            else -> Unit
        }
    }
    return shown
}

/** How many changes haven't reached Gmail: [queue]d label changes on threads (as stored) and label or filter edits. */
fun unsyncedChanges(queue: List<*>, accountQueue: List<AccountChange>): Int = queue.size + accountQueue.size
