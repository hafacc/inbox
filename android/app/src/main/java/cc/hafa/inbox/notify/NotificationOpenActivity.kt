package cc.hafa.inbox.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.MainActivity
import cc.hafa.inbox.core.notify.notificationTag

/**
 * Where a notification's tap, Open and Undo land: not exported, so only this app's notifications
 * can ask to open a thread or take back a send. It hands the request to the app and brings up
 * [MainActivity], which takes no such request from its own intent.
 */
class NotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as InboxApplication
        val threadId = intent.getStringExtra(Notifier.EXTRA_THREAD_ID)
        val outboxId = intent.getStringExtra(Notifier.EXTRA_UNDO_OUTBOX)
        val localId = intent.getStringExtra(Notifier.EXTRA_UNDO_LOCAL)
        if (outboxId != null && localId != null) {
            threadId?.let { Notifier.cancel(this, notificationTag(it)) }
            app.undoSendFromNotification(outboxId, localId)
        } else if (threadId != null) {
            app.openFromNotification(
                threadId,
                intent.getBooleanExtra(Notifier.EXTRA_REPLY, false),
                intent.getStringExtra(Notifier.EXTRA_REPLY_TEXT).orEmpty(),
            )
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
