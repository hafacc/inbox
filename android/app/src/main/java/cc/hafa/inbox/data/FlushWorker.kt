package cc.hafa.inbox.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.hafa.inbox.InboxApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends label changes made offline once the network is back, even if the app was closed. */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext as InboxApplication
        // Signed out: the account's queue went with it.
        if (app.authManager?.currentUser?.value == null) return@withContext Result.success()
        val session = app.awaitSession()
        if (session != null && session.flush()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "flush-label-changes"

        /** Ask WorkManager to send queued changes when the device is online. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
