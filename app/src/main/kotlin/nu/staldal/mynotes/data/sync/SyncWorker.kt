package nu.staldal.mynotes.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import nu.staldal.mynotes.data.ArtifactRepository
import nu.staldal.mynotes.data.NoteRepository
import nu.staldal.mynotes.data.api.RetrofitClient
import nu.staldal.mynotes.data.local.AppDatabase
import nu.staldal.mynotes.data.preferences.UserPreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private const val LOGTAG = "SyncWorker"

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferences(applicationContext)
        val config = prefs.serverConfig.first()
        if (!config.isConfigured) return Result.success()

        val database = AppDatabase.getInstance(applicationContext)
        val artifactRepository = ArtifactRepository(applicationContext, database) {
            RetrofitClient.getApiService(config.baseUrl, config.username, config.password)
        }
        val repository = NoteRepository(
            database = database,
            artifactRepository = artifactRepository,
            apiProvider = { RetrofitClient.getApiService(config.baseUrl, config.username, config.password) },
            baseUrlProvider = { config.baseUrl },
        )

        return try {
            val notifications = repository.syncPendingChanges()
            notifications.forEach { Log.i(LOGTAG, it) }
            repository.refreshNotes()
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
