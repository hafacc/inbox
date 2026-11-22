package cc.hafa.inbox.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import cc.hafa.inbox.core.notify.notificationTag
import cc.hafa.inbox.core.notify.todoIdOf

private const val DONE = "done:"
private const val SNOOZE = "snooze:"
private const val REPLY = "reply:"

/**
 * Runs a notification's buttons without opening the app: Done and Snooze (handed to
 * [NotificationActionWorker]) and the inline Reply (handed to [ReplyWorker]), each with the time
 * to set up the account's mail and read and save what it needs.
 */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getStringExtra(Notifier.EXTRA_THREAD_ID) ?: return
        val action = intent.action.orEmpty()
        if (action.startsWith(REPLY)) {
            reply(context, intent, threadId)
            return
        }
        val tag = if (todoIdOf(threadId) != null) threadId else notificationTag(threadId)
        Notifier.cancel(context, tag)
        if (action.startsWith(SNOOZE)) {
            NotificationActionWorker.snooze(context, threadId, tag, intent.getLongExtra(Notifier.EXTRA_SNOOZE_AT, 0L))
        } else {
            NotificationActionWorker.done(context, threadId, tag)
        }
    }

    private fun reply(context: Context, intent: Intent, threadId: String) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // Answers the reply box at once; the worker then says how it went.
        Notifier.showReplySaving(context, threadId, text)
        ReplyWorker.start(context, threadId, intent.getStringExtra(Notifier.EXTRA_MESSAGE_ID).orEmpty(), text)
    }

    companion object {
        private fun intent(context: Context, action: String, threadId: String) =
            Intent(context, NotificationActions::class.java)
                .setAction(action + threadId)
                .putExtra(Notifier.EXTRA_THREAD_ID, threadId)

        /** The intent the Done button sends. */
        fun donePendingIntent(context: Context, threadId: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0, intent(context, DONE, threadId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** The intent the Snooze button sends, snoozing until [at]. */
        fun snoozePendingIntent(context: Context, threadId: String, at: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent(context, SNOOZE, threadId).putExtra(Notifier.EXTRA_SNOOZE_AT, at),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The intent the Reply button sends with the typed text; mutable, so the system can add it. */
        fun replyPendingIntent(context: Context, threadId: String, messageId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent(context, REPLY, threadId).putExtra(Notifier.EXTRA_MESSAGE_ID, messageId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
