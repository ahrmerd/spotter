package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.RangeTestServer
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import dev.ahmed.spotter.engine.waitTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class HlsFixtureTest {
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
        manager = DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 20, writeTimeoutSeconds = 20),
            hlsRemuxer = FfmpegCliRemuxer(),
        )
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

    @Test
    fun plainTsPlays() = runBlocking { playCase("plain", "prog.m3u8", expectAudio = true) }

    @Test
    fun aes128SequenceIvPlays() = runBlocking { playCase("aes", "prog.m3u8", expectAudio = true) }

    @Test
    fun fmp4MapPlays() = runBlocking { playCase("fmp4", "prog.m3u8", expectAudio = true) }

    @Test
    fun masterSeparateAudioPlays() = runBlocking {
        playCase("master", "master.m3u8", expectAudio = true, extraHeaders = mapOf(
            HlsHeaders.VARIANT to server.url("/v/prog.m3u8"),
            HlsHeaders.AUDIO to server.url("/a/prog.m3u8"),
        ))
    }

    @Test
    fun jpgSegmentsPlay() = runBlocking { playCase("jpg", "prog.m3u8", expectAudio = true) }

    @Test
    fun junkPrefixPlays() = runBlocking { playCase("junk", "prog.m3u8", expectAudio = true) }

    private suspend fun playCase(
        dirName: String,
        playlistName: String,
        expectAudio: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val (assets, playlistPath) = loadAssets(dirName, playlistName)
        server.assets = assets
        val headers = captured + extraHeaders.mapValues { (_, v) ->
            if (v.startsWith("http")) v else v
        }
        val id = manager.enqueue(server.url(playlistPath), headers, "$dirName.m3u8")
        val rec = store.waitTerminal(id, timeoutMs = 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val out = File(rec.outputPath)
        assertTrue(out.exists() && out.length() > 0)
        val probe = ffprobe(out)
        val duration = probeDuration(out)
        val types = probeCodecTypes(out)
        val decodeLog = ffmpegNull(out)
        println("M4 fixture=$dirName file=${out.name} size=${out.length()} duration=$duration streams=$types mime=${rec.mimeType}")
        println("M4 ffprobe $dirName:\n$probe")
        println("M4 ffmpeg-null $dirName: '${decodeLog.replace("\n", " | ")}'")
        assertTrue("duration $duration not within 1s of 2s", kotlin.math.abs(duration - 2.0) <= 1.0)
        assertTrue("missing video in $types", types.any { it.contains("video") })
        if (expectAudio) {
            assertTrue("missing audio in $types", types.any { it.contains("audio") })
        }
        assertTrue("ffmpeg decode errors: $decodeLog", decodeLog.isBlank())
        assertTrue(server.recorded.any { it.path.substringBefore('?').endsWith(playlistName) })
        assertTrue(server.recorded.any { it.path.substringBefore('?').contains("seg") || it.path.contains("init") })
        if (dirName == "aes") {
            assertTrue(server.recorded.any { it.path.substringBefore('?').endsWith("enc.key") })
        }
    }

    private fun loadAssets(dirName: String, playlistName: String): Pair<Map<String, ByteArray>, String> {
        val url = requireNotNull(javaClass.classLoader!!.getResource("hls/$dirName/$playlistName")) {
            "missing fixture hls/$dirName/$playlistName"
        }
        val playlistFile = File(url.toURI())
        val root = requireNotNull(playlistFile.parentFile) { "fixture $dirName has no parent" }
        val assets = root.walkTopDown().filter { it.isFile }.associate { f ->
            "/" + f.relativeTo(root).invariantSeparatorsPath to f.readBytes()
        }
        return assets to "/$playlistName"
    }

    private fun ffprobe(file: File): String {
        val proc = ProcessBuilder(
            "ffprobe", "-hide_banner", "-show_entries",
            "format=duration,format_name,size:stream=index,codec_type,codec_name,width,height",
            "-of", "default=noprint_wrappers=0",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor(20, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun probeDuration(file: File): Double {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        proc.waitFor(20, TimeUnit.SECONDS)
        return out.toDoubleOrNull() ?: -1.0
    }

    private fun probeCodecTypes(file: File): List<String> {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        proc.waitFor(20, TimeUnit.SECONDS)
        return out.lines().filter { it.isNotBlank() }
    }

    private fun ffmpegNull(file: File): String {
        val proc = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        proc.waitFor(30, TimeUnit.SECONDS)
        return out
    }
}
