package cc.hafa.inbox.notify

import android.annotation.SuppressLint
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.core.notify.PushPayload
import cc.hafa.inbox.core.notify.isForAccount
import cc.hafa.inbox.core.notify.parseClear
import cc.hafa.inbox.core.notify.parsePush
import cc.hafa.inbox.core.runSuspendCatching
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SNOOZE_KIND = "snooze"

// How long a push waits for the app's mail and the decrypted preview before the plain notice.
private const val DECRYPT_WAIT_MS = 8_000L

/**
 * Receives the server's pushes: new mail refreshes when the app is on screen, otherwise notifies;
 * a clear push takes away notifications about threads read or archived elsewhere.
 */
// The lint predates FCM's register()/onRegistered, which replace the deprecated onNewToken.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class PushService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        (application as InboxApplication).tokenIssued(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The server's ping that a snooze change moves this phone's snooze alarm.
        if (message.data["kind"] == SNOOZE_KIND) {
            runBlocking { SnoozeAlarm.check(this@PushService) }
            return
        }
        val app = application as InboxApplication
        // The server's ask to clear threads read or taken out of the inbox elsewhere.
        val clear = parseClear(message.data)
        if (clear != null) {
            if (isForAccount(clear.account, app.signedInEmail())) Notifier.cancelThreads(this, clear.threadIds)
            return
        }
        val payload = parsePush(message.data) ?: return
        if (!isForAccount(payload, app.signedInEmail())) return
        val onScreen = runBlocking {
            withContext(Dispatchers.Main) {
                val started = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                // The open app shows new mail itself; history says what changed, and a refresh
                // already running takes this one after it.
                if (started) app.session.value?.refresh(force = false)
                started
            }
        }
        if (!onScreen) {
            val shown = if (payload.encrypted) decrypted(app, payload) else payload
            Notifier.show(this, shown, runBlocking { Notifier.snoozeTime(this@PushService) })
        }
    }

    // The server can't read encrypted mail, so the phone fetches and decrypts it for the preview.
    private fun decrypted(app: InboxApplication, payload: PushPayload): PushPayload = runBlocking {
        if (payload.messageId.isEmpty() || app.authManager?.currentUser?.value == null) return@runBlocking payload
        val preview = withTimeoutOrNull(DECRYPT_WAIT_MS) {
            val session = app.session.filterNotNull().first()
            runSuspendCatching { session.encryption.notificationPreview(payload.messageId) }.getOrNull()
        }
        if (preview == null) payload else payload.copy(subject = preview.subject, snippet = preview.snippet)
    }
}
