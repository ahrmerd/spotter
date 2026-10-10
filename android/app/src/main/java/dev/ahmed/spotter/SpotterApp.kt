package dev.ahmed.spotter

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.room.Room
import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.NetworkPolicy
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.sink.AndroidSinkFactory
import dev.ahmed.spotter.engine.store.RoomDownloadStore
import dev.ahmed.spotter.engine.store.SpotterDatabase
import dev.ahmed.spotter.service.AndroidNetworkPolicy
import dev.ahmed.spotter.service.BootReceiver
import dev.ahmed.spotter.service.DownloadService
import dev.ahmed.spotter.service.NetworkMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class SpotterApp : Application() {
    private val crashLog = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught in appScope", e)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashLog)

    val db: SpotterDatabase by lazy {
        Room.databaseBuilder(this, SpotterDatabase::class.java, "spotter.db").build()
    }

    val store: RoomDownloadStore by lazy { RoomDownloadStore(db) }

    val networkPolicy: NetworkPolicy by lazy { AndroidNetworkPolicy(this) }

    val privateDir: File
        get() = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "Download")

    val manager: DownloadManager by lazy {
        DownloadManager(
            store = store,
            outputDir = privateDir,
            scope = appScope,
            networkPolicy = networkPolicy,
            logger = EngineLog { level, message, error ->
                when (level) {
                    "E" -> Log.e(TAG, message, error)
                    "W" -> Log.w(TAG, message, error)
                    else -> Log.i(TAG, message)
                }
            },
            sinkFactory = AndroidSinkFactory(contentResolver, privateDir),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager
        NetworkMonitor.register(this)
        appScope.launch {
            BootReceiver.setEnabled(this@SpotterApp, store.loadSettings().resumeOnBoot)
        }
        appScope.launch {
            store.observeAll()
                .map { list -> list.any { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING } }
                .distinctUntilChanged()
                .collect { hasWork ->
                    if (hasWork) DownloadService.start(this@SpotterApp)
                }
        }
    }

    companion object {
        private const val TAG = "Spotter"
        @Volatile lateinit var instance: SpotterApp
            private set
    }
}
