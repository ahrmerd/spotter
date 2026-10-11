package dev.ahmed.spotter.telemetry

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ahmed.spotter.SpotterApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The anonymous "I am still installed" ping, once a day. It sends only: a random install id (not a device
 * identifier, new after a reinstall), app version, Android version and CPU type. No IP is stored by the server, no
 * model, no account, nothing about downloads or browsing. The user can switch it off in Settings.
 */
object UsagePing {
    const val ENDPOINT = "https://spotter.hanscadi.cloud/api/ping.php"
    private const val UNIQUE = "spotter-usage-ping"
    private const val PREFS = "usage"
    private const val KEY_ID = "install_id"

    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    fun payload(id: String, versionName: String, versionCode: Long, sdk: Int, abi: String): String =
        buildJsonObject {
            put("id", id)
            put("v", versionName)
            put("c", versionCode)
            put("sdk", sdk)
            put("abi", abi)
        }.toString()

    /** Turns the daily job on or off. Safe to call as often as settings change. */
    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE)
            return
        }
        val req = PeriodicWorkRequestBuilder<UsagePingWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    @Suppress("DEPRECATION")
    fun send(context: Context, client: OkHttpClient, endpoint: String = ENDPOINT): Boolean {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val body = payload(installId(context), info.versionName.orEmpty(), code, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        val req = Request.Builder().url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Spotter/${info.versionName}")
            .build()
        return client.newCall(req).execute().use { it.isSuccessful }
    }
}

class UsagePingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SpotterApp
        if (!app.settingsSnap.usagePing) return Result.success()
        return try {
            if (UsagePing.send(applicationContext, app.httpClient)) Result.success() else Result.retry()
        } catch (e: java.io.IOException) {
            Result.retry()
        }
    }
}
