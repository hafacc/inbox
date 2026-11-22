package cc.hafa.inbox.core.mail

// Gmail label changes made offline or not yet sent, kept in the order they were made and sent one
// request per thread; web's shared/src/offline-queue.ts. Both run shared/testdata/offline-queue-fixtures.json.

import kotlinx.serialization.Serializable

/** A Gmail label change waiting to be sent, in the order the user made it. */
@Serializable
data class LabelChange(
    /** position in the order the user made changes */
    val seq: Long,
    /** Gmail thread id */
    val threadId: String,
    /** label ids to add */
    val add: List<String>,
    /** label ids to remove */
    val remove: List<String>,
    /** whether Done was tapped on a notification, which says so if Gmail refuses it for good */
    val fromNotification: Boolean = false,
)

/** Append a change, numbered after every change already queued. */
fun enqueue(
    queue: List<LabelChange>,
    threadId: String,
    add: List<String>,
    remove: List<String>,
    fromNotification: Boolean = false,
): List<LabelChange> {
    val highest = queue.maxOfOrNull { it.seq } ?: 0L
    return queue + LabelChange(highest + 1, threadId, add, remove, fromNotification)
}

/** The queue in the order to replay it. */
fun inReplayOrder(queue: List<LabelChange>): List<LabelChange> = queue.sortedBy { it.seq }

/** Labels after a change; a label both added and removed ends up added. */
fun applyChange(labelIds: List<String>, add: List<String>, remove: List<String>): List<String> =
    ((labelIds.toSet() - remove.toSet()) + add).sorted()

/**
 * Merge each thread's queued changes into one net change, so replay sends one request per thread.
 *
 * Changes to different threads don't interact, so each merged change takes the place of that
 * thread's last change.
 */
fun compact(queue: List<LabelChange>): List<LabelChange> {
    class Net(var seq: Long, val add: MutableSet<String>, val remove: MutableSet<String>)
    val byThread = linkedMapOf<String, Net>()
    for (change in inReplayOrder(queue)) {
        val net = byThread.getOrPut(change.threadId) { Net(change.seq, mutableSetOf(), mutableSetOf()) }
        for (label in change.remove) {
            net.add -= label
            net.remove += label
        }
        for (label in change.add) {
            net.remove -= label
            net.add += label
        }
        net.seq = change.seq
    }
    return inReplayOrder(
        byThread.map { (threadId, net) -> LabelChange(net.seq, threadId, net.add.sorted(), net.remove.sorted()) },
    )
}

/** What sending one change came to. */
enum class ReplayStep {
    /** sent or refused for good, and taken off the queue */
    SETTLED,

    /** it has to wait (offline, say), so nothing more is started */
    LATER,
}

/**
 * Send the queue in replay order (compacted, one change per thread) one at a time until it is empty
 * or a change has to wait, also sending changes queued while it runs. [send] settles one change and
 * takes it off the queue, or answers [ReplayStep.LATER]; returns whether the queue emptied.
 */
suspend fun replay(queue: () -> List<LabelChange>, send: suspend (LabelChange) -> ReplayStep): Boolean {
    while (true) {
        val pending = compact(queue())
        if (pending.isEmpty()) return true
        for (change in pending) {
            if (send(change) == ReplayStep.LATER) return false
        }
    }
}
