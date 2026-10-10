package dev.ahmed.spotter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.ahmed.spotter.MainActivity
import dev.ahmed.spotter.engine.hls.HlsProgress
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState

object DownloadNotifier {
    const val CHANNEL_ID = "spotter_downloads"
    const val WAITING_CHANNEL_ID = "spotter_waiting"
    const val NOTIFICATION_ID = 1
    const val WAITING_ID = 3

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Download progress"
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(WAITING_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WAITING_CHANNEL_ID,
                    "Downloads waiting",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Tap to resume downloads the system would not start in the background"
                },
            )
        }
    }

    fun build(context: Context, items: List<DownloadRecord>): Notification {
        ensureChannel(context)
        val active = items.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
        val running = active.filter { it.state == DownloadState.RUNNING }
        val downloaded = running.sumOf { it.downloaded }
        val total = running.sumOf { it.size }
        val hlsRunning = running.filter { HlsProgress.isSegmentMode(it) }
        val fileRunning = running.filter { !HlsProgress.isSegmentMode(it) }
        val title = when {
            running.isEmpty() && active.isNotEmpty() -> "Waiting to download"
            running.size == 1 -> running[0].filename.ifBlank { "Downloading" }
            running.isNotEmpty() -> "${running.size} downloading"
            else -> "Spotter"
        }
        val text = when {
            running.isEmpty() -> "${active.size} queued"
            running.size == 1 && hlsRunning.size == 1 && hlsRunning[0].size > 0 ->
                HlsProgress.line(hlsRunning[0].downloaded, hlsRunning[0].size)
            fileRunning.isEmpty() && hlsRunning.isNotEmpty() && hlsRunning.all { it.size > 0 } -> {
                val d = hlsRunning.sumOf { it.downloaded }
                val t = hlsRunning.sumOf { it.size }
                HlsProgress.line(d, t)
            }
            fileRunning.isNotEmpty() && fileRunning.sumOf { it.size } > 0 -> {
                val d = fileRunning.sumOf { it.downloaded }
                val t = fileRunning.sumOf { it.size }
                "${formatBytes(d)} / ${formatBytes(t)}"
            }
            downloaded > 0 && hlsRunning.isEmpty() -> "${formatBytes(downloaded)} so far"
            else -> "Starting…"
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
        val pause = serviceAction(context, DownloadService.ACTION_PAUSE_ALL, 1)
        val cancel = serviceAction(context, DownloadService.ACTION_CANCEL_RUNNING, 2)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Pause all", pause)
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (total > 0) {
            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun waiting(context: Context): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
        return NotificationCompat.Builder(context, WAITING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloads waiting")
            .setContentText("Tap to resume")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    fun showWaiting(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(WAITING_ID, waiting(context))
    }

    fun cancelWaiting(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(WAITING_ID)
    }

    fun idle(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Spotter")
            .setContentText("Idle")
            .setSilent(true)
            .setOngoing(false)
            .build()
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutable(),
        )
    }

    private fun immutable(): Int = PendingIntent.FLAG_IMMUTABLE

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
