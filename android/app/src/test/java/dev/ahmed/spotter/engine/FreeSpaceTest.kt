package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.http.HttpDownloader
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.sink.FreeSpace
import dev.ahmed.spotter.engine.sink.InsufficientSpaceException
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
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

class FreeSpaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: RangeTestServer
    private lateinit var store: InMemoryDownloadStore
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        server = RangeTestServer()
        store = InMemoryDownloadStore()
        val dir = tmp.newFolder("out")
        manager = DownloadManager(
            store = store,
            outputDir = dir,
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
            freeSpace = FreeSpace { 0L },
        )
    }

    @After
    fun tearDown() = runBlocking {
        manager.shutdown()
        supervisor.cancelAndJoin()
        server.close()
    }

    @Test
    fun checkThrowsWhenShort() {
        try {
            FreeSpace { 100 }.check(1024 * 1024, "/tmp")
            error("expected InsufficientSpaceException")
        } catch (e: InsufficientSpaceException) {
            assertEquals(1024 * 1024, e.needed)
            assertTrue(e.message!!.contains("Not enough free space"))
        }
    }

    @Test
    fun downloadFailsWithClearErrorBeforeWriting() = runBlocking {
        val body = randomBytes(64 * 1024, seed = 91)
        server.body = body
        val id = manager.enqueue(server.url("/space.bin"), filename = "space.bin")
        val rec = store.waitTerminal(id, timeoutMs = 20_000)
        assertEquals(DownloadState.ERROR, rec.state)
        val msg = EngineErrors.userMessage(rec.error)
        assertTrue("msg=$msg", msg.startsWith("Not enough free space"))
        assertTrue(server.requestCount <= 2)
    }

    @Test
    fun httpBufferIsBounded() {
        assertEquals(64 * 1024, HttpDownloader.BUFFER)
    }
}
