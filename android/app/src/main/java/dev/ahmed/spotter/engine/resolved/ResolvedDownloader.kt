package dev.ahmed.spotter.engine.resolved

import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.http.DownloadOutcome
import dev.ahmed.spotter.engine.http.MutablePart
import dev.ahmed.spotter.engine.http.NeedsRefreshException
import dev.ahmed.spotter.engine.hls.HlsByteRange
import dev.ahmed.spotter.engine.hls.HlsHttp
import dev.ahmed.spotter.engine.hls.HlsNames
import dev.ahmed.spotter.engine.hls.HlsRemuxer
import dev.ahmed.spotter.engine.hls.KeepTsRemuxer
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.net.CookieSource
import dev.ahmed.spotter.engine.net.TokenBucket
import dev.ahmed.spotter.engine.sink.FileSink
import dev.ahmed.spotter.engine.sink.FileSinkFactory
import dev.ahmed.spotter.engine.sink.FreeSpace
import dev.ahmed.spotter.engine.store.DownloadStore
import dev.ahmed.spotter.resolver.OptionKind
import dev.ahmed.spotter.resolver.PlanStream
import dev.ahmed.spotter.resolver.ResolvedPlan
import dev.ahmed.spotter.resolver.ResolvedUrls
import dev.ahmed.spotter.resolver.SiteResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import dev.ahmed.spotter.engine.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads the streams of a resolved page (for example a YouTube video): a video file and an audio file, or one file.
 *
 * Each stream is fetched as 8 MiB byte ranges, several at a time (sites throttle a single long request, and chunks make
 * pause and resume exact). The ranges are stored like HLS segments, so resume never repeats a finished chunk. Links
 * expire after a few hours: on a 401/403/404/410 the page is looked up again once and the same chunks continue with
 * the fresh links. Separate video and audio are merged into one MP4 with the platform muxer.
 */
class ResolvedDownloader(
    client: OkHttpClient,
    private val store: DownloadStore,
    private val resolvers: List<SiteResolver>,
    private val logger: EngineLog = EngineLog.NONE,
    tokenBucket: TokenBucket = TokenBucket(),
    connectionLimiter: AtomicReference<Semaphore> = AtomicReference(Semaphore(16)),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remuxer: HlsRemuxer = KeepTsRemuxer,
    private val parallel: Int = DEFAULT_PARALLEL,
    cookies: CookieSource = CookieSource.NONE,
    private val freeSpace: FreeSpace = FreeSpace.UNLIMITED,
    private val chunkBytes: Long = CHUNK,
) {
    private val http = HlsHttp(client, tokenBucket, connectionLimiter, cookies)

    suspend fun download(
        downloadId: Long,
        sinkFactory: FileSinkFactory,
        workRoot: File,
        allocateName: suspend (String) -> String,
    ): DownloadOutcome = withContext(ioDispatcher) {
        val initial = store.get(downloadId) ?: return@withContext DownloadOutcome.Failed("missing download $downloadId")
        try {
            run(initial, sinkFactory, workRoot, allocateName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsRefreshException) {
            DownloadOutcome.NeedsRefresh(e.code, e.message ?: "HTTP ${e.code}")
        } catch (e: Exception) {
            logger.error("resolved download failed id=$downloadId", e)
            DownloadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun run(
        initial: DownloadRecord,
        sinkFactory: FileSinkFactory,
        workRoot: File,
        allocateName: suspend (String) -> String,
    ): DownloadOutcome {
        val plan = ResolvedUrls.decode(initial.url) ?: return DownloadOutcome.Failed("Unreadable download plan")
        val streams = LinkedHashMap<Role, StreamState>()
        plan.video?.let { streams[Role.VIDEO] = StreamState(it) }
        plan.audio?.let { streams[Role.AUDIO] = StreamState(it) }
        if (streams.isEmpty()) return DownloadOutcome.Failed("Nothing to download")

        val workDir = File(workRoot, ".res-${initial.id}").apply { mkdirs() }
        val mergedFile = File(workDir, "merged.mp4")
        val items = plan(streams, workDir)
        val totalBytes = items.sumOf { it.length }
        val kind = runCatching { OptionKind.valueOf(plan.kind) }.getOrDefault(OptionKind.MERGED)
        val outExt = if (kind == OptionKind.AUDIO_ONLY) ".m4a" else ".mp4"
        val outMime = if (kind == OptionKind.AUDIO_ONLY) "audio/mp4" else "video/mp4"

        var record = initial.copy(mimeType = outMime, size = totalBytes, ranged = true)
        if (record.filename.isEmpty()) {
            record = record.copy(filename = allocateName(HlsNames.withExt(plan.title, "", outExt)))
        }
        store.update(record)
        // Peak disk: chunks (1x) then the assembled files and the merged output (about 2x of the total).
        freeSpace.check(totalBytes * 2, workDir.path)

        val parts = restoreParts(record.id, items)
        val partsLock = Mutex()
        val refreshLock = Mutex()
        val gate = Semaphore(parallel.coerceAtLeast(1))
        val refreshGeneration = java.util.concurrent.atomic.AtomicInteger(0)
        var persistDirty = 0
        var lastPersistAt = 0L

        suspend fun persistLocked(force: Boolean = false) {
            persistDirty++
            val now = System.currentTimeMillis()
            if (!force && persistDirty < 4 && now - lastPersistAt < 1_000) return
            persistDirty = 0
            lastPersistAt = now
            val done = items.indices.filter { parts[it].complete }.sumOf { items[it].length }
            store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
            store.update(record.copy(downloaded = done))
        }

        suspend fun refreshLinks(seenGeneration: Int) {
            refreshLock.withLock {
                // Several chunks fail together when a link expires; only the first one looks the page up again.
                if (refreshGeneration.get() != seenGeneration) return
                if (refreshGeneration.get() >= MAX_REFRESHES) {
                    throw NeedsRefreshException(403, "Links keep expiring; open the page again")
                }
                val resolver = resolvers.firstOrNull { it.canHandle(plan.page) }
                    ?: throw NeedsRefreshException(403, "Link expired and this page cannot be looked up again")
                val media = resolver.resolve(plan.page)
                val option = media.options.firstOrNull { it.id == plan.optionId }
                    ?: throw NeedsRefreshException(403, "Link expired and the chosen quality is no longer offered")
                option.video?.let { streams[Role.VIDEO]?.let { s -> s.url = it.url } }
                option.audio?.let { streams[Role.AUDIO]?.let { s -> s.url = it.url } }
                refreshGeneration.incrementAndGet()
                logger.info("refreshed stream links for download ${record.id}")
            }
        }

        coroutineScope {
            for (item in items) {
                launch {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        val part = parts[item.index]
                        if (part.complete && item.file.exists() && item.file.length() == item.length) return@withPermit
                        if (item.file.exists()) item.file.delete()
                        // A stream already joined (or the whole merge finished) before an interruption needs no chunks.
                        if (assembledFile(workDir, item.role).exists() || mergedFile.exists()) return@withPermit
                        val state = streams.getValue(item.role)
                        val seen = refreshGeneration.get()
                        try {
                            http.fetchToFile(state.url, initial.headers, item.file, item.range)
                        } catch (e: NeedsRefreshException) {
                            refreshLinks(seen)
                            http.fetchToFile(state.url, initial.headers, item.file, item.range)
                        }
                        if (item.length > 0 && item.file.length() != item.length) {
                            item.file.delete()
                            throw IOException("chunk ${item.index} has ${item.file.length()} bytes, expected ${item.length}")
                        }
                        partsLock.withLock {
                            parts[item.index].current = 1
                            persistLocked()
                        }
                    }
                }
            }
        }
        partsLock.withLock { persistLocked(force = true) }
        val complete = parts.indices.count {
            parts[it].complete || assembledFile(workDir, items[it].role).exists() || mergedFile.exists()
        }
        if (complete != items.size) return DownloadOutcome.Failed("incomplete download $complete/${items.size}")

        val video = if (mergedFile.exists()) null else streams[Role.VIDEO]?.let {
            assemble(items.filter { i -> i.role == Role.VIDEO }, assembledFile(workDir, Role.VIDEO))
        }
        val audio = if (mergedFile.exists()) null else streams[Role.AUDIO]?.let {
            assemble(items.filter { i -> i.role == Role.AUDIO }, assembledFile(workDir, Role.AUDIO))
        }
        val output: File = when {
            kind == OptionKind.MERGED && mergedFile.exists() -> mergedFile
            kind == OptionKind.MERGED && video != null && audio != null -> {
                val merged = mergedFile
                if (!merged.exists()) {
                    val tmp = File(workDir, "merged.tmp.mp4")
                    val result = remuxer.remux(video, audio, tmp)
                    if (result.output != tmp) {
                        return DownloadOutcome.Failed(result.note ?: "Could not merge video and audio")
                    }
                    if (!tmp.renameTo(merged)) throw IOException("could not finish the merged file")
                }
                video.delete()
                audio.delete()
                merged
            }
            video != null -> video
            audio != null -> audio
            else -> return DownloadOutcome.Failed("Nothing to save")
        }

        val name = allocateName(HlsNames.withExt(record.filename, "", outExt))
        var sink: FileSink? = null
        try {
            sink = sinkFactory.open(name, record.outputPath, outMime)
            copyToSink(output, sink)
            val length = output.length()
            val storedName = sink.outputName.ifBlank { name }
            store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
            store.update(
                record.copy(
                    filename = storedName,
                    outputPath = sink.outputPath,
                    mimeType = outMime,
                    size = length,
                    downloaded = length,
                    error = null,
                ),
            )
            val published = sink
            published.complete()
            sink = null
            val real = try {
                published.finalName()
            } catch (e: RuntimeException) {
                ""
            }
            // The file is published: record DONE now, uninterruptibly. A pause arriving at this instant must not turn a
            // finished download back into PAUSED (the result returned below could be lost to the cancellation).
            withContext(NonCancellable) {
                store.get(record.id)?.let {
                    store.update(it.copy(filename = real.ifBlank { it.filename }, state = DownloadState.DONE, error = null))
                }
            }
            workDir.deleteRecursively()
            return DownloadOutcome.Completed
        } finally {
            try {
                sink?.close()
            } catch (io: IOException) {
                logger.warn("close sink failed", io)
            }
        }
    }

    private fun plan(streams: Map<Role, StreamState>, workDir: File): List<WorkItem> {
        val out = mutableListOf<WorkItem>()
        for ((role, state) in streams) {
            val size = state.stream.size
            if (size <= 0) {
                out += WorkItem(out.size, role, null, 0, File(workDir, "${role.name.lowercase()}-0"))
                continue
            }
            var offset = 0L
            var n = 0
            while (offset < size) {
                val len = minOf(chunkBytes, size - offset)
                out += WorkItem(out.size, role, HlsByteRange(len, offset), len, File(workDir, "${role.name.lowercase()}-${n++}"))
                offset += len
            }
        }
        return out
    }

    private suspend fun restoreParts(downloadId: Long, items: List<WorkItem>): MutableList<MutablePart> {
        val existing = store.getParts(downloadId)
        if (existing.size == items.size) {
            return existing.map { MutablePart(it.id, it.start, it.current, it.end) }.toMutableList()
        }
        val fresh = items.map { MutablePart(0, it.index.toLong(), 0, 0) }.toMutableList()
        store.replaceParts(downloadId, fresh.map { it.toRecord(downloadId) })
        return fresh
    }

    private fun assembledFile(workDir: File, role: Role) = File(workDir, "${role.name.lowercase()}.bin")

    /**
     * Joins the chunks in order into [dest]. The chunks are deleted only after the joined file is complete (written
     * under a temporary name and renamed), so a pause or a crash while joining never forces a re-download.
     */
    private fun assemble(items: List<WorkItem>, dest: File): File {
        if (dest.exists()) return dest
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            for (item in items.sortedBy { it.index }) {
                item.file.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) throw IOException("could not finish ${dest.name}")
        items.forEach { it.file.delete() }
        return dest
    }

    private fun copyToSink(src: File, sink: FileSink) {
        sink.setLength(0)
        src.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                sink.writeAt(pos, buf, 0, n)
                pos += n
            }
            sink.setLength(pos)
        }
    }

    private enum class Role { VIDEO, AUDIO }

    private class StreamState(val stream: PlanStream) {
        @Volatile var url: String = stream.url
    }

    private class WorkItem(
        val index: Int,
        val role: Role,
        val range: HlsByteRange?,
        val length: Long,
        val file: File,
    )

    companion object {
        const val CHUNK = 8L * 1024 * 1024
        const val DEFAULT_PARALLEL = 4
        const val MAX_REFRESHES = 3
    }
}
