package cc.hafa.inbox.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions

private const val TAG = "LabelPrefDocs"

/**
 * `users/{uid}/labelPrefs`: each label's drawer, bundle and icon settings, as stored.
 * [start] follows them into [onChange]; after a listener error, [reattach] follows again.
 */
class LabelPrefDocs(
    private val firestore: FirebaseFirestore,
    private val uid: String,
    private val onChange: (Map<String, Map<String, Any?>>) -> Unit,
) {
    private fun collection() = firestore.collection("users").document(uid).collection("labelPrefs")

    private val listener = FirestoreListener<QuerySnapshot>("labelPrefs", { collection().addSnapshotListener(it) }) { snapshot ->
        onChange(snapshot?.documents.orEmpty().associate { entry -> entry.id to entry.data.orEmpty() })
    }

    /** Start following every label's settings. */
    fun start() = listener.start()

    /** Follow again after a listener error. */
    fun reattach() = listener.reattach()

    /** Stop following. */
    fun stop() = listener.stop()

    /** Merge [patch] (any of `sidebar`, `bundle`, `icon`) into a label's settings. */
    fun write(labelId: String, patch: Map<String, Any>) {
        collection().document(labelId).set(patch, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "label settings write failed", it) }
    }

    /** Merge [patch] into [to]'s settings, then drop [from]'s. */
    fun move(from: String, to: String, patch: Map<String, Any>) {
        val batch = firestore.batch()
        batch.set(collection().document(to), patch, SetOptions.merge())
        batch.delete(collection().document(from))
        batch.commit().addOnFailureListener { Log.w(TAG, "label settings move failed", it) }
    }
}
