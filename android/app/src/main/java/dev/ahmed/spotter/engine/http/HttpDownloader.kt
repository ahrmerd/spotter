package dev.ahmed.spotter.engine.http

import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.PartRecord
import dev.ahmed.spotter.engine.net.CookieSource
import dev.ahmed.spotter.engine.net.HeaderUtils
import dev.ahmed.spotter.engine.net.ProbeResult
import dev.ahmed.spotter.engine.net.RangeProbe
import dev.ahmed.spotter.engine.net.TokenBucket
import dev.ahmed.spotter.engine.sink.FileSink
import dev.ahmed.spotter.engine.sink.FileSinkFactory
import dev.ahmed.spotter.engine.sink.FreeSpace
import dev.ahmed.spotter.engine.sink.InsufficientSpaceException
import dev.ahmed.spotter.engine.store.DownloadStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.Volatile
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.min

sealed class DownloadOutcome {
    data object Completed : DownloadOutcome()
    data class NeedsRefresh(val code: Int, val message: String) : DownloadOutcome()
    data class Failed(val error: String) : DownloadOutcome()
}

internal class MutablePart(
    var id: Long,
    val start: Long,
    @Volatile var current: Long,
    @Volatile var end: Long,
) {
    val complete: Boolean get() = end >= 0 && current > end
    fun toRecord(downloadId: Long) = PartRecord(id, downloadId, start, current, end)
}

class HttpDownloader(
    private val client: OkHttpClient,
    private val store: DownloadStore,
    private val logger: EngineLog = EngineLog.NONE,
    private val tokenBucket: TokenBucket = TokenBucket(),
    private val connectionLimiter: AtomicReference<Semaphore> = AtomicReference(Semaphore(16)),
    private val persistEveryBytes: Long = 256 * 1024,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cookies: CookieSource = CookieSource.NONE,
    private val freeSpace: FreeSpace = FreeSpace.UNLIMITED,
) {
    private val probe = RangeProbe(client, cookies)

    suspend fun download(
        downloadId: Long,
        sinkFactory: FileSinkFactory,
        connections: Int,
        allocateName: suspend (suggested: String) -> String,
    ): DownloadOutcome = withContext(ioDispatcher) {
        var sink: FileSink? = null
        try {
            var lastRestart: String? = null
            repeat(2) { attempt ->
                var record = store.get(downloadId) ?: return@withContext DownloadOutcome.Failed("missing download $downloadId")
                val prepared = prepare(record, connections, allocateName)
                record = prepared.record
                val parts = prepared.parts
                sink?.close()
                sink = sinkFactory.open(record.filename, record.outputPath, record.mimeType)
                val openSink = sink!!
                if (record.outputPath.isEmpty() || record.outputPath != openSink.outputPath ||
                    (openSink.outputName.isNotEmpty() && openSink.outputName != record.filename)
                ) {
                    record = record.copy(
                        outputPath = openSink.outputPath,
                        filename = openSink.outputName.ifBlank { record.filename },
                    )
                    store.update(record)
                }
                try {
                    // A resume must continue into the very file we wrote. A deleted/truncated .part would otherwise be
                    // re-created as zeros and the download would "complete" with holes.
                    if (parts.any { it.current > it.start }) {
                        val len = openSink.length()
                        val intact = if (record.size > 0) len == record.size else len >= parts.maxOf { it.current }
                        if (!intact) throw RestartDownloadException("partial file missing or truncated (length $len)")
                    }
                    if (record.size > 0) {
                        freeSpace.check(record.size, openSink.outputPath)
                        try {
                            openSink.setLength(record.size)
                        } catch (e: InsufficientSpaceException) {
                            throw e
                        } catch (e: IOException) {
                            val msg = e.message.orEmpty().lowercase()
                            if ("no space" in msg || "enospc" in msg) {
                                throw InsufficientSpaceException(
                                    record.size,
                                    freeSpace.availableBytes(openSink.outputPath),
                                )
                            }
                            throw e
                        }
                    } else if (!record.ranged) {
                        openSink.setLength(0)
                    }
                    runParts(record, parts, openSink)
                } catch (e: RestartDownloadException) {
                    lastRestart = e.message
                    logger.info("restart id=$downloadId: ${e.message}")
                    store.replaceParts(downloadId, emptyList())
                    store.update(record.copy(downloaded = 0, etag = null, lastModified = null))
                    if (attempt == 0) return@repeat
                    throw e
                }
                val downloaded = parts.sumOf { downloadedOf(it) }
                if (record.size > 0 && downloaded != record.size) {
                    openSink.force()
                    return@withContext DownloadOutcome.Failed(
                        "size mismatch: expected ${record.size}, got $downloaded",
                    )
                }
                // Persist "all parts complete" first: if we die right after the rename, the next start finds a finished
                // file with complete parts and finalises it instead of failing on "refusing to overwrite".
                store.replaceParts(downloadId, parts.map { it.toRecord(downloadId) })
                store.update(
                    record.copy(
                        downloaded = downloaded,
                        size = if (record.size > 0) record.size else downloaded,
                    ),
                )
                openSink.complete()
                sink = null
                storeFinalName(downloadId, openSink)
                return@withContext DownloadOutcome.Completed
            }
            DownloadOutcome.Failed(lastRestart ?: "restart failed")
        } catch (e: CancellationException) {
            try {
                sink?.force()
            } catch (io: IOException) {
                logger.warn("force on cancel failed", io)
            }
            throw e
        } catch (e: NeedsRefreshException) {
            logger.warn("needs refresh HTTP ${e.code} id=$downloadId")
            DownloadOutcome.NeedsRefresh(e.code, e.message ?: "HTTP ${e.code}")
        } catch (e: Exception) {
            logger.error("download failed id=$downloadId", e)
            DownloadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                sink?.close()
            } catch (io: IOException) {
                logger.warn("close sink failed", io)
            }
        }
    }

    /** After publishing, keep the row's name equal to the file's real name (M5 audit F2). */
    private suspend fun storeFinalName(downloadId: Long, sink: FileSink) {
        val real = try {
            sink.finalName()
        } catch (e: RuntimeException) {
            ""
        }
        // Published: record DONE uninterruptibly so a pause at this instant cannot undo it (see ResolvedDownloader).
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val rec = store.get(downloadId) ?: return@withContext
            store.update(
                rec.copy(
                    filename = if (real.isNotBlank()) real else rec.filename,
                    state = dev.ahmed.spotter.engine.model.DownloadState.DONE,
                ),
            )
        }
    }

    private data class Prepared(
        val record: DownloadRecord,
        val parts: MutableList<MutablePart>,
    )

    private suspend fun prepare(
        initial: DownloadRecord,
        connections: Int,
        allocateName: suspend (String) -> String,
    ): Prepared {
        var record = initial
        val existing = store.getParts(record.id).map { MutablePart(it.id, it.start, it.current, it.end) }
            .toMutableList()

        if (existing.isNotEmpty() && record.ranged) {
            val validation = validateResume(record)
            if (validation.restart) {
                logger.info("validator changed for id=${record.id}; restarting")
                existing.clear()
                record = record.copy(
                    downloaded = 0,
                    size = validation.probe.size ?: 0,
                    ranged = validation.probe.ranged,
                    etag = validation.probe.etag,
                    lastModified = validation.probe.lastModified,
                    mimeType = validation.probe.mimeType ?: record.mimeType,
                )
            } else if (!record.ranged) {
                existing.clear()
            }
        }

        if (!record.ranged && existing.isNotEmpty()) {
            existing.forEach { it.current = it.start }
            record = record.copy(downloaded = 0)
        }

        if (existing.isEmpty()) {
            val extra = buildMap {
                record.etag?.let { put("If-Range", it) } ?: record.lastModified?.let { put("If-Range", it) }
            }
            val probed = probe.probe(record.url, record.headers, extra)
            val filename = when {
                record.outputPath.isNotEmpty() && record.filename.isNotEmpty() -> record.filename
                record.filename.isNotEmpty() -> {
                    val withExt = Filenames.ensureExtension(record.filename, probed.mimeType, record.url)
                    allocateName(withExt)
                }
                else -> allocateName(probed.filename)
            }
            record = record.copy(
                filename = filename,
                size = probed.size ?: 0,
                ranged = probed.ranged,
                etag = probed.etag,
                lastModified = probed.lastModified,
                mimeType = probed.mimeType,
                downloaded = 0,
            )
            val plans = PartPlanner.initialParts(probed.size, probed.ranged, connections)
            existing.clear()
            existing.addAll(plans.map { MutablePart(0, it.start, it.current, it.end) })
            store.update(record)
            store.replaceParts(record.id, existing.map { it.toRecord(record.id) })
        }
        return Prepared(record, existing)
    }

    private data class ResumeCheck(val restart: Boolean, val probe: ProbeResult)

    private fun validateResume(record: DownloadRecord): ResumeCheck {
        val extra = buildMap {
            record.etag?.let { put("If-Range", it) } ?: record.lastModified?.let { put("If-Range", it) }
        }
        val probed = probe.probe(record.url, record.headers, extra)
        val etagChanged = record.etag != null && probed.etag != null && record.etag != probed.etag
        val lmChanged = record.lastModified != null && probed.lastModified != null &&
            record.lastModified != probed.lastModified
        val sizeChanged = record.size > 0 && probed.size != null && probed.size != record.size
        val lostRange = record.ranged && !probed.ranged
        return ResumeCheck(etagChanged || lmChanged || sizeChanged || lostRange, probed)
    }

    private suspend fun runParts(
        record: DownloadRecord,
        parts: MutableList<MutablePart>,
        sink: FileSink,
    ) {
        val partsLock = Mutex()
        val bytesSincePersist = AtomicLong(0)

        suspend fun persist() {
            val downloaded = parts.sumOf { downloadedOf(it) }
            store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
            store.update(record.copy(downloaded = downloaded, outputPath = sink.outputPath))
            bytesSincePersist.set(0)
        }

        suspend fun stealWork(): MutablePart? = partsLock.withLock {
            val snapshot = parts.map { PartPlan(it.start, it.current, it.end) }
            val steal = PartPlanner.steal(snapshot) ?: return@withLock null
            parts[steal.fromIndex].end = steal.newEndForOriginal
            val stolen = MutablePart(0, steal.stolen.start, steal.stolen.current, steal.stolen.end)
            parts.add(stolen)
            persist()
            stolen
        }

        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    delay(1_000)
                    partsLock.withLock { persist() }
                }
            }
            try {
                val workerCount = parts.size.coerceAtLeast(1)
                coroutineScope {
                    repeat(workerCount) { index ->
                        launch {
                            var work: MutablePart? = parts.getOrNull(index)
                            while (currentCoroutineContext().isActive) {
                                if (work == null) work = stealWork()
                                if (work == null) break
                                fetchPart(
                                    record = record,
                                    work = work,
                                    sink = sink,
                                    onBytes = { n ->
                                        if (bytesSincePersist.addAndGet(n.toLong()) >= persistEveryBytes) {
                                            partsLock.withLock { persist() }
                                        }
                                    },
                                )
                                work = null
                            }
                        }
                    }
                }
            } finally {
                ticker.cancel()
                // Must run even when cancelled (pause/shutdown); a real Room store throws on a cancelled coroutine.
                withContext(NonCancellable) {
                    partsLock.withLock { persist() }
                    sink.force()
                }
            }
        }
    }

    private suspend fun fetchPart(
        record: DownloadRecord,
        work: MutablePart,
        sink: FileSink,
        onBytes: suspend (Int) -> Unit,
    ) {
        var attempts = 0
        while (currentCoroutineContext().isActive && !work.complete) {
            currentCoroutineContext().ensureActive()
            // Without Range support the server always sends the body from byte 0, so every attempt must write from 0
            // (otherwise a retry after a dropped connection lands at the old offset and silently corrupts the file).
            if (!record.ranged) work.current = work.start
            val limiter = connectionLimiter.get()
            limiter.acquire()
            try {
                val endSnapshot = work.end
                val extra = if (record.ranged && endSnapshot >= 0) {
                    mapOf("Range" to "bytes=${work.current}-$endSnapshot")
                } else {
                    emptyMap()
                }
                val headers = HeaderUtils.merge(
                    with(CookieSource) { record.headers.withCookie(record.url, cookies) },
                    extra,
                )
                val req = Request.Builder().url(record.url).get().apply {
                    for ((k, v) in headers) header(k, v)
                }.build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        401, 403, 404, 410 -> throw NeedsRefreshException(resp.code, "HTTP ${resp.code}")
                        200 -> {
                            if (record.ranged && extra.containsKey("Range")) {
                                throw RestartDownloadException("entity changed or Range ignored")
                            }
                        }
                        206 -> Unit
                        else -> throw HttpStatusException(resp.code, "HTTP ${resp.code}")
                    }
                    val stream = resp.body?.byteStream() ?: throw IOException("empty body")
                    val buf = ByteArray(BUFFER)
                    while (currentCoroutineContext().isActive && !work.complete) {
                        val remaining = if (work.end >= 0) work.end - work.current + 1 else BUFFER.toLong()
                        if (remaining <= 0) break
                        val want = min(buf.size.toLong(), remaining).toInt()
                        val n = stream.read(buf, 0, want)
                        if (n < 0) break
                        tokenBucket.take(n)
                        val written = min(
                            n.toLong(),
                            if (work.end >= 0) (work.end - work.current + 1).coerceAtLeast(0) else n.toLong(),
                        ).toInt()
                        if (written <= 0) break
                        sink.writeAt(work.current, buf, 0, written)
                        work.current += written
                        attempts = 0
                        onBytes(written)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsRefreshException) {
                throw e
            } catch (e: RestartDownloadException) {
                throw e
            } catch (e: IOException) {
                attempts++
                if (attempts > MAX_ATTEMPTS) throw e
                delay(backoffMs(attempts))
                continue
            } catch (e: HttpStatusException) {
                attempts++
                if (attempts > MAX_ATTEMPTS) throw e
                delay(backoffMs(attempts))
                continue
            } finally {
                limiter.release()
            }
            if (work.complete) {
                sink.force()
                return
            }
            if (work.end < 0) return
            attempts++
            if (attempts > MAX_ATTEMPTS) {
                throw IOException("short read on ${work.start}-${work.end} at ${work.current}")
            }
            delay(backoffMs(attempts))
        }
    }

    companion object {
        const val BUFFER = 64 * 1024
        const val MAX_ATTEMPTS = 8

        fun backoffMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 10)
            return min(30_000L, 1_000L shl shift)
        }

        internal fun downloadedOf(part: MutablePart): Long {
            return if (part.end < 0) {
                (part.current - part.start).coerceAtLeast(0)
            } else {
                (part.current - part.start).coerceIn(0, part.end - part.start + 1)
            }
        }
    }
}
