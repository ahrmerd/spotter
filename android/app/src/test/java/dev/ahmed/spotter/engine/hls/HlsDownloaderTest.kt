package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.RangeTestServer
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.net.HeaderUtils
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.sha256
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import dev.ahmed.spotter.engine.waitFor
import dev.ahmed.spotter.engine.waitTerminal
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var supervisor: kotlinx.coroutines.Job
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var manager: DownloadManager

    private val captured = mapOf(
        "Referer" to "https://iframe.example/player",
        "Origin" to "https://iframe.example",
        "Cookie" to "session=secret",
        "User-Agent" to "SpotterTest/1.0",
    )

    @Before
    fun setUp() {
        server = RangeTestServer()
        store = InMemoryDownloadStore()
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        dir = tmp.newFolder("out")
        manager = newManager()
        server.requiredHeaders = mapOf(
            "referer" to "https://iframe.example/player",
            "origin" to "https://iframe.example",
            "cookie" to "session=secret",
            "user-agent" to "SpotterTest/1.0",
        )
    }

    @After
    fun tearDown() = runBlocking {
        manager.shutdown()
        supervisor.cancelAndJoin()
        server.close()
    }

    private fun newManager(remuxer: HlsRemuxer = KeepTsRemuxer): DownloadManager {
        return DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
            hlsRemuxer = remuxer,
        )
    }

    @Test
    fun plainTsHashAndHeadersAndNoSpotterLeak() = runBlocking {
        val segs = (0 until 4).map { tsSegment(it) }
        val playlist = mediaPlaylist(segs.size)
        val assets = mutableMapOf("/prog.m3u8" to playlist.toByteArray())
        segs.forEachIndexed { i, b -> assets["/seg$i.ts"] = b }
        server.assets = assets
        val headers = captured + mapOf(HlsHeaders.VARIANT to "http://should.not.leak/v.m3u8")
        val id = manager.enqueue(server.url("/prog.m3u8"), headers, "clip.m3u8")
        val rec = store.waitTerminal(id)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val expected = sha256(segs.reduce { a, b -> a + b })
        val actual = sha256(File(rec.outputPath))
        assertEquals(expected, actual)
        assertTrue(rec.filename.endsWith(".ts") || rec.filename.endsWith(".mp4"))
        val paths = server.recorded.map { it.path.substringBefore('?') }
        assertTrue(paths.contains("/prog.m3u8"))
        assertTrue(paths.any { it.startsWith("/seg") })
        for (req in server.recorded) {
            assertEquals("https://iframe.example/player", req.headers["referer"])
            assertEquals("https://iframe.example", req.headers["origin"])
            assertEquals("session=secret", req.headers["cookie"])
            assertEquals("SpotterTest/1.0", req.headers["user-agent"])
            assertTrue(req.headers.keys.none { it.startsWith("x-spotter") })
        }
        val merged = HeaderUtils.merge(headers)
        assertTrue(merged.keys.none { it.startsWith("X-Spotter") || it.startsWith("x-spotter") })
        println("M4 plain-ts sha=$actual requests=${server.requestCount} name=${rec.filename} size=${rec.size}")
    }

    @Test
    fun aes128UsesSequenceIv() = runBlocking {
        val key = "0123456789abcdef".toByteArray()
        val segs = (0 until 3).map { i ->
            encrypt(tsSegment(i), key, HlsPlaylist.sequenceIv(i.toLong()))
        }
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
            #EXTINF:1.0,
            seg0.ts
            #EXTINF:1.0,
            seg1.ts
            #EXTINF:1.0,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.assets = mapOf(
            "/prog.m3u8" to playlist.toByteArray(),
            "/enc.key" to key,
            "/seg0.ts" to segs[0],
            "/seg1.ts" to segs[1],
            "/seg2.ts" to segs[2],
        )
        val id = manager.enqueue(server.url("/prog.m3u8"), captured, "aes.ts")
        val rec = store.waitTerminal(id)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val expected = sha256(tsSegment(0) + tsSegment(1) + tsSegment(2))
        assertEquals(expected, sha256(File(rec.outputPath)))
        assertTrue(server.recorded.any { it.path.substringBefore('?') == "/enc.key" })
        println("M4 aes-seq-iv sha=$expected keyHits=${server.recorded.count { it.path.contains("enc.key") }}")
    }

    @Test
    fun liveAndSampleAesFailClosed() = runBlocking {
        server.assets = mapOf(
            "/live.m3u8" to """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                live.ts
            """.trimIndent().toByteArray(),
            "/live.ts" to tsSegment(0),
            "/sample.m3u8" to """
                #EXTM3U
                #EXT-X-KEY:METHOD=SAMPLE-AES,URI="enc.key"
                #EXTINF:1.0,
                seg.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            "/seg.ts" to tsSegment(1),
            "/enc.key" to ByteArray(16),
        )
        val liveId = manager.enqueue(server.url("/live.m3u8"), captured, "live.m3u8")
        val live = store.waitTerminal(liveId)
        assertEquals(DownloadState.ERROR, live.state)
        assertTrue(live.error.orEmpty().contains("Live HLS"))
        assertFalse(server.recorded.any { it.path.substringBefore('?') == "/live.ts" })

        server.resetCounters()
        server.assets = mapOf(
            "/sample.m3u8" to """
                #EXTM3U
                #EXT-X-KEY:METHOD=SAMPLE-AES,URI="enc.key"
                #EXTINF:1.0,
                seg.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            "/seg.ts" to tsSegment(1),
            "/enc.key" to ByteArray(16),
        )
        val sampleId = manager.enqueue(server.url("/sample.m3u8"), captured, "sample.m3u8")
        val sample = store.waitTerminal(sampleId)
        assertEquals(DownloadState.ERROR, sample.state)
        assertTrue(sample.error.orEmpty().contains("SAMPLE-AES"))
        assertFalse(server.recorded.any { it.path.substringBefore('?') == "/seg.ts" })
        println("M4 live error=${live.error} sample error=${sample.error}")
    }

    @Test
    fun jpgNameAndJunkPrefixAssemble() = runBlocking {
        val payload = tsSegment(7, packets = 5)
        val junk = "JUNKHEADER!!!!".toByteArray()
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:1
            #EXTINF:1.0,
            seg0.jpg
            #EXT-X-ENDLIST
        """.trimIndent()
        server.assets = mapOf(
            "/prog.m3u8" to playlist.toByteArray(),
            "/seg0.jpg" to (junk + payload),
        )
        val id = manager.enqueue(server.url("/prog.m3u8"), captured, "pic.m3u8")
        val rec = store.waitTerminal(id)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(payload), sha256(File(rec.outputPath)))
        println("M4 jpg-junk sha=${sha256(File(rec.outputPath))} len=${File(rec.outputPath).length()}")
    }

    @Test
    fun resumeAtSegment40MatchesStraight() = runBlocking {
        val n = 100
        val segs = (0 until n).map { tsSegment(it) }
        val expected = sha256(segs.reduce { a, b -> a + b })
        val assets = mutableMapOf("/prog.m3u8" to mediaPlaylist(n).toByteArray())
        segs.forEachIndexed { i, b -> assets["/seg$i.ts"] = b }

        server.assets = assets
        val straightId = manager.enqueue(server.url("/prog.m3u8"), captured, "straight.m3u8")
        val straight = store.waitTerminal(straightId)
        assertEquals(straight.error, DownloadState.DONE, straight.state)
        val straightHash = sha256(File(straight.outputPath))
        assertEquals(expected, straightHash)

        manager.setSettings(EngineSettings(speedLimitBps = 6_000))
        val resumeId = manager.enqueue(server.url("/prog.m3u8"), captured, "resume.m3u8")
        store.waitFor(resumeId, timeoutMs = 60_000) {
            it.downloaded >= 40 && it.state == DownloadState.RUNNING
        }
        val mid = store.get(resumeId)!!
        assertTrue("progress should be segments not bytes, got downloaded=${mid.downloaded} size=${mid.size} mime=${mid.mimeType}",
            mid.size == 100L && mid.downloaded in 40L..90L)
        assertEquals(HlsHeaders.MIME_PLAYLIST, mid.mimeType)
        manager.pause(resumeId)
        store.waitFor(resumeId) { it.state == DownloadState.PAUSED || it.state == DownloadState.DONE }
        if (store.get(resumeId)!!.state == DownloadState.PAUSED) {
            manager.resume(resumeId)
        }
        val resumed = store.waitTerminal(resumeId, timeoutMs = 90_000)
        assertEquals(resumed.error, DownloadState.DONE, resumed.state)
        val resumeHash = sha256(File(resumed.outputPath))
        assertEquals(straightHash, resumeHash)
        println("M4 resume-40/100 straight=$straightHash resume=$resumeHash mid=${mid.downloaded}/${mid.size} mime=${mid.mimeType}")
    }

    @Test
    fun masterPicksBestVariant() = runBlocking {
        val low = tsSegment(1, packets = 3)
        val high = tsSegment(2, packets = 3)
        server.assets = mapOf(
            "/master.m3u8" to """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=160x120
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=8000,RESOLUTION=320x240
                high.m3u8
            """.trimIndent().toByteArray(),
            "/low.m3u8" to mediaPlaylist(1, prefix = "l").toByteArray(),
            "/high.m3u8" to mediaPlaylist(1, prefix = "h").toByteArray(),
            "/l0.ts" to low,
            "/h0.ts" to high,
        )
        val id = manager.enqueue(server.url("/master.m3u8"), captured, "best.m3u8")
        val rec = store.waitTerminal(id)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(high), sha256(File(rec.outputPath)))
        assertTrue(server.recorded.any { it.path.startsWith("/high.m3u8") })
        assertFalse(server.recorded.any { it.path.startsWith("/l0.ts") })
        println("M4 master-best sha=${sha256(high)}")
    }

    @Test
    fun drmFailsWithoutFetchingSegment() = runBlocking {
        server.assets = mapOf(
            "/drm.m3u8" to """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"
                #EXTINF:1.0,
                seg.ts
                #EXT-X-ENDLIST
            """.trimIndent().toByteArray(),
            "/seg.ts" to tsSegment(0),
        )
        val id = manager.enqueue(server.url("/drm.m3u8"), captured, "drm.m3u8")
        val rec = store.waitTerminal(id)
        assertEquals(DownloadState.ERROR, rec.state)
        assertTrue(rec.error.orEmpty().contains("DRM"))
        assertFalse(server.recorded.any { it.path.substringBefore('?') == "/seg.ts" })
        println("M4 drm error=${rec.error}")
    }

    private fun mediaPlaylist(count: Int, prefix: String = "seg"): String {
        val body = (0 until count).joinToString("\n") { "#EXTINF:1.0,\n$prefix$it.ts" }
        return """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            $body
            #EXT-X-ENDLIST
        """.trimIndent()
    }

    private fun tsSegment(marker: Int, packets: Int = 2): ByteArray {
        val out = ByteArray(packets * HlsTs.PACKET)
        for (i in 0 until packets) {
            val off = i * HlsTs.PACKET
            out[off] = HlsTs.SYNC
            out[off + 1] = marker.toByte()
            out[off + 2] = i.toByte()
        }
        return out
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }
}
