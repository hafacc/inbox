package cc.hafa.inbox.data

import android.util.Log
import com.google.android.gms.tasks.Task
import cc.hafa.inbox.core.mail.SnoozeDoc
import cc.hafa.inbox.core.mail.epochToIso
import cc.hafa.inbox.core.mail.isLocalIso
import cc.hafa.inbox.core.mail.isoToEpoch
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

private const val TAG = "SnoozeDocs"

/**
 * `users/{uid}/threads`: each thread's snooze, kept by Firestore (which queues writes offline).
 * `snoozeUntil` is stored as a local wall time with no zone and read in this device's zone.
 * [start] follows them into [onChange]; after a listener error, [reattach] follows again.
 */
class SnoozeDocs(
    private val firestore: FirebaseFirestore,
    private val uid: String,
    private val onChange: (Map<String, SnoozeDoc>) -> Unit,
) {
    private fun collection() = firestore.collection("users").document(uid).collection("threads")

    private val listener = FirestoreListener<QuerySnapshot>("snooze", { collection().addSnapshotListener(it) }) { snapshot ->
        val docs = snapshot?.documents.orEmpty().associate { entry ->
            val until = entry.getString("snoozeUntil")?.takeIf(::isLocalIso)?.let(::isoToEpoch)
            entry.id to SnoozeDoc(until, entry.getString("snoozeWatermark"))
        }
        onChange(docs)
    }

    /** Whether the snoozes last passed to onChange are current (the listener answered and hasn't failed or stopped since). */
    val live: Boolean get() = listener.live

    /** Start following every thread's snooze. */
    fun start() = listener.start()

    /** Follow again after a listener error. */
    fun reattach() = listener.reattach()

    /** Stop following. */
    fun stop() = listener.stop()

    /** Save a thread's snooze, its end as this device's wall time; the task ends once Firestore has it. */
    fun write(threadId: String, snooze: SnoozeDoc): Task<Void> {
        val data = mapOf(
            "snoozeUntil" to snooze.snoozeUntil?.let { epochToIso(it) },
            "snoozeWatermark" to snooze.snoozeWatermark,
        )
        return collection().document(threadId).set(data).addOnFailureListener { Log.w(TAG, "snooze write failed", it) }
    }

    /** Forget a thread's snooze. */
    fun delete(threadId: String) {
        collection().document(threadId).delete().addOnFailureListener { Log.w(TAG, "snooze clear failed", it) }
    }
}
