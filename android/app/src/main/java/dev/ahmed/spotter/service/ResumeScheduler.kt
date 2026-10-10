package dev.ahmed.spotter.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.ahmed.spotter.engine.model.EngineSettings

object ResumeScheduler {
    const val UNIQUE_RESUME = "spotter-resume"
    const val UNIQUE_NETWORK = "spotter-resume-network"

    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<ResumeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_RESUME, ExistingWorkPolicy.REPLACE, req)
    }

    fun enqueueWhenConnected(context: Context, wifiOnly: Boolean) {
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<ResumeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NETWORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelNetworkWait(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NETWORK)
    }

    fun wifiOnlyFrom(settings: EngineSettings): Boolean = settings.wifiOnly
}
