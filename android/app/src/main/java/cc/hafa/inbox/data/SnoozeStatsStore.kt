package cc.hafa.inbox.data

import android.util.Log
import cc.hafa.inbox.core.snooze.CommitResult
import cc.hafa.inbox.core.snooze.LastCustom
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeStatsWire
import cc.hafa.inbox.core.snooze.SnoozeSuggestions
import cc.hafa.inbox.core.snooze.VoteDeltas
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SnoozeStatsStore"

/** Where a snooze (or scheduled send) time was picked. */
enum class SnoozeSource(
    /** the name the learned counts use */
    val wire: String,
) {
    /** a suggested row */
    SUGGESTION("suggestion"),

    /** the last time picked by hand */
    LAST("last"),

    /** a time picked by hand */
    CUSTOM("custom"),
}

/** What undoing one snooze needs: the engine's result and the amounts the commit added. */
data class SnoozeStatsUndo(
    /** what the engine returned for the commit */
    val result: CommitResult,
    /** the vote amounts the commit added */
    val deltas: VoteDeltas,
    /** whether the time was picked by hand rather than from the menu */
    val custom: Boolean,
)

/**
 * The account's learned snooze counts at `users/{uid}/meta/snoozeStats`, in the same format as
 * web, changed only by adding amounts so devices never overwrite each other.
 * Main-thread only.
 */
class SnoozeStatsStore(
    private val firestore: FirebaseFirestore,
    private val uid: String,
    private val scope: CoroutineScope,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    // `snoozeStats`, or `sendStats` for schedule send's counts, which use the same format.
    private val docName: String = "snoozeStats",
) {
    private val counts = MutableStateFlow(SnoozeStatsSnapshot())
    private var retryJob: Job? = null
    private var retryAttempt = 0

    /** The counts to feed [SnoozeSuggestions.rankDeep]. */
    val stats: StateFlow<SnoozeStatsSnapshot> = counts

    private fun document() = firestore.collection("users").document(uid).collection("meta").document(docName)

    private val listener = FirestoreListener<DocumentSnapshot>(docName, { document().addSnapshotListener(it) }, ::scheduleRetry) { snapshot ->
        if (snapshot?.metadata?.isFromCache == false) retryAttempt = 0
        counts.value = SnoozeStatsWire.sharedFromWire(snapshot?.data)
    }

    /** Start following the counts. */
    fun start() = listener.start()

    private fun scheduleRetry() {
        if (retryJob != null) return
        val backoff = minOf(MAX_RETRY_DELAY, BASE_RETRY_DELAY * (1 shl retryAttempt.coerceAtMost(RETRY_SHIFT_CAP)))
        retryAttempt++
        retryJob = scope.launch {
            delay(backoff)
            retryJob = null
            start()
        }
    }

    /** Retry the listener now instead of waiting out the backoff. */
    fun reattach() {
        val pending = retryJob ?: return
        pending.cancel()
        retryJob = null
        start()
    }

    /** Count a snooze (or scheduled send) to [target] made at [at]; [pickedKey] is the suggestion row's key. */
    fun commit(at: Long, target: Long, source: SnoozeSource, pickedKey: String?): SnoozeStatsUndo {
        val before = counts.value
        val result = SnoozeSuggestions.commit(before, at, target, zoneProvider(), source.wire, pickedKey)
        val deltas = SnoozeStatsWire.voteDeltas(before, result.next)
        val custom = source == SnoozeSource.CUSTOM
        counts.value = result.next
        write(deltas, if (custom) mapOf("lastCustom" to lastCustomWire(result.next.lastCustom)) else emptyMap())
        return SnoozeStatsUndo(result, deltas, custom)
    }

    /** Take back a commit. */
    fun undo(undo: SnoozeStatsUndo) {
        counts.value = SnoozeSuggestions.revert(counts.value, undo.result)
        val extra = if (undo.custom) mapOf("lastCustom" to lastCustomWire(undo.result.undoLastCustom)) else emptyMap()
        write(undo.deltas.negated(), extra)
    }

    private fun lastCustomWire(lastCustom: LastCustom?): Map<String, Long>? =
        lastCustom?.let { mapOf("target" to it.target, "at" to it.at) }

    // Amounts are added on the server (and to the cached copy while offline), so nothing another device wrote is lost.
    private fun write(deltas: VoteDeltas, extra: Map<String, Any?>) {
        val fields = extra.toMutableMap()
        if (deltas.sets.isNotEmpty()) fields[SnoozeStatsWire.SHARED_SETS_FIELD] = deltas.sets.mapValues { FieldValue.increment(it.value) }
        if (deltas.tod.isNotEmpty()) fields["tod"] = deltas.tod.mapValues { FieldValue.increment(it.value) }
        if (fields.isEmpty()) return
        document().set(fields, SetOptions.merge()).addOnFailureListener { Log.w(TAG, "snooze stats write failed", it) }
    }

    /** Stop listening. */
    fun stop() {
        listener.stop()
        retryJob?.cancel()
        retryJob = null
    }

    private companion object {
        val BASE_RETRY_DELAY = 1.seconds
        val MAX_RETRY_DELAY = 30.seconds
        const val RETRY_SHIFT_CAP = 5
    }
}
