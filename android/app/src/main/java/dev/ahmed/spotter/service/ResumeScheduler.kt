package dev.ahmed.spotter.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.ahmed.spotter.engine.model.EngineSettings

object ResumeScheduler {
    const val UNIQUE_RESUME = "spotter-resume"
    const val UNIQUE_NETWORK = "spotter-resume-network"

    fun enqueueNow(context: Context) {
        // Regular work, not expedited dataSync FGS: Android 15 refuses that from the background (A2).
        val req = OneTimeWorkRequestBuilder<ResumeWorker>().build()
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
