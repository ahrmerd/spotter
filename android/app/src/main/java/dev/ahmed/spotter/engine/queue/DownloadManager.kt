package dev.ahmed.spotter.engine.queue

import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.engine.http.DownloadOutcome
import dev.ahmed.spotter.engine.http.HttpDownloader
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.net.NetworkPolicy
import dev.ahmed.spotter.engine.net.TokenBucket
import dev.ahmed.spotter.engine.sink.FileSinkFactory
import dev.ahmed.spotter.engine.sink.PrivateFileSink
import dev.ahmed.spotter.engine.store.DownloadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DownloadManager(
    private val store: DownloadStore,
    private val outputDir: File,
    private val scope: CoroutineScope,
    client: OkHttpClient = HttpClients.create(),
    private val networkPolicy: NetworkPolicy = NetworkPolicy.ALWAYS,
    private val logger: EngineLog = EngineLog.NONE,
    private val tokenBucket: TokenBucket = TokenBucket(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    sinkFactory: FileSinkFactory? = null,
) {
    private val connectionLimiter = AtomicReference(Semaphore(EngineSettings.DEFAULT_MAX_CONNECTIONS))
    private val downloader = HttpDownloader(
        client = client,
        store = store,
        logger = logger,
        tokenBucket = tokenBucket,
        connectionLimiter = connectionLimiter,
        ioDispatcher = ioDispatcher,
    )
    private val sinks = sinkFactory ?: FileSinkFactory { filename, resumePath ->
        PrivateFileSink.forName(outputDir, filename, resumePath)
    }
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stop = ConcurrentHashMap<Long, StopReason>()
    // resume() arrived while a pause was still being processed: re-queue once the job has actually stopped.
    private val resumeAfterStop = ConcurrentHashMap.newKeySet<Long>()
    private val lock = Mutex()
    private val nameLock = Mutex()
    private val running = AtomicInteger(0)
    private val peak = AtomicInteger(0)
    @Volatile private var shuttingDown = false
    @Volatile private var connectionCap = EngineSettings.DEFAULT_MAX_CONNECTIONS

    val peakRunning: Int get() = peak.get()
    val currentRunning: Int get() = running.get()

    fun observe(): Flow<List<DownloadRecord>> = store.observeAll()

    init {
        scope.launch {
            recover()
            pump()
        }
    }

    suspend fun enqueue(
        url: String,
        headers: Map<String, String> = emptyMap(),
        filename: String? = null,
    ): Long {
        val id = store.insert(
            DownloadRecord(
                url = url,
                headers = headers,
                filename = filename?.let { Filenames.sanitize(it) }.orEmpty(),
                state = DownloadState.QUEUED,
                createdAt = System.currentTimeMillis(),
            ),
        )
        pump()
        return id
    }

    suspend fun pause(id: Long) {
        val job = jobs[id]
        if (job != null) {
            resumeAfterStop.remove(id)
            stop[id] = StopReason.Pause
            job.cancel()
        }
        val rec = store.get(id) ?: return
        // QUEUED, or RUNNING with no live job (stale state), becomes PAUSED here; a live job does it in its cancel handler.
        if (rec.state == DownloadState.QUEUED || (rec.state == DownloadState.RUNNING && job == null)) {
            store.update(rec.copy(state = DownloadState.PAUSED))
        }
    }

    suspend fun resume(id: Long) {
        val rec = store.get(id) ?: return
        when (rec.state) {
            DownloadState.PAUSED, DownloadState.ERROR -> {
                store.update(rec.copy(state = DownloadState.QUEUED, error = null))
                pump()
            }
            DownloadState.RUNNING -> {
                if (jobs.containsKey(id)) {
                    if (stop[id] == StopReason.Pause) resumeAfterStop.add(id) else pump()
                } else {
                    store.update(rec.copy(state = DownloadState.QUEUED)) // stale RUNNING with no job: re-queue
                    pump()
                }
            }
            DownloadState.QUEUED -> pump()
            DownloadState.DONE, DownloadState.NEEDS_REFRESH -> Unit
        }
    }

    suspend fun setSettings(settings: EngineSettings) {
        store.setSetting(EngineSettings.KEY_MAX_CONCURRENT, settings.maxConcurrentDownloads.toString())
        store.setSetting(EngineSettings.KEY_CONNECTIONS_PER_FILE, settings.connectionsPerFile.toString())
        store.setSetting(EngineSettings.KEY_MAX_TOTAL_CONNECTIONS, settings.maxTotalConnections.toString())
        store.setSetting(EngineSettings.KEY_SPEED_LIMIT_BPS, settings.speedLimitBps.toString())
        store.setSetting(EngineSettings.KEY_WIFI_ONLY, settings.wifiOnly.toString())
        tokenBucket.setRate(settings.speedLimitBps)
        applyConnectionCap(settings.maxTotalConnections)
        pump()
    }

    // Holders of the old semaphore release into it, so the cap converges as in-flight requests finish.
    private fun applyConnectionCap(cap: Int) {
        val want = cap.coerceIn(1, 64)
        if (connectionCap != want) {
            connectionCap = want
            connectionLimiter.set(Semaphore(want))
        }
    }

    fun shutdown() {
        shuttingDown = true
        stop.clear()
        jobs.values.forEach { it.cancel() }
    }

    private suspend fun recover() {
        applyConnectionCap(store.loadSettings().maxTotalConnections)
        for (rec in store.getAll()) {
            if (rec.state == DownloadState.RUNNING) {
                store.update(rec.copy(state = DownloadState.QUEUED))
            }
        }
    }

    private fun pump() {
        if (shuttingDown) return
        scope.launch {
            if (shuttingDown) return@launch
            lock.withLock {
                val settings = store.loadSettings()
                tokenBucket.setRate(settings.speedLimitBps)
                if (settings.wifiOnly && !networkPolicy.canDownload()) return@withLock
                val max = settings.maxConcurrentCapped()
                val queued = store.getAll().filter { it.state == DownloadState.QUEUED }
                    .sortedBy { it.createdAt }
                for (item in queued) {
                    if (jobs.size >= max) break
                    if (settings.wifiOnly && !networkPolicy.canDownload()) break
                    startJob(item.id)
                }
            }
        }
    }

    private suspend fun startJob(id: Long) {
        if (jobs.containsKey(id)) return
        val rec = store.get(id) ?: return
        store.update(rec.copy(state = DownloadState.RUNNING, error = null))
        val n = running.incrementAndGet()
        peak.updateAndGet { maxOf(it, n) }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val settings = store.loadSettings()
                val outcome = downloader.download(
                    downloadId = id,
                    sinkFactory = sinks,
                    connections = settings.connectionsPerFileCapped(),
                    allocateName = { suggested -> allocateName(id, suggested) },
                )
                applyOutcome(id, outcome)
            } catch (e: CancellationException) {
                val reason = stop.remove(id) ?: StopReason.Kill
                // The coroutine is already cancelled: store calls (Room uses withContext) would throw straight away and
                // leave the record RUNNING forever. Finish the bookkeeping in a non-cancellable block.
                withContext(NonCancellable) {
                    val rec2 = store.get(id)
                    if (rec2 != null && rec2.state == DownloadState.RUNNING) {
                        when (reason) {
                            StopReason.Pause -> store.update(
                                rec2.copy(state = if (resumeAfterStop.remove(id)) DownloadState.QUEUED else DownloadState.PAUSED),
                            )
                            StopReason.Kill -> store.update(rec2.copy(state = DownloadState.RUNNING))
                        }
                    }
                }
                if (reason == StopReason.Pause) {
                    logger.info("paused $id")
                }
            } catch (e: Exception) {
                logger.error("job failed $id", e)
                val rec2 = store.get(id)
                if (rec2 != null && rec2.state == DownloadState.RUNNING) {
                    store.update(rec2.copy(state = DownloadState.ERROR, error = e.message ?: e.javaClass.simpleName))
                }
            } finally {
                running.decrementAndGet()
                jobs.remove(id, job)
                if (!shuttingDown) pump()
            }
        }
        jobs[id] = job
        peak.updateAndGet { maxOf(it, jobs.size) }
        job.start()
    }

    private suspend fun applyOutcome(id: Long, outcome: DownloadOutcome) {
        val rec = store.get(id) ?: return
        when (outcome) {
            DownloadOutcome.Completed -> store.update(rec.copy(state = DownloadState.DONE, error = null))
            is DownloadOutcome.NeedsRefresh -> store.update(
                rec.copy(state = DownloadState.NEEDS_REFRESH, error = outcome.message),
            )
            is DownloadOutcome.Failed -> store.update(
                rec.copy(state = DownloadState.ERROR, error = outcome.error),
            )
        }
    }

    private suspend fun allocateName(downloadId: Long, suggested: String): String = nameLock.withLock {
        val existing = store.getAll().map { it.filename }.filter { it.isNotEmpty() }.toHashSet()
        val name = Filenames.unique(suggested) { candidate ->
            candidate in existing ||
                File(outputDir, candidate).exists() ||
                File(outputDir, "$candidate.part").exists()
        }
        val rec = store.get(downloadId)
        if (rec != null) {
            store.update(rec.copy(filename = name))
        }
        name
    }

    private enum class StopReason { Pause, Kill }
}
