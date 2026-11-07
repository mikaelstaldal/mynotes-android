package nu.staldal.mynotes.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import nu.staldal.mynotes.BuildConfig
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.preferences.UserPreferences

private const val LOGTAG = "SyncWorker"

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val prefs: UserPreferences,
    private val repository: NoteRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val config = prefs.serverConfig.first()
        if (!config.isConfigured) return Result.success()

        return try {
            val notifications = repository.syncPendingChanges()
            if (BuildConfig.DEBUG) notifications.forEach { Log.i(LOGTAG, it) }
            repository.refreshNotes()
            repository.refreshTags()
            repository.collectOrphanedArtifacts()
            Result.success()
        } catch (e: Exception) {
            Log.w(LOGTAG, "Sync failed, will retry: $e")
            Result.retry()
        }
    }

    companion object {
        private const val ONE_TIME_WORK_NAME = "sync_once"
        private const val PERIODIC_WORK_NAME = "sync_periodic"

        fun enqueueOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancels any scheduled or running sync work, e.g. after the user signs out. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(ONE_TIME_WORK_NAME)
                cancelUniqueWork(PERIODIC_WORK_NAME)
            }
        }

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
