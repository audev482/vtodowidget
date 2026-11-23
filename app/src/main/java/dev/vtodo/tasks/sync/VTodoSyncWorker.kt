package dev.vtodo.tasks.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.vtodo.tasks.VTodoApp
import dev.vtodo.tasks.data.SyncSchedulerPort
import java.util.concurrent.TimeUnit

class VTodoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VTodoApp ?: return Result.success()
        app.repository.initialize()
        if (!app.repository.state.value.config.isConfigured) {
            return Result.success()
        }
        return app.repository.syncAll().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "vtodo_periodic_caldav_sync"
    }
}

class WorkManagerSyncScheduler(
    private val context: Context
) : SyncSchedulerPort {

    override fun schedulePeriodicSync(intervalMinutes: Int) {
        val workManager = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (intervalMinutes <= 0) {
            workManager.cancelUniqueWork(VTodoSyncWorker.UNIQUE_WORK_NAME)
            return
        }
        val effectiveMinutes = intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<VTodoSyncWorker>(effectiveMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            VTodoSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
