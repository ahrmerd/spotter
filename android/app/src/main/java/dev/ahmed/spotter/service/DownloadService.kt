package dev.ahmed.spotter.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.engine.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    @Volatile private var startedFg = false
    private var idleStop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.ensureChannel(this)
        promote(emptyList())
        val app = app() ?: return
        scope.launch {
            app.store.observeAll()
                .distinctUntilChanged()
                .collect { items ->
                    val active = items.any {
                        it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
                    }
                    if (active) {
                        idleStop?.cancel()
                        idleStop = null
                        promote(items)
                    } else {
                        // First Room emission can be empty while recover()/insert is still in flight.
                        idleStop?.cancel()
                        idleStop = scope.launch {
                            delay(2_000)
                            stopIdle()
                        }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(currentItems())
        val app = app()
        when (intent?.action) {
            ACTION_PAUSE_ALL -> scope.launch(Dispatchers.IO) { app?.manager?.pauseAll() }
            ACTION_CANCEL_RUNNING -> scope.launch(Dispatchers.IO) { app?.manager?.cancelRunning() }
            ACTION_RESUME, null -> scope.launch(Dispatchers.IO) {
                app?.manager?.notifyNetworkChanged()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        Log.w(TAG, "dataSync timeout; pausing and scheduling WorkManager resume")
        val app = app()
        scope.launch(Dispatchers.IO) {
            try {
                app?.manager?.parkRunning()
            } finally {
                ResumeScheduler.enqueueNow(this@DownloadService)
                stopIdle()
            }
        }
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun promote(items: List<dev.ahmed.spotter.engine.model.DownloadRecord>) {
        val notification = DownloadNotifier.build(this, items)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(DownloadNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DownloadNotifier.NOTIFICATION_ID, notification)
        }
        startedFg = true
        DownloadNotifier.cancelWaiting(this)
    }

    private fun stopIdle() {
        if (startedFg) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun currentItems() = emptyList<dev.ahmed.spotter.engine.model.DownloadRecord>()

    private fun app(): SpotterApp? = application as? SpotterApp

    companion object {
        const val ACTION_PAUSE_ALL = "dev.ahmed.spotter.action.PAUSE_ALL"
        const val ACTION_CANCEL_RUNNING = "dev.ahmed.spotter.action.CANCEL_RUNNING"
        const val ACTION_RESUME = "dev.ahmed.spotter.action.RESUME"
        private const val TAG = "Spotter"

        fun notifyWaiting(context: Context) {
            val app = context.applicationContext as? SpotterApp ?: return
            app.appScope.launch(Dispatchers.IO) {
                val has = app.store.getAll().any {
                    it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING
                }
                if (has) DownloadNotifier.showWaiting(app)
            }
        }

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // Android 12+ refuses FGS from many background contexts. Leave rows QUEUED
                // and tell the user to open the app (A2).
                Log.w(TAG, "DownloadService start not allowed from this context", e)
                notifyWaiting(app)
            }
        }
    }
}
