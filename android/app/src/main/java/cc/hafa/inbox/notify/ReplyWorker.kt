package cc.hafa.inbox.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.data.FlushWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ReplyWorker"
// How long reading the thread and saving the send may take before the reply is given up.
private const val REPLY_WAIT_MS = 30_000L
private const val KEY_THREAD = "threadId"
private const val KEY_MESSAGE = "messageId"
private const val KEY_TEXT = "text"

/**
 * Sends a reply typed into a notification, app open or not: reads the thread, saves the reply to
 * go through the drafts and outbox with the undo delay (docs/compose-contract.md), and shows it on
 * its way with Undo; when it can't, a notification says so and opens a reply with the text.
 */
class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val threadId = inputData.getString(KEY_THREAD).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE).orEmpty()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val app = applicationContext as InboxApplication
        val session = app.awaitSession()
        val ids = try {
            session?.let { withTimeoutOrNull(REPLY_WAIT_MS) { it.replyInlineFromNotification(threadId, messageId, text) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "reply from a notification failed", error)
            null
        }
        if (session == null || ids == null) {
            Notifier.showReplyFailed(applicationContext, threadId, text)
        } else {
            val (outboxId, localId) = ids
            Notifier.showReplySending(applicationContext, threadId, text, outboxId, localId)
            if (!session.flush()) FlushWorker.schedule(applicationContext)
        }
        Result.success()
    }

    companion object {
        /** Send [text] as a reply to [threadId], whose notification was about [messageId]. */
        fun start(context: Context, threadId: String, messageId: String, text: String) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(workDataOf(KEY_THREAD to threadId, KEY_MESSAGE to messageId, KEY_TEXT to text))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
