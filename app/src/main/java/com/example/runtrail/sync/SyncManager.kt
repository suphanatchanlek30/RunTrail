package com.example.runtrail.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.runtrail.App
import java.util.concurrent.TimeUnit

/** Schedules sync with WorkManager; work runs only when a network is available. */
object SyncManager {

    private const val ONE_TIME = "sync_now"
    private const val PERIODIC = "sync_periodic"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Requests a sync, e.g. after a run is saved, edited or deleted. Queues another round if one is running. */
    fun requestSync(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Periodic sync every 6 hours to pick up changes from other devices. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Cancels all sync work (sign-out). */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = when (App.services.sync.sync()) {
        is SyncEngine.Outcome.Done, SyncEngine.Outcome.NotLoggedIn, SyncEngine.Outcome.SessionExpired -> Result.success()
        is SyncEngine.Outcome.Retry -> if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}
