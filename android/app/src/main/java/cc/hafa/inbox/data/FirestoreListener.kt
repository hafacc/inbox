package cc.hafa.inbox.data

import android.util.Log
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.ListenerRegistration

private const val TAG = "FirestoreListener"

/**
 * One Firestore snapshot listener that can be stopped and started again. A delivered error ends a
 * Firestore listener for good, so after one [onError] runs and [reattach] starts it afresh.
 * Main-thread only.
 */
class FirestoreListener<Snapshot>(
    private val name: String,
    private val register: (EventListener<Snapshot>) -> ListenerRegistration,
    private val onError: () -> Unit = {},
    private val onSnapshot: (Snapshot?) -> Unit,
) {
    private var registration: ListenerRegistration? = null

    /** Whether a snapshot came since the last start and no error or stop since: what it holds is current. */
    var live = false
        private set

    /** Start following, unless already following. */
    fun start() {
        if (registration != null) return
        registration = register { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "$name listener error", error)
                registration = null
                live = false
                onError()
            } else {
                live = true
                onSnapshot(snapshot)
            }
        }
    }

    /** Follow again after a listener error. */
    fun reattach() {
        if (registration == null) start()
    }

    /** Stop following. */
    fun stop() {
        registration?.remove()
        registration = null
        live = false
    }
}
