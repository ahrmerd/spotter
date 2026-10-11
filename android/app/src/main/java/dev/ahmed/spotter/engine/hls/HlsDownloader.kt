package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.EngineLog
import dev.ahmed.spotter.engine.http.DownloadOutcome
import dev.ahmed.spotter.engine.http.MutablePart
import dev.ahmed.spotter.engine.http.NeedsRefreshException
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.net.CookieSource
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
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

class HlsDownloader(
    client: OkHttpClient,
    private val store: DownloadStore,
    private val logger: EngineLog = EngineLog.NONE,
    tokenBucket: TokenBucket = TokenBucket(),
    connectionLimiter: AtomicReference<Semaphore> = AtomicReference(Semaphore(16)),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remuxer: HlsRemuxer = KeepTsRemuxer,
    private val parallel: Int = DEFAULT_PARALLEL,
    cookies: CookieSource = CookieSource.NONE,
    private val freeSpace: FreeSpace = FreeSpace.UNLIMITED,
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
            runJob(initial, sinkFactory, workRoot, allocateName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsRefreshException) {
            logger.warn("needs refresh HTTP ${e.code} id=$downloadId")
            DownloadOutcome.NeedsRefresh(e.code, e.message ?: "HTTP ${e.code}")
        } catch (e: Exception) {
            logger.error("hls failed id=$downloadId", e)
            DownloadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun runJob(
        initial: DownloadRecord,
        sinkFactory: FileSinkFactory,
        workRoot: File,
        allocateName: suspend (String) -> String,
    ): DownloadOutcome {
        val captured = initial.headers
        val topText = http.fetchText(initial.url, captured)
        val top = HlsPlaylist.parse(topText, initial.url)
        val variantOverride = header(captured, HlsHeaders.VARIANT)
        val audioOverride = header(captured, HlsHeaders.AUDIO)

        val mediaUrl: String
        val audioUrl: String?
        var media: HlsDocument.Media
        when (top) {
            is HlsDocument.Master -> {
                val variant = top.variants.firstOrNull { it.uri == variantOverride } ?: top.best()
                mediaUrl = variant.uri
                audioUrl = audioOverride ?: top.audioFor(variant)?.uri
                media = HlsPlaylist.parse(http.fetchText(mediaUrl, captured), mediaUrl) as? HlsDocument.Media
                    ?: throw IOException("variant is not a media playlist")
            }
            is HlsDocument.Media -> {
                mediaUrl = initial.url
                audioUrl = audioOverride
                media = top
            }
        }
        rejectUnsupported(media)
        val audio = audioUrl?.let { url ->
            val parsed = HlsPlaylist.parse(http.fetchText(url, captured), url) as? HlsDocument.Media
                ?: throw IOException("audio rendition is not a media playlist")
            rejectUnsupported(parsed)
            parsed
        }

        val workDir = File(workRoot, ".hls-${initial.id}")
        workDir.mkdirs()
        val items = plan(media, audio, workDir)
        val mediaCount = items.count { it.countsForProgress }
        var record = initial.copy(
            mimeType = HlsHeaders.MIME_PLAYLIST,
            size = mediaCount.toLong(),
            ranged = true,
        )
        if (record.filename.isEmpty() || looksLikePlaylistName(record.filename)) {
            record = record.copy(filename = allocateName(HlsNames.withExt(record.filename, record.url, ".mp4")))
        }
        store.update(record)

        val parts = restoreParts(record.id, items)
        persist(record, parts, items)

        val keys = HashMap<String, ByteArray>()
        val keyLock = Mutex()
        val partsLock = Mutex()
        val gate = Semaphore(parallel.coerceAtLeast(1))

        suspend fun keyBytes(key: HlsKey?): ByteArray? {
            if (key == null || key.method == HlsKeyMethod.NONE) return null
            HlsCrypto.unsupportedReason(key)?.let { throw IOException(it) }
            val uri = key.uri ?: throw IOException("AES-128 key missing URI")
            keyLock.withLock {
                keys[uri]?.let { return it }
                val bytes = http.fetchKey(uri, captured)
                keys[uri] = bytes
                return bytes
            }
        }

        var persistDirty = 0
        var lastPersistAt = 0L
        suspend fun persistLocked(force: Boolean = false) {
            persistDirty++
            val now = System.currentTimeMillis()
            if (!force && persistDirty < 8 && now - lastPersistAt < 1_000) return
            persistDirty = 0
            lastPersistAt = now
            val done = items.indices.count { idx -> items[idx].countsForProgress && parts[idx].complete }.toLong()
            store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
            store.update(record.copy(downloaded = done, mimeType = HlsHeaders.MIME_PLAYLIST, size = mediaCount.toLong()))
        }

        coroutineScope {
            for (item in items) {
                launch {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        val part = parts[item.index]
                        if (part.complete && item.file.exists() && item.file.length() > 0) return@withPermit
                        if (item.file.exists()) item.file.delete()
                        val k = keyBytes(item.key)
                        val decrypt = if (k != null) {
                            { raw: java.io.InputStream ->
                                HlsCrypto.decryptingStream(raw, k, item.iv())
                            }
                        } else {
                            null
                        }
                        http.fetchToFile(item.url, captured, item.file, item.range, decrypt)
                        partsLock.withLock {
                            part.current = 1
                            persistLocked()
                        }
                    }
                }
            }
        }

        partsLock.withLock { persistLocked(force = true) }
        val complete = parts.count { it.complete }
        if (complete != items.size) {
            return DownloadOutcome.Failed("incomplete segments $complete/${items.size}")
        }

        val videoItems = items.filter { it.stream == Stream.VIDEO }
        val audioItems = items.filter { it.stream == Stream.AUDIO }
        val segmentBytes = items.sumOf { it.file.length() }
        // Peak after this point is assembled + remux output (~2x), not 4x: segments are deleted as they are appended.
        freeSpace.check(segmentBytes * 2, workDir.path)
        val assembledVideo = File(workDir, "assembled-video")
        val videoKind = assemble(videoItems, assembledVideo, media.map != null)
        val assembledAudio = if (audioItems.isEmpty()) {
            null
        } else {
            val f = File(workDir, "assembled-audio")
            assemble(audioItems, f, audio?.map != null)
            f
        }

        val muxDest = File(workDir, "out.mp4")
        val result = when {
            videoKind == HlsPayloadKind.FMP4 && assembledAudio == null ->
                RemuxResult(assembledVideo, HlsHeaders.MIME_MP4, null)
            else -> remuxer.remux(assembledVideo, assembledAudio, muxDest)
        }
        if (result.output != assembledVideo) assembledVideo.delete()
        assembledAudio?.delete()

        val ext = if (result.mime == HlsHeaders.MIME_MP4) ".mp4" else ".ts"
        val name = allocateName(HlsNames.withExt(record.filename, record.url, ext))
        // video/mp2t makes MediaStore rewrite the name to .m2ts (E2); octet-stream keeps .ts.
        val sinkMime = if (result.mime == HlsHeaders.MIME_TS) "application/octet-stream" else result.mime
        var sink: FileSink? = null
        try {
            sink = sinkFactory.open(name, record.outputPath, sinkMime)
            copyToSink(result.output, sink)
            val length = result.output.length()
            val storedName = sink.outputName.ifBlank { name }
            store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
            store.update(
                record.copy(
                    filename = storedName,
                    outputPath = sink.outputPath,
                    mimeType = result.mime,
                    size = length,
                    downloaded = length,
                    error = result.note,
                ),
            )
            sink.complete()
            sink = null
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

    private fun rejectUnsupported(media: HlsDocument.Media) {
        if (!media.endList) throw IOException("Live HLS is not supported (no #EXT-X-ENDLIST)")
        if (media.segments.isEmpty()) throw IOException("Playlist has no segments")
        for (seg in media.segments) {
            HlsCrypto.unsupportedReason(seg.key)?.let { throw IOException(it) }
        }
        media.map?.let { /* init is downloaded as a work item */ }
    }

    private fun plan(video: HlsDocument.Media, audio: HlsDocument.Media?, workDir: File): List<WorkItem> {
        val out = mutableListOf<WorkItem>()
        fun add(media: HlsDocument.Media, stream: Stream, prefix: String) {
            media.map?.let { map ->
                out += WorkItem(
                    index = out.size,
                    url = map.uri,
                    range = map.byteRange,
                    key = null,
                    sequence = 0,
                    file = File(workDir, "$prefix-init"),
                    stream = stream,
                    countsForProgress = false,
                    isInit = true,
                )
            }
            for (seg in media.segments) {
                out += WorkItem(
                    index = out.size,
                    url = seg.uri,
                    range = seg.byteRange,
                    key = seg.key,
                    sequence = seg.sequence,
                    file = File(workDir, "$prefix-${seg.sequence}"),
                    stream = stream,
                    countsForProgress = true,
                    isInit = false,
                )
            }
        }
        add(video, Stream.VIDEO, "v")
        if (audio != null) add(audio, Stream.AUDIO, "a")
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

    private suspend fun persist(record: DownloadRecord, parts: List<MutablePart>, items: List<WorkItem>) {
        val done = items.indices.count { idx -> items[idx].countsForProgress && parts[idx].complete }.toLong()
        store.replaceParts(record.id, parts.map { it.toRecord(record.id) })
        store.update(record.copy(downloaded = done))
    }

    private fun assemble(items: List<WorkItem>, dest: File, mapPresent: Boolean): HlsPayloadKind {
        val mediaItems = items.filter { !it.isInit }
        val first = mediaItems.firstOrNull()?.file
        val sniffed = if (first != null && first.exists()) HlsTs.sniff(first, mapPresent) else HlsPayloadKind.UNKNOWN to 0
        val kind = if (mapPresent) HlsPayloadKind.FMP4 else sniffed.first
        dest.outputStream().use { out ->
            for (item in items) {
                val skip = when {
                    item.isInit -> 0
                    kind == HlsPayloadKind.MPEG_TS -> HlsTs.sniff(item.file).second.coerceAtLeast(0)
                    else -> 0
                }
                copyFile(item.file, out, skip)
                item.file.delete()
            }
        }
        return kind
    }

    private fun copyFile(src: File, dest: OutputStream, skip: Int) {
        src.inputStream().use { input ->
            var left = skip.toLong()
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n
            }
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                dest.write(buf, 0, n)
            }
        }
    }

    private fun copyToSink(src: File, sink: FileSink) {
        freeSpace.check(src.length(), sink.outputPath)
        try {
            sink.setLength(0)
        } catch (e: InsufficientSpaceException) {
            throw e
        } catch (e: IOException) {
            val msg = e.message.orEmpty().lowercase()
            if ("no space" in msg || "enospc" in msg) {
                throw InsufficientSpaceException(src.length(), freeSpace.availableBytes(sink.outputPath))
            }
            throw e
        }
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

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun looksLikePlaylistName(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "m3u8" || ext == "mpd"
    }

    private enum class Stream { VIDEO, AUDIO }

    private data class WorkItem(
        val index: Int,
        val url: String,
        val range: HlsByteRange?,
        val key: HlsKey?,
        val sequence: Long,
        val file: File,
        val stream: Stream,
        val countsForProgress: Boolean,
        val isInit: Boolean,
    ) {
        fun iv(): ByteArray = key?.iv ?: HlsPlaylist.sequenceIv(sequence)
    }

    companion object {
        const val DEFAULT_PARALLEL = 8
    }
}
