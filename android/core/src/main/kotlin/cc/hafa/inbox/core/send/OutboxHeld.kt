package cc.hafa.inbox.core.send

// Port of shared/src/outbox-held.ts: a send made on the device gets its outbox doc only once Gmail
// has saved its draft, so offline it has none yet; until then the Outbox lists it as held on the
// device. Both run shared/testdata/outbox-held-fixtures.json.

/** What a held send's row says in place of its time or status. */
const val HELD_SEND_STATUS = "Waiting for a connection"

/** A send queued on the device, as [heldSendIds] sees it. */
data class QueuedSend(
    /** the draft's id on this device */
    val localId: String,
    /** the outbox doc it writes or rewrites, once it has an id */
    val outboxId: String?,
    /** whether this device has written its outbox doc already */
    val docWritten: Boolean,
)

/**
 * The local ids of queued sends the Outbox lists as held on the device: those with no outbox doc
 * yet, neither written by this device nor among the docs ([docIds]) it has heard of.
 */
fun heldSendIds(sends: Iterable<QueuedSend>, docIds: Iterable<String>): List<String> {
    val known = docIds.toSet()
    return sends.filter { send -> !send.docWritten && (send.outboxId == null || send.outboxId !in known) }.map { it.localId }
}
