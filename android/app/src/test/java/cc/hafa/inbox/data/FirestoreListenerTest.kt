package cc.hafa.inbox.data

import android.app.Application
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [FirestoreListener.live] holds only while what the listener delivered is current. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FirestoreListenerTest {
    private var delivered: EventListener<String>? = null
    private var removed = 0
    private var errors = 0
    private val snapshots = mutableListOf<String?>()

    private val listener = FirestoreListener<String>(
        "test",
        { events ->
            delivered = events
            ListenerRegistration { removed += 1 }
        },
        onError = { errors += 1 },
    ) { snapshots += it }

    private fun fail() = delivered?.onEvent(null, FirebaseFirestoreException("refused", FirebaseFirestoreException.Code.PERMISSION_DENIED))

    @Test
    fun liveOnlyAfterASnapshot() {
        assertFalse(listener.live)
        listener.start()
        assertFalse(listener.live)
        delivered?.onEvent("first", null)
        assertTrue(listener.live)
        assertEquals(listOf<String?>("first"), snapshots)
    }

    @Test
    fun anErrorEndsLiveUntilTheNextSnapshot() {
        listener.start()
        delivered?.onEvent("first", null)
        fail()
        assertFalse(listener.live)
        assertEquals(1, errors)
        listener.reattach()
        assertFalse(listener.live)
        delivered?.onEvent("second", null)
        assertTrue(listener.live)
    }

    @Test
    fun stopEndsLive() {
        listener.start()
        delivered?.onEvent("first", null)
        listener.stop()
        assertFalse(listener.live)
        assertEquals(1, removed)
    }
}
