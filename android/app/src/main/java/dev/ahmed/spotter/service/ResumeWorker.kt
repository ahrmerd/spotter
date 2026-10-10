package dev.ahmed.spotter.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

class ResumeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        DownloadNotifier.ensureChannel(applicationContext)
        val notification = DownloadNotifier.idle(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2, notification)
        }
    }

    override suspend fun doWork(): Result {
        DownloadService.start(applicationContext)
        return Result.success()
    }
}
