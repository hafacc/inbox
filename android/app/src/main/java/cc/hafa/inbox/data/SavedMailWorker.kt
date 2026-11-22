package cc.hafa.inbox.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cc.hafa.inbox.InboxApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// How often the saved copy syncs while the app is closed.
private const val SYNC_EVERY_HOURS = 1L

private const val FILL = "fill"

/**
 * Keeps the saved copy of mail ([cc.hafa.inbox.mail.OfflineCopy]) in step while the app is closed:
 * its first fill once on a network that isn't metered, and a sync every hour on any network.
 */
class SavedMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext as InboxApplication
        // Signed out: the account's saved copy went with it.
        if (app.authManager?.currentUser?.value == null) return@withContext Result.success()
        val session = app.awaitSession()
        val whole = session?.syncSavedMail() == true
        // The hourly sync tries again at its next run; the first fill until it completes.
        if (whole || !inputData.getBoolean(FILL, false)) Result.success() else Result.retry()
    }

    companion object {
        private const val SYNC_NAME = "sync-saved-mail"
        private const val FILL_NAME = "fill-saved-mail"

        /** Ask WorkManager to sync the saved copy every hour while online. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SavedMailWorker>(SYNC_EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Ask WorkManager to fill the saved copy once the device is on a network that isn't metered. */
        fun scheduleFill(context: Context) {
            val request = OneTimeWorkRequestBuilder<SavedMailWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInputData(workDataOf(FILL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FILL_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
