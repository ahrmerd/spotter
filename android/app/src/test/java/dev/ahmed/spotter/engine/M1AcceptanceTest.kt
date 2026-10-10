package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.http.PartPlanner
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class M1AcceptanceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var supervisor: Job
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        server = RangeTestServer()
        store = InMemoryDownloadStore()
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        dir = tmp.newFolder("out")
        manager = newManager()
    }

    @After
    fun tearDown() = runBlocking {
        manager.shutdown()
        supervisor.cancelAndJoin()
        server.close()
    }

    private fun newManager(): DownloadManager {
        return DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
        )
    }

    @Test
    fun hundredMegFourPartsHashMatches() = runBlocking {
        val body = randomBytes(100 * 1024 * 1024, seed = 1)
        val expected = sha256(body)
        server.body = body
        val id = manager.enqueue(server.url("/hundred.bin"))
        val rec = store.waitTerminal(id, timeoutMs = 120_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val out = File(rec.outputPath)
        assertTrue(out.exists())
        val actual = sha256(out)
        println("M1 100MB sha256 source=$expected output=$actual size=${out.length()} requests=${server.requestCount} bytesServed=${server.bytesServed}")
        assertEquals(expected, actual)
        val parts = store.getParts(id)
        // Initial split is 4; work stealing can add a fifth if one part finishes early.
        val planned = PartPlanner.initialParts(body.size.toLong(), ranged = true, connections = 4)
        assertEquals(4, planned.size)
        val rangeStarts = server.recorded.mapNotNull { req ->
            req.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        }.toSet()
        for (p in planned) {
            assertTrue("missing initial range start ${p.start} in $rangeStarts", p.start in rangeStarts)
        }
        assertTrue("expected at least 4 parts, got ${parts.size}", parts.size >= 4)
    }

    @Test
    fun secondDownloadSurvivesStaleKeepAlive() = runBlocking {
        server.keepAlive = true
        val body = randomBytes(256 * 1024, seed = 11)
        server.body = body
        val first = manager.enqueue(server.url("/keep.bin"), filename = "keep.bin")
        val rec1 = store.waitTerminal(first, timeoutMs = 30_000)
        assertEquals(rec1.error, DownloadState.DONE, rec1.state)
        server.dropKeepAlives()
        val second = manager.enqueue(server.url("/keep.bin"), filename = "keep2.bin")
        val rec2 = store.waitTerminal(second, timeoutMs = 30_000)
        println("M1 stale-keepalive second state=${rec2.state} error=${rec2.error} requests=${server.requestCount}")
        assertEquals(rec2.error, DownloadState.DONE, rec2.state)
        assertEquals(sha256(body), sha256(File(rec2.outputPath)))
    }

    @Test
    fun resumeAfterSimulatedKillDoesNotRedownloadWrittenBytes() = runBlocking {
        val body = randomBytes(2 * 1024 * 1024, seed = 2)
        val expected = sha256(body)
        server.body = body
        server.throttleBytesPerSec = 400_000
        val id = manager.enqueue(server.url("/resume.bin"))
        val partial = store.waitFor(id, timeoutMs = 30_000) { it.downloaded >= 256 * 1024 }
        val persisted = partial.downloaded
        val servedAtKill = server.bytesServed
        println("M1 kill at downloaded=$persisted bytesServed=$servedAtKill")
        manager.shutdown()
        supervisor.cancelAndJoin()
        val servedAfterShutdown = server.bytesServed
        val requestsBefore = server.recorded.size
        val persistedCurrents = store.getParts(id).map { it.current }.toSet()
        server.throttleBytesPerSec = 0
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        manager = newManager()
        val rec = store.waitTerminal(id, timeoutMs = 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val actual = sha256(File(rec.outputPath))
        println("M1 resume sha256 source=$expected output=$actual persisted=$persisted servedAtKill=$servedAtKill servedAfterShutdown=$servedAfterShutdown totalServed=${server.bytesServed} requests=${server.requestCount}")
        assertEquals(expected, actual)
        // Tight check: every ranged GET after the restart must start exactly at a persisted part offset, i.e. nothing
        // that was already written gets requested again (the 0-0 validation probe is excluded).
        val resumedStarts = server.recorded.drop(requestsBefore)
            .mapNotNull { r -> r.headers.entries.firstOrNull { it.key.equals("Range", true) }?.value }
            .filter { it != "bytes=0-0" }
            .map { it.removePrefix("bytes=").substringBefore('-').toLong() }
        println("M1 resume ranges starts=$resumedStarts persistedCurrents=$persistedCurrents")
        assertTrue("resumed requests must be non-empty", resumedStarts.isNotEmpty())
        assertTrue("resume re-requested written bytes: starts=$resumedStarts persisted=$persistedCurrents", persistedCurrents.containsAll(resumedStarts))
        assertTrue("must not re-download the whole file: served=${server.bytesServed}", server.bytesServed < body.size * 2)
    }

    @Test
    fun randomDisconnectsStillCompleteWithCorrectHash() = runBlocking {
        val body = randomBytes(512 * 1024, seed = 3)
        val expected = sha256(body)
        server.body = body
        server.disconnectEveryNthRequest = 2
        server.disconnectAfterBytes = 32 * 1024
        val id = manager.enqueue(server.url("/flaky.bin"))
        val rec = store.waitTerminal(id, timeoutMs = 120_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val actual = sha256(File(rec.outputPath))
        println("M1 flaky sha256 source=$expected output=$actual requests=${server.requestCount} bytesServed=${server.bytesServed}")
        assertEquals(expected, actual)
        assertTrue("flaky server should cause retries, requests=${server.requestCount}", server.requestCount > 2)
    }

    @Test
    fun etagChangeOnResumeRestartsWithNewHash() = runBlocking {
        val bodyA = randomBytes(256 * 1024, seed = 4)
        val bodyB = randomBytes(256 * 1024, seed = 5)
        server.body = bodyA
        server.etag = "\"v1\""
        server.throttleBytesPerSec = 80_000
        val id = manager.enqueue(server.url("/etag.bin"))
        store.waitFor(id, timeoutMs = 20_000) { it.downloaded >= 48_000 }
        manager.pause(id)
        store.waitFor(id, timeoutMs = 10_000) { it.state == DownloadState.PAUSED }
        server.throttleBytesPerSec = 0
        server.body = bodyB
        server.etag = "\"v2\""
        manager.resume(id)
        val rec = store.waitTerminal(id, timeoutMs = 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        val actual = sha256(File(rec.outputPath))
        val hashB = sha256(bodyB)
        println("M1 etag-restart sha256 new=$hashB output=$actual old=${sha256(bodyA)} requests=${server.requestCount}")
        assertEquals(hashB, actual)
    }

    @Test
    fun noRangeCompletesViaSingleStream() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 6)
        val expected = sha256(body)
        server.body = body
        server.rangeSupport = false
        val id = manager.enqueue(server.url("/norange.bin"))
        val rec = store.waitTerminal(id)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(expected, sha256(File(rec.outputPath)))
        val parts = store.getParts(id)
        assertEquals(1, parts.size)
        val rangeHeaders = server.recorded.count { it.headers.entries.any { h -> h.key.equals("Range", true) && h.value != "bytes=0-0" } }
        println("M1 no-range hash=$expected parts=${parts.size} requests=${server.requestCount} nonProbeRangeHeaders=$rangeHeaders")
        assertTrue("single stream should not issue parallel ranges", rangeHeaders <= 1)
    }

    @Test
    fun http403EndsNeedsRefreshWithoutRetryStorm() = runBlocking {
        val body = randomBytes(8 * 1024 * 1024, seed = 7)
        server.body = body
        server.failAfterRequests = 3
        server.failStatus = 403
        val id = manager.enqueue(server.url("/forbidden.bin"))
        val rec = store.waitTerminal(id)
        println("M1 403 state=${rec.state} error=${rec.error} requests=${server.requestCount}")
        assertEquals(DownloadState.NEEDS_REFRESH, rec.state)
        assertTrue("retry storm: ${server.requestCount} requests", server.requestCount <= 8)
        val forbidden = server.recorded.size
        assertTrue(forbidden >= 3)
    }

    @Test
    fun tenQueuedNeverMoreThanThreeRunning() = runBlocking {
        val body = randomBytes(96 * 1024, seed = 8)
        server.body = body
        server.contentDisposition = null
        server.throttleBytesPerSec = 48_000
        manager.setSettings(EngineSettings(maxConcurrentDownloads = 3, connectionsPerFile = 1))
        val ids = (0 until 10).map { i ->
            manager.enqueue(server.url("/q$i.bin"))
        }
        for (id in ids) {
            val rec = store.waitTerminal(id, timeoutMs = 120_000)
            assertEquals("id=$id ${rec.error}", DownloadState.DONE, rec.state)
        }
        println("M1 queue peakRunning=${manager.peakRunning} current=${manager.currentRunning} requests=${server.requestCount}")
        assertTrue("peakRunning=${manager.peakRunning}", manager.peakRunning <= 3)
        assertEquals("the limit must actually be reached, otherwise this test proves nothing", 3, manager.peakRunning)
        assertEquals(0, manager.currentRunning)
    }
}
