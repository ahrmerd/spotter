package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Auditor probe (copy into app/src/test/java/dev/ahmed/spotter/engine/ to run). Not part of the build.
class AuditProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mgr(store: InMemoryDownloadStore, dir: File, scope: CoroutineScope) =
        DownloadManager(store = store, outputDir = dir, scope = scope, client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15))

    @Test
    fun noRangeServerDropsMidStreamThenRetries() = runBlocking {
        val server = RangeTestServer()
        val body = randomBytes(512 * 1024, seed = 11)
        server.body = body
        server.rangeSupport = false
        server.disconnectAfterBytes = 32 * 1024
        server.disconnectEveryNthRequest = 2 // request 2 (first real GET) is cut after 32 KiB, request 3 is whole
        val store = InMemoryDownloadStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = mgr(store, tmp.newFolder("o1"), scope)
        val id = m.enqueue(server.url("/nr.bin"))
        val rec = store.waitTerminal(id, 60_000)
        println("AUDIT norange-drop state=${rec.state} err=${rec.error} requests=${server.requestCount}")
        if (rec.state == DownloadState.DONE) assertEquals(sha256(body), sha256(File(rec.outputPath)))
        m.shutdown(); server.close()
    }

    @Test
    fun pauseResumeSpam() = runBlocking {
        val server = RangeTestServer()
        val body = randomBytes(3 * 1024 * 1024, seed = 12)
        server.body = body
        server.throttleBytesPerSec = 600_000
        val store = InMemoryDownloadStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = mgr(store, tmp.newFolder("o2"), scope)
        val id = m.enqueue(server.url("/spam.bin"))
        repeat(25) { m.pause(id); m.resume(id); delay(37) }
        m.pause(id); delay(300); m.resume(id)
        server.throttleBytesPerSec = 0
        val rec = store.waitTerminal(id, 90_000)
        println("AUDIT spam state=${rec.state} err=${rec.error} requests=${server.requestCount} served=${server.bytesServed}")
        assertEquals(DownloadState.DONE, rec.state)
        assertEquals(sha256(body), sha256(File(rec.outputPath)))
        m.shutdown(); server.close()
    }

    @Test
    fun sameUrlTwiceDifferentFiles() = runBlocking {
        val server = RangeTestServer()
        server.body = randomBytes(64 * 1024, seed = 13)
        val store = InMemoryDownloadStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = tmp.newFolder("o3")
        val m = mgr(store, dir, scope)
        val a = m.enqueue(server.url("/same.bin")); val b = m.enqueue(server.url("/same.bin"))
        val ra = store.waitTerminal(a); val rb = store.waitTerminal(b)
        println("AUDIT same a=${ra.outputPath} b=${rb.outputPath} files=${dir.list()?.sorted()}")
        assertEquals(2, dir.listFiles()!!.size)
        m.shutdown(); server.close()
    }
}
