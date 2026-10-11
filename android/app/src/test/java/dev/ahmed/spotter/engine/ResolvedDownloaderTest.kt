package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.hls.HlsRemuxer
import dev.ahmed.spotter.engine.hls.RemuxResult
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import dev.ahmed.spotter.resolver.MediaOption
import dev.ahmed.spotter.resolver.OptionKind
import dev.ahmed.spotter.resolver.PlanStream
import dev.ahmed.spotter.resolver.ResolvedMedia
import dev.ahmed.spotter.resolver.ResolvedPlan
import dev.ahmed.spotter.resolver.ResolvedUrls
import dev.ahmed.spotter.resolver.SiteResolver
import dev.ahmed.spotter.resolver.StreamRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A resolved page (video + audio streams) downloaded in chunks and merged; also link expiry and pause/resume. */
class ResolvedDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var supervisor: kotlinx.coroutines.Job
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private var manager: DownloadManager? = null
    private val video = randomBytes(20 * 1024 * 1024 + 123, seed = 101)
    private val audio = randomBytes(9 * 1024 * 1024 + 7, seed = 102)

    /** Stand-in for the platform muxer: output = video bytes followed by audio bytes (enough to prove both arrive intact). */
    private object ConcatRemuxer : HlsRemuxer {
        override fun remux(video: File, audio: File?, destMp4: File): RemuxResult {
            destMp4.outputStream().use { out ->
                video.inputStream().use { it.copyTo(out) }
                audio?.inputStream()?.use { it.copyTo(out) }
            }
            return RemuxResult(destMp4, "video/mp4", null)
        }
    }

    private class FakeResolver(private val onResolve: () -> Unit, private val media: () -> ResolvedMedia) : SiteResolver {
        var calls = 0
        override fun canHandle(url: String) = url.contains("fake-site")
        override suspend fun resolve(url: String): ResolvedMedia {
            calls++
            onResolve()
            return media()
        }
    }

    @Before
    fun setUp() {
        server = RangeTestServer()
        server.assets = mapOf("/v.bin" to video, "/a.bin" to audio)
        store = InMemoryDownloadStore()
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        dir = tmp.newFolder("out")
    }

    @After
    fun tearDown() = runBlocking {
        manager?.shutdown()
        supervisor.cancelAndJoin()
        server.close()
    }

    private fun plan(kind: OptionKind, withAudio: Boolean = true, id: String = "v720") = ResolvedPlan(
        page = "https://fake-site/watch?v=1",
        title = "My Clip",
        optionId = id,
        kind = kind.name,
        video = if (kind == OptionKind.AUDIO_ONLY) null else PlanStream(server.url("/v.bin"), video.size.toLong(), "video/mp4", ".mp4"),
        audio = if (withAudio) PlanStream(server.url("/a.bin"), audio.size.toLong(), "audio/mp4", ".m4a") else null,
    )

    private fun newManager(resolvers: List<SiteResolver> = emptyList()): DownloadManager =
        DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
            hlsRemuxer = ConcatRemuxer,
            resolvers = resolvers,
        ).also { manager = it }

    @Test
    fun mergedVideoAndAudioAreDownloadedInChunksAndJoined() = runBlocking {
        val m = newManager()
        val id = m.enqueue(ResolvedUrls.encode(plan(OptionKind.MERGED)), emptyMap(), "My Clip.mp4")
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val out = File(rec.outputPath)
        assertEquals(sha256(video + audio), sha256(out))
        assertEquals("My Clip.mp4", rec.filename)
        // 8 MiB chunks: 20 MiB video = 3 chunks, 9 MiB audio = 2 chunks.
        val ranges = server.recorded.mapNotNull { r -> r.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value }
        assertTrue("expected 5 chunk requests, got $ranges", ranges.size >= 5)
        assertTrue("no work directory left behind", dir.listFiles()!!.none { it.name.startsWith(".res-") })
    }

    @Test
    fun oneFileOptionNeedsNoMerge() = runBlocking {
        val m = newManager()
        val id = m.enqueue(ResolvedUrls.encode(plan(OptionKind.MUXED, withAudio = false, id = "m360")), emptyMap(), "One.mp4")
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(video), sha256(File(rec.outputPath)))
    }

    @Test
    fun audioOnlySavesAnM4a() = runBlocking {
        val m = newManager()
        val id = m.enqueue(ResolvedUrls.encode(plan(OptionKind.AUDIO_ONLY, id = "a")), emptyMap(), "Song")
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertTrue(rec.filename, rec.filename.endsWith(".m4a"))
        assertEquals(sha256(audio), sha256(File(rec.outputPath)))
    }

    @Test
    fun expiredLinksAreRefreshedOnceFromThePageAndTheDownloadFinishes() = runBlocking {
        // After 2 requests the server answers 403 (expired link). Resolving again "fixes" it, like a new signed URL.
        server.failAfterRequests = 2
        val fresh = plan(OptionKind.MERGED)
        val resolver = FakeResolver(onResolve = { server.failAfterRequests = Int.MAX_VALUE }) {
            ResolvedMedia(
                pageUrl = fresh.page, title = "My Clip", author = null, durationSeconds = 10,
                options = listOf(
                    MediaOption(
                        id = "v720", label = "720p", kind = OptionKind.MERGED,
                        video = StreamRef(fresh.video!!.url, "video/mp4", ".mp4", fresh.video!!.size),
                        audio = StreamRef(fresh.audio!!.url, "audio/mp4", ".m4a", fresh.audio!!.size),
                    ),
                ),
            )
        }
        val m = newManager(listOf(resolver))
        val id = m.enqueue(ResolvedUrls.encode(fresh), emptyMap(), "Refresh.mp4")
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(video + audio), sha256(File(rec.outputPath)))
        assertEquals("the page is looked up again exactly once", 1, resolver.calls)
    }

    @Test
    fun expiredLinkWithoutAResolverEndsNeedsRefresh() = runBlocking {
        server.failAfterRequests = 1
        val m = newManager(emptyList())
        val id = m.enqueue(ResolvedUrls.encode(plan(OptionKind.MERGED)), emptyMap(), "X.mp4")
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(DownloadState.NEEDS_REFRESH, rec.state)
    }

    @Test
    fun pauseAndResumeDoNotFetchFinishedChunksAgain() = runBlocking {
        server.throttleBytesPerSec = 1_000_000
        val m = newManager()
        val id = m.enqueue(ResolvedUrls.encode(plan(OptionKind.MERGED)), emptyMap(), "Resume.mp4")
        store.waitFor(id, 30_000) { it.downloaded >= 8 * 1024 * 1024 }
        m.pause(id)
        store.waitFor(id, 10_000) { it.state == DownloadState.PAUSED }
        // Work items are numbered video chunks first (3 x 8 MiB), then audio chunks (2).
        val chunk = 8L * 1024 * 1024
        val finished = store.getParts(id).filter { it.current > it.end }.map { p ->
            val i = p.start.toInt()
            if (i < 3) "/v.bin" to i * chunk else "/a.bin" to (i - 3) * chunk
        }.toSet()
        assertTrue("some chunk must be finished at pause", finished.isNotEmpty())
        val requestsBefore = server.recorded.size
        server.throttleBytesPerSec = 0
        m.resume(id)
        val rec = store.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(video + audio), sha256(File(rec.outputPath)))
        val again = server.recorded.drop(requestsBefore).mapNotNull { r ->
            val range = r.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value ?: return@mapNotNull null
            r.path.substringBefore('?') to range.removePrefix("bytes=").substringBefore('-').toLong()
        }.toSet()
        assertTrue("finished chunks $finished were requested again: $again", again.none { it in finished })
    }
}
