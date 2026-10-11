package dev.ahmed.spotter

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.room.Room
import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.ErrorRedact
import dev.ahmed.spotter.engine.RotatingFileLog
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.net.NetworkPolicy
import dev.ahmed.spotter.engine.hls.MediaMuxerRemuxer
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.sink.AndroidFreeSpace
import dev.ahmed.spotter.engine.sink.AndroidSinkFactory
import dev.ahmed.spotter.browser.BrowserStore
import dev.ahmed.spotter.engine.net.HttpClients
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
import kotlinx.coroutines.flow.MutableStateFlow
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
        Room.databaseBuilder(this, SpotterDatabase::class.java, "spotter.db")
            .addMigrations(SpotterDatabase.MIGRATION_1_2)
            .build()
    }

    val store: RoomDownloadStore by lazy { RoomDownloadStore(db) }

    val browserStore: BrowserStore by lazy { BrowserStore(db) }

    val httpClient by lazy { HttpClients.create() }

    /** Bumped whenever the ad blocker blocks something, so the browser can refresh its counter. */
    val adBlockTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val adBlocker: dev.ahmed.spotter.browser.adblock.AdBlocker by lazy {
        dev.ahmed.spotter.browser.adblock.AdBlocker(
            openLists = { listOf(assets.open("adblock/easylist.txt"), assets.open("adblock/easyprivacy.txt")) },
            settings = { settingsSnap },
            onBlocked = { adBlockTick.value = adBlockTick.value + 1 },
        )
    }
    val resolve: dev.ahmed.spotter.ui.ResolveCoordinator by lazy { dev.ahmed.spotter.ui.ResolveCoordinator(this) }

    /** Page resolvers (YouTube today). Add more here; the downloader and the UI pick them up from this list. */
    val resolvers: List<dev.ahmed.spotter.resolver.SiteResolver> by lazy {
        listOf(dev.ahmed.spotter.resolver.YoutubeResolver(httpClient))
    }

    /** Cookies come from the WebView at request time and are never stored with a download (see CookieSource). */
    val cookieSource = dev.ahmed.spotter.engine.net.CookieSource { url ->
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    val networkPolicy: NetworkPolicy by lazy { AndroidNetworkPolicy(this) }

    val privateDir: File
        get() = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "Download")

    val fileLog: RotatingFileLog by lazy { RotatingFileLog(File(filesDir, "logs")) }

    // batteryPromptDone=true until Room loads, so the one-shot prompt does not flash on a cold start.
    val settingsFlow = MutableStateFlow(EngineSettings(batteryPromptDone = true))
    @Volatile var settingsSnap: EngineSettings = EngineSettings(batteryPromptDone = true)
        private set

    val manager: DownloadManager by lazy {
        val logger = EngineLog { level, message, error ->
            fileLog.log(level, message, error)
            val safe = ErrorRedact.redact(message)
            when (level) {
                "E" -> Log.e(TAG, safe, error)
                "W" -> Log.w(TAG, safe, error)
                else -> Log.i(TAG, safe)
            }
        }
        DownloadManager(
            store = store,
            outputDir = privateDir,
            scope = appScope,
            client = httpClient,
            networkPolicy = networkPolicy,
            logger = logger,
            sinkFactory = AndroidSinkFactory(contentResolver, privateDir) { settingsSnap.saveFolderUri },
            hlsRemuxer = MediaMuxerRemuxer(),
            cookies = cookieSource,
            freeSpace = AndroidFreeSpace(this),
            resolvers = resolvers,
        )
    }

    suspend fun applySettings(settings: EngineSettings) {
        manager.setSettings(settings)
        refreshSettings()
    }

    suspend fun refreshSettings() {
        val loaded = store.loadSettings()
        settingsSnap = loaded
        settingsFlow.value = loaded
        BootReceiver.setEnabled(this, loaded.resumeOnBoot)
        dev.ahmed.spotter.telemetry.UsagePing.apply(this, loaded.usagePing)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager
        adBlocker.loadAsync()
        NetworkMonitor.register(this)
        appScope.launch {
            refreshSettings()
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
