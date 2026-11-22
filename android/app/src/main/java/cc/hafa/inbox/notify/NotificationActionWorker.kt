package cc.hafa.inbox.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.core.notify.todoIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KEY_ACTION = "action"
private const val KEY_THREAD = "threadId"
private const val KEY_TAG = "tag"
private const val KEY_SNOOZE_AT = "snoozeAt"
private const val SNOOZE = "snooze"
private const val DONE = "done"

/**
 * Carries out a notification's Done or Snooze, app open or not, with the time a broadcast receiver
 * lacks for a cold start and Gmail and Firestore calls: a thread's through the offline queue (and
 * Firestore, for a snooze), a todo's straight to Firestore. When it can't, a notification says so.
 */
class NotificationActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val threadId = inputData.getString(KEY_THREAD).orEmpty()
        val tag = inputData.getString(KEY_TAG).orEmpty()
        val session = (applicationContext as InboxApplication).awaitSession()
        val todoId = todoIdOf(threadId)
        when {
            inputData.getString(KEY_ACTION) == SNOOZE -> {
                val at = inputData.getLong(KEY_SNOOZE_AT, 0L)
                if (session?.snoozeFromNotification(threadId, at) != true) Notifier.showSnoozeFailed(applicationContext, threadId, tag)
            }
            todoId != null -> if (session?.todoDoneFromNotification(todoId) != true) Notifier.showTodoDoneFailed(applicationContext, tag)
            session == null -> Notifier.showDoneFailed(applicationContext, threadId, tag)
            else -> session.doneFromNotification(threadId)
        }
        Result.success()
    }

    companion object {
        /** Mark [threadId] (a thread, or a todo's item id) done, whose notification was [tag]. */
        fun done(context: Context, threadId: String, tag: String) = start(context, workDataOf(KEY_ACTION to DONE, KEY_THREAD to threadId, KEY_TAG to tag))

        /** Snooze thread [threadId] until [at], whose notification was [tag]. */
        fun snooze(context: Context, threadId: String, tag: String, at: Long) =
            start(context, workDataOf(KEY_ACTION to SNOOZE, KEY_THREAD to threadId, KEY_TAG to tag, KEY_SNOOZE_AT to at))

        private fun start(context: Context, data: Data) {
            val request = OneTimeWorkRequestBuilder<NotificationActionWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
