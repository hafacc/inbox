package cc.hafa.inbox.data

import android.util.Log
import cc.hafa.inbox.core.send.BlockedOn
import cc.hafa.inbox.core.send.DeviceClaim
import cc.hafa.inbox.core.send.DirectSendDoc
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.SendRoute
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.MetadataChanges
import java.util.Date
import java.util.UUID

private const val TAG = "OutboxStore"

/**
 * `users/{uid}/outbox`: undo and scheduled sends (docs/compose-contract.md). Clients write
 * `waiting` docs, whole; the server moves them on, but for a send now this device sends itself
 * ([claimDirect]). Firestore queues writes made offline.
 */
class OutboxStore(private val firestore: FirebaseFirestore, private val uid: String) {
    private val state = MutableStateFlow<List<OutboxDoc>>(emptyList())
    // Whether the server's copy has been read (true) or the listener failed first (false); null while waiting.
    private val firstReadState = MutableStateFlow<Boolean?>(null)

    /** Whether the server's copy of the outbox has been read (true) or the listener failed first (false); null while waiting. */
    val firstRead: StateFlow<Boolean?> = firstReadState

    /** Every outbox doc, soonest first. */
    val docs: StateFlow<List<OutboxDoc>> = state

    private fun collection() = firestore.collection("users").document(uid).collection("outbox")

    private val listener = FirestoreListener<QuerySnapshot>(
        "outbox",
        { collection().addSnapshotListener(MetadataChanges.INCLUDE, it) },
        onError = { if (firstReadState.value == null) firstReadState.value = false },
        onSnapshot = ::read,
    )

    /** Start following the outbox. */
    fun start() {
        if (firstReadState.value == false) firstReadState.value = null
        listener.start()
    }

    private fun read(snapshot: QuerySnapshot?) {
        // The offline cache may lack docs written elsewhere; only the server's answer counts as read.
        if (snapshot != null && !snapshot.metadata.isFromCache) firstReadState.value = true
        state.value = snapshot?.documents.orEmpty().mapNotNull { doc ->
            val sendError = doc.get("error") as? Map<*, *>
            val lastError = doc.get("lastError") as? Map<*, *>
            OutboxDoc(
                id = doc.id,
                draftId = doc.getString("draftId") ?: return@mapNotNull null,
                from = doc.getString("from") ?: "",
                status = OutboxStatus.of(doc.getString("status")),
                sendAt = doc.getTimestamp("sendAt")?.toDate()?.time ?: 0L,
                chosenAt = doc.getTimestamp("chosenAt")?.toDate()?.time,
                threadId = doc.getString("threadId"),
                errorCode = sendError?.get("code") as? String,
                errorMessage = sendError?.get("message") as? String,
                sentAt = doc.getTimestamp("sentAt")?.toDate()?.time,
                via = SendRoute.of(doc.getString("via")),
                filed = doc.getBoolean("filed"),
                nextAttemptAt = doc.getTimestamp("nextAttemptAt")?.toDate()?.time,
                lastErrorCode = lastError?.get("code") as? String,
                lastErrorMessage = lastError?.get("message") as? String,
                blockedOn = BlockedOn.of(doc.getString("blockedOn")),
            )
        }.sortedBy { it.sendAt }
    }

    /**
     * Wait for the server's copy of the outbox; false when the listener failed before it (an
     * encrypted send's Bcc copies can't be rebuilt right without it).
     */
    suspend fun awaitLoaded(): Boolean = firstReadState.filterNotNull().first()

    /** Follow again after a listener error. */
    fun reattach() = start()

    /** Stop following. */
    fun stop() = listener.stop()

    /** Write a doc whole as `waiting`: a new send, a reschedule or a retry. */
    fun write(id: String, draftId: String, from: String, sendAt: Long, chosenAt: Long?, threadId: String?) {
        val data = mapOf(
            "draftId" to draftId,
            "from" to from,
            "status" to "waiting",
            "sendAt" to Timestamp(Date(sendAt)),
            "chosenAt" to chosenAt?.let { Timestamp(Date(it)) },
            "threadId" to threadId,
        )
        collection().document(id).set(data).addOnFailureListener { Log.w(TAG, "outbox write failed", it) }
    }

    /**
     * Claim a send now for this device in a transaction (shared/src/direct-send.ts): only when the
     * doc as stored still names [draftId] and [mayClaim] says so for it, moving it to `sending` with
     * [claim] and what it hands over, as the rules allow. False when it isn't this device's to send;
     * throws when the transaction fails (offline, refused).
     */
    suspend fun claimDirect(id: String, draftId: String, claim: DeviceClaim, mayClaim: (DirectSendDoc?) -> Boolean): Boolean {
        val ref = collection().document(id)
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val sendAt = snapshot.getTimestamp("sendAt")
            val stored = if (!snapshot.exists() || sendAt == null) {
                null
            } else {
                DirectSendDoc(snapshot.getString("status").orEmpty(), sendAt.toDate().time, snapshot.data?.keys.orEmpty())
            }
            val claimed = snapshot.getString("draftId") == draftId && mayClaim(stored)
            if (claimed) {
                transaction.update(
                    ref,
                    mapOf(
                        "status" to "sending",
                        "claimedAt" to FieldValue.serverTimestamp(),
                        "firstAttemptAt" to FieldValue.serverTimestamp(),
                        "inFlightAt" to FieldValue.serverTimestamp(),
                        "via" to SendRoute.GMAIL.wire,
                        "draftMessageId" to claim.draftMessageId,
                        "messageIdHeader" to claim.messageIdHeader,
                        "deviceClaim" to claim.deviceClaim,
                    ),
                )
            }
            claimed
        }.await()
    }

    /**
     * Record this device's send now, claimed under [deviceClaim], as sent, as the server records a
     * Gmail send. The claim id is written as it stands, so the rules refuse it once another claim does.
     */
    fun recordDirectSent(id: String, messageId: String, threadId: String, deviceClaim: String) {
        val data = mapOf(
            "deviceClaim" to deviceClaim,
            "status" to "sent",
            "sentAt" to FieldValue.serverTimestamp(),
            "messageId" to messageId,
            "threadId" to threadId,
            "filed" to true,
            "rejected" to emptyList<String>(),
            "inFlightAt" to FieldValue.delete(),
        )
        collection().document(id).update(data).addOnFailureListener { Log.w(TAG, "recording a send failed", it) }
    }

    /** Record this device's send now, claimed under [deviceClaim], as refused by Gmail for good (`failed`, `rejected`). */
    fun recordDirectRefused(id: String, message: String, deviceClaim: String) {
        val data = mapOf(
            "deviceClaim" to deviceClaim,
            "status" to "failed",
            "error" to mapOf("code" to "rejected", "message" to message),
            "failedAt" to FieldValue.serverTimestamp(),
            "inFlightAt" to FieldValue.delete(),
        )
        collection().document(id).update(data).addOnFailureListener { Log.w(TAG, "recording a refused send failed", it) }
    }

    /** Delete a doc: undo, cancel (a retry finding no doc stops), or done with a result. */
    fun delete(id: String) {
        collection().document(id).delete().addOnFailureListener { Log.w(TAG, "outbox delete failed", it) }
    }

    companion object {
        /** A fresh outbox doc id. */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
