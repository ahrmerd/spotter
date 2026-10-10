package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.DownloadStore
import dev.ahmed.spotter.engine.store.FreezableStore
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import dev.ahmed.spotter.engine.store.RoomLikeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** Regression tests for the M1 audit findings F1, F2, F3, F4, F7 (see android/reports/M1-audit.md). */
class M1FixesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var inner: InMemoryDownloadStore
    private lateinit var supervisor: Job
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private var manager: DownloadManager? = null

    @Before
    fun setUp() {
        server = RangeTestServer()
        inner = InMemoryDownloadStore()
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

    private fun newManager(store: DownloadStore): DownloadManager =
        DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
        ).also { manager = it }

    private suspend fun restart(store: DownloadStore): DownloadManager {
        manager?.shutdown()
        supervisor.cancelAndJoin()
        supervisor = SupervisorJob()
        scope = CoroutineScope(supervisor + Dispatchers.IO)
        return newManager(store)
    }

    // F1
    @Test
    fun noRangeStreamDroppedMidwayStillProducesCorrectFile() = runBlocking {
        val body = randomBytes(512 * 1024, seed = 11)
        server.body = body
        server.rangeSupport = false
        server.disconnectAfterBytes = 32 * 1024
        server.disconnectEveryNthRequest = 2 // first real GET is cut after 32 KiB, the retry is whole
        val m = newManager(inner)
        val id = m.enqueue(server.url("/nr.bin"))
        val rec = inner.waitTerminal(id, 60_000)
        println("FIX F1 state=${rec.state} requests=${server.requestCount} sha=${sha256(File(rec.outputPath))}")
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertTrue("a retry must have happened, requests=${server.requestCount}", server.requestCount >= 3)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    @Test
    fun noRangePauseThenResumeRestartsFromZeroWithCorrectHash() = runBlocking {
        val body = randomBytes(600 * 1024, seed = 14)
        server.body = body
        server.rangeSupport = false
        server.throttleBytesPerSec = 200_000
        val m = newManager(inner)
        val id = m.enqueue(server.url("/nr2.bin"))
        inner.waitFor(id, 20_000) { it.downloaded >= 100_000 }
        m.pause(id)
        inner.waitFor(id, 10_000) { it.state == DownloadState.PAUSED }
        server.throttleBytesPerSec = 0
        m.resume(id)
        val rec = inner.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    // F2
    @Test
    fun pauseEndsPausedWhenStoreHonoursCancellation_thenResumeCompletes() = runBlocking {
        val body = randomBytes(3 * 1024 * 1024, seed = 21)
        server.body = body
        server.throttleBytesPerSec = 400_000
        val store = RoomLikeStore(inner)
        val m = newManager(store)
        val id = m.enqueue(server.url("/p.bin"))
        inner.waitFor(id, 20_000) { it.downloaded > 0 || it.state == DownloadState.RUNNING }
        delay(1500)
        m.pause(id)
        val paused = inner.waitFor(id, 10_000) { it.state == DownloadState.PAUSED }
        assertTrue("progress must be persisted on pause, downloaded=${paused.downloaded}", paused.downloaded > 0)
        server.throttleBytesPerSec = 0
        m.resume(id)
        val rec = inner.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    @Test
    fun staleRunningRecordWithNoJobCanBeResumed() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 22)
        server.body = body
        val m = newManager(inner)
        val id = m.enqueue(server.url("/stale.bin"))
        inner.waitTerminal(id, 30_000)
        val m2 = restart(inner)
        // Fabricate a record stuck in RUNNING with no job behind it.
        val id2 = inner.insert(inner.get(id)!!.copy(id = 0, state = DownloadState.RUNNING, filename = "", outputPath = "", downloaded = 0))
        m2.resume(id2)
        val rec = inner.waitTerminal(id2, 30_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
    }

    // F3
    @Test
    fun resumeAfterPartialFileDeletedRestartsCleanly() = runBlocking {
        val body = randomBytes(3 * 1024 * 1024, seed = 31)
        server.body = body
        server.throttleBytesPerSec = 500_000
        val m = newManager(inner)
        val id = m.enqueue(server.url("/del.bin"))
        inner.waitFor(id, 20_000) { it.downloaded >= 300_000 }
        m.pause(id)
        val paused = inner.waitFor(id, 10_000) { it.state == DownloadState.PAUSED }
        val part = File(paused.outputPath + ".part")
        assertTrue(".part should exist while paused", part.exists())
        assertTrue(part.delete())
        server.throttleBytesPerSec = 0
        m.resume(id)
        val rec = inner.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    @Test
    fun resumeAfterPartialFileTruncatedRestartsCleanly() = runBlocking {
        val body = randomBytes(3 * 1024 * 1024, seed = 32)
        server.body = body
        server.throttleBytesPerSec = 500_000
        val m = newManager(inner)
        val id = m.enqueue(server.url("/trunc.bin"))
        inner.waitFor(id, 20_000) { it.downloaded >= 300_000 }
        m.pause(id)
        val paused = inner.waitFor(id, 10_000) { it.state == DownloadState.PAUSED }
        RandomAccessFile(paused.outputPath + ".part", "rw").use { it.setLength(100_000) }
        server.throttleBytesPerSec = 0
        m.resume(id)
        val rec = inner.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    // F4: hard stop. Nothing is persisted after the freeze, so resume starts from the last periodic snapshot.
    @Test
    fun hardStopWithoutFinalPersistResumesToCorrectHash() = runBlocking {
        val body = randomBytes(4 * 1024 * 1024, seed = 41)
        server.body = body
        server.throttleBytesPerSec = 700_000
        val frozenStore = FreezableStore(inner)
        val m = newManager(frozenStore)
        val id = m.enqueue(server.url("/hard.bin"))
        inner.waitFor(id, 20_000) { it.downloaded >= 700_000 }
        frozenStore.frozen = true
        m.shutdown()
        delay(500)
        val snapshot = inner.getParts(id).map { it.current }.toSet()
        server.throttleBytesPerSec = 0
        val m2 = restart(inner)
        val rec = inner.waitTerminal(id, 60_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
        assertTrue("snapshot should have progress: $snapshot", snapshot.any { it > 0 })
        assertTrue(m2.currentRunning == 0)
    }

    // F7: crash after the rename but before the DB said DONE
    @Test
    fun finishedFileWithRunningRecordIsFinalisedNotFailed() = runBlocking {
        val body = randomBytes(256 * 1024, seed = 51)
        server.body = body
        val m = newManager(inner)
        val id = m.enqueue(server.url("/crash.bin"))
        val done = inner.waitTerminal(id, 30_000)
        assertEquals(DownloadState.DONE, done.state)
        // Pretend we died right after the rename: file is final, record says RUNNING, parts are complete.
        inner.update(done.copy(state = DownloadState.RUNNING))
        val m2 = restart(inner)
        val rec = inner.waitTerminal(id, 30_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }

    @Test
    fun sameUrlTwiceGivesTwoFiles() = runBlocking {
        server.body = randomBytes(64 * 1024, seed = 13)
        val m = newManager(inner)
        val a = m.enqueue(server.url("/same.bin"))
        val b = m.enqueue(server.url("/same.bin"))
        val ra = inner.waitTerminal(a)
        val rb = inner.waitTerminal(b)
        assertTrue(ra.outputPath != rb.outputPath)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun pauseResumeSpamKeepsDataCorrect() = runBlocking {
        val body = randomBytes(3 * 1024 * 1024, seed = 12)
        server.body = body
        server.throttleBytesPerSec = 600_000
        val m = newManager(RoomLikeStore(inner))
        val id = m.enqueue(server.url("/spam.bin"))
        repeat(25) { m.pause(id); m.resume(id); delay(37) }
        server.throttleBytesPerSec = 0
        val rec = inner.waitTerminal(id, 90_000)
        assertEquals(rec.error, DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
    }
}
