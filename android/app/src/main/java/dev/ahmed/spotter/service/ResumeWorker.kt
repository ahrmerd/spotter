package dev.ahmed.spotter.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.engine.model.DownloadState

class ResumeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? SpotterApp ?: return Result.success()
        val hasWork = app.store.getAll().any {
            it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
        }
        if (!hasWork) return Result.success()
        DownloadService.start(applicationContext)
        return Result.success()
    }
}
