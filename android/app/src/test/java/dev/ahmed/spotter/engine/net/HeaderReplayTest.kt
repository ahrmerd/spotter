package dev.ahmed.spotter.engine.net

import dev.ahmed.spotter.engine.RangeTestServer
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.randomBytes
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import dev.ahmed.spotter.engine.waitTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeaderReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun capturedHeadersAreReplayedOnEveryRequest() = runBlocking {
        RangeTestServer().use { server ->
            server.body = randomBytes(32 * 1024, seed = 9)
            val store = InMemoryDownloadStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = DownloadManager(
                store = store,
                outputDir = tmp.newFolder(),
                scope = scope,
                client = HttpClients.create(),
            )
            try {
                val headers = mapOf(
                    "Referer" to "https://iframe.example/player",
                    "Origin" to "https://iframe.example",
                    "Cookie" to "session=secret",
                    "User-Agent" to "SpotterTest/1.0",
                )
                val id = manager.enqueue(server.url("/hdr.bin"), headers)
                val rec = store.waitTerminal(id)
                assertEquals(rec.error, DownloadState.DONE, rec.state)
                assertTrue(server.recorded.size >= 2)
                for (req in server.recorded) {
                    assertEquals("https://iframe.example/player", req.headers["referer"])
                    assertEquals("https://iframe.example", req.headers["origin"])
                    assertEquals("session=secret", req.headers["cookie"])
                    assertEquals("SpotterTest/1.0", req.headers["user-agent"])
                }
                println("M1 header-replay requests=${server.recorded.size}")
            } finally {
                manager.shutdown()
                scope.cancel()
            }
        }
    }
}
