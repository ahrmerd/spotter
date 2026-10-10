package dev.ahmed.spotter.engine.queue

import dev.ahmed.spotter.engine.EngineErrors
import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.engine.hls.HlsDownloader
import dev.ahmed.spotter.engine.hls.HlsPlaylist
import dev.ahmed.spotter.engine.hls.HlsRemuxer
import dev.ahmed.spotter.engine.hls.KeepTsRemuxer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
    hlsRemuxer: HlsRemuxer = KeepTsRemuxer,
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
    private val hlsDownloader = HlsDownloader(
        client = client,
        store = store,
        logger = logger,
        tokenBucket = tokenBucket,
        connectionLimiter = connectionLimiter,
        ioDispatcher = ioDispatcher,
        remuxer = hlsRemuxer,
    )
    private val sinks = sinkFactory ?: FileSinkFactory { filename, resumePath, _ ->
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

    suspend fun pauseAll() {
        for (rec in store.getAll()) {
            if (rec.state == DownloadState.QUEUED || rec.state == DownloadState.RUNNING) {
                pause(rec.id)
            }
        }
    }

    /**
     * Abort an unfinished download: stop the job, drop the partial file, remove the row.
     * Finished files are left alone; use [remove] with deleteFile for those.
     */
    suspend fun cancel(id: Long) {
        val rec = store.get(id) ?: return
        if (rec.state == DownloadState.DONE) return
        resumeAfterStop.remove(id)
        val job = jobs[id]
        if (job != null) {
            stop[id] = StopReason.Cancel
            job.cancel()
            job.join()
        }
        withContext(NonCancellable) {
            val latest = store.get(id) ?: return@withContext
            if (latest.state == DownloadState.DONE) return@withContext
            discardPartial(latest)
            store.delete(id)
        }
    }

    suspend fun cancelRunning() {
        for (rec in store.getAll()) {
            if (rec.state == DownloadState.QUEUED || rec.state == DownloadState.RUNNING) {
                cancel(rec.id)
            }
        }
    }

    /**
     * Remove the row. Partial files are always dropped (they are ours). A finished user file is
     * deleted only when [deleteFile] is true.
     */
    suspend fun remove(id: Long, deleteFile: Boolean) {
        val rec = store.get(id) ?: return
        val finished = rec.state == DownloadState.DONE
        resumeAfterStop.remove(id)
        val job = jobs[id]
        if (job != null) {
            stop[id] = StopReason.Cancel
            job.cancel()
            job.join()
        }
        withContext(NonCancellable) {
            val latest = store.get(id) ?: return@withContext
            val nowFinished = latest.state == DownloadState.DONE
            if (!nowFinished) discardPartial(latest)
            if (deleteFile && (finished || nowFinished)) deleteFinished(latest)
            store.delete(id)
        }
    }

    suspend fun deleteDownloadedFile(id: Long) {
        val rec = store.get(id) ?: return
        if (rec.state == DownloadState.RUNNING && jobs.containsKey(id)) return
        if (rec.state == DownloadState.DONE) {
            deleteFinished(rec)
            store.update(rec.copy(outputPath = ""))
        } else {
            discardPartial(rec)
        }
    }

    suspend fun retry(id: Long) {
        val rec = store.get(id) ?: return
        when (rec.state) {
            DownloadState.ERROR, DownloadState.NEEDS_REFRESH, DownloadState.PAUSED -> {
                store.update(rec.copy(state = DownloadState.QUEUED, error = null))
                pump()
            }
            else -> resume(id)
        }
    }

    suspend fun rename(id: Long, newName: String): String = nameLock.withLock {
        val rec = store.get(id) ?: return@withLock ""
        if (rec.state == DownloadState.RUNNING) return@withLock rec.filename
        val sanitized = Filenames.ensureExtension(Filenames.sanitize(newName), rec.mimeType, rec.url)
        val name = uniqueFilename(sanitized, exceptId = id)
        val newPath = sinks.rename(rec.outputPath, name)
        store.update(
            rec.copy(
                filename = name,
                outputPath = newPath.ifEmpty { rec.outputPath },
            ),
        )
        name
    }

    /**
     * Called from the network callback. Airplane / lost network, or Wi-Fi-only on cellular,
     * yields running jobs to QUEUED so they resume without a user tap when the allowed
     * network returns.
     */
    suspend fun notifyNetworkChanged() {
        val settings = store.loadSettings()
        if (networkBlocked(settings)) {
            yieldRunningToQueue()
        } else {
            pump()
        }
    }

    suspend fun setSettings(settings: EngineSettings) {
        store.setSetting(EngineSettings.KEY_MAX_CONCURRENT, settings.maxConcurrentDownloads.toString())
        store.setSetting(EngineSettings.KEY_CONNECTIONS_PER_FILE, settings.connectionsPerFile.toString())
        store.setSetting(EngineSettings.KEY_MAX_TOTAL_CONNECTIONS, settings.maxTotalConnections.toString())
        store.setSetting(EngineSettings.KEY_SPEED_LIMIT_BPS, settings.speedLimitBps.toString())
        store.setSetting(EngineSettings.KEY_WIFI_ONLY, settings.wifiOnly.toString())
        store.setSetting(EngineSettings.KEY_RESUME_ON_BOOT, settings.resumeOnBoot.toString())
        tokenBucket.setRate(settings.speedLimitBps)
        applyConnectionCap(settings.maxTotalConnections)
        if (networkBlocked(settings)) {
            yieldRunningToQueue()
        } else {
            pump()
        }
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
                if (networkBlocked(settings)) return@withLock
                val max = settings.maxConcurrentCapped()
                val queued = store.getAll().filter { it.state == DownloadState.QUEUED }
                    .sortedBy { it.createdAt }
                for (item in queued) {
                    if (jobs.size >= max) break
                    if (networkBlocked(settings)) break
                    startJob(item.id)
                }
            }
        }
    }

    private fun networkBlocked(settings: EngineSettings): Boolean {
        if (!networkPolicy.isOnline()) return true
        return settings.wifiOnly && !networkPolicy.canDownload()
    }

    /** Yield running jobs to QUEUED (system timeout / network). They resume on the next pump. */
    suspend fun parkRunning() = yieldRunningToQueue()

    /**
     * Stop running jobs as QUEUED (not user-PAUSED) so a later pump resumes them.
     */
    private suspend fun yieldRunningToQueue() {
        val live = jobs.keys.mapNotNull { id -> jobs[id]?.let { id to it } }
        for ((id, job) in live) {
            resumeAfterStop.remove(id)
            stop[id] = StopReason.Network
            job.cancel()
        }
        for ((_, job) in live) {
            job.join()
        }
        for (rec in store.getAll()) {
            if (rec.state == DownloadState.RUNNING && !jobs.containsKey(rec.id)) {
                store.update(rec.copy(state = DownloadState.QUEUED))
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
                val current = store.get(id) ?: return@launch
                val outcome = if (HlsPlaylist.looksLikeHls(current.url, current.headers, current.mimeType)) {
                    hlsDownloader.download(
                        downloadId = id,
                        sinkFactory = sinks,
                        workRoot = outputDir,
                        allocateName = { suggested -> allocateName(id, suggested) },
                    )
                } else {
                    downloader.download(
                        downloadId = id,
                        sinkFactory = sinks,
                        connections = settings.connectionsPerFileCapped(),
                        allocateName = { suggested -> allocateName(id, suggested) },
                    )
                }
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
                            StopReason.Network -> store.update(rec2.copy(state = DownloadState.QUEUED))
                            StopReason.Cancel -> Unit // caller deletes the row
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
                    store.update(rec2.copy(state = DownloadState.ERROR, error = EngineErrors.userMessage(e.message ?: e.javaClass.simpleName)))
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
            DownloadOutcome.Completed -> store.update(rec.copy(state = DownloadState.DONE))
            is DownloadOutcome.NeedsRefresh -> store.update(
                rec.copy(state = DownloadState.NEEDS_REFRESH, error = outcome.message),
            )
            is DownloadOutcome.Failed -> store.update(
                rec.copy(state = DownloadState.ERROR, error = EngineErrors.userMessage(outcome.error)),
            )
        }
    }

    private suspend fun allocateName(downloadId: Long, suggested: String): String = nameLock.withLock {
        val name = uniqueFilename(suggested, exceptId = downloadId)
        val rec = store.get(downloadId)
        if (rec != null) {
            store.update(rec.copy(filename = name))
        }
        name
    }

    private suspend fun uniqueFilename(suggested: String, exceptId: Long): String {
        val existing = store.getAll().filter { it.id != exceptId }.map { it.filename }.filter { it.isNotEmpty() }.toHashSet()
        return Filenames.unique(suggested) { candidate ->
            candidate in existing ||
                File(outputDir, candidate).exists() ||
                File(outputDir, "$candidate.part").exists() ||
                sinks.nameTaken(candidate)
        }
    }

    private fun discardPartial(rec: DownloadRecord) {
        try {
            sinks.deletePartial(rec.outputPath, rec.filename)
        } catch (e: Exception) {
            logger.warn("delete partial failed id=${rec.id}", e)
        }
        if (rec.filename.isNotEmpty()) {
            File(outputDir, "${rec.filename}.part").takeIf { it.exists() }?.delete()
        }
        val hlsDir = File(outputDir, ".hls-${rec.id}")
        if (hlsDir.exists() && !hlsDir.deleteRecursively()) {
            logger.warn("delete HLS work dir failed id=${rec.id}")
        }
    }

    private fun deleteFinished(rec: DownloadRecord) {
        try {
            sinks.deleteFinished(rec.outputPath)
        } catch (e: Exception) {
            logger.warn("delete finished failed id=${rec.id}", e)
        }
        if (rec.outputPath.isNotEmpty() && !rec.outputPath.startsWith("content:")) {
            File(rec.outputPath).takeIf { it.exists() }?.delete()
        }
    }

    private enum class StopReason { Pause, Kill, Network, Cancel }
}
