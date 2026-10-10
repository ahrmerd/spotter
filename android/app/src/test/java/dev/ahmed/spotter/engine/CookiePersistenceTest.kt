package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.net.CookieSource
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.HeaderJson
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Audit finding B2: Cookie / Authorization must never be persisted, but downloads still need the cookie. */
class CookiePersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun secretsAreNeverEncoded() {
        val json = HeaderJson.encode(
            mapOf(
                "Cookie" to "sid=SECRET",
                "authorization" to "Bearer SECRET",
                "Proxy-Authorization" to "x",
                "Referer" to "https://a.example/",
                "User-Agent" to "UA",
            ),
        )
        assertFalse(json, json.contains("SECRET"))
        assertFalse(json.lowercase().contains("cookie"))
        assertFalse(json.lowercase().contains("authorization"))
        val back = HeaderJson.decode(json)
        assertEquals(setOf("Referer", "User-Agent"), back.keys)
    }

    @Test
    fun cookieIsReadAtRequestTimeNotFromStoredHeaders() = runBlocking {
        val server = RangeTestServer()
        server.body = randomBytes(300 * 1024, seed = 77)
        val store = InMemoryDownloadStore()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.IO)
        val live = arrayOf("sid=one")
        val manager = DownloadManager(
            store = store,
            outputDir = tmp.newFolder("o"),
            scope = scope,
            client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15),
            cookies = CookieSource { live[0] },
        )
        try {
            val id = manager.enqueue(server.url("/c.bin"), headers = mapOf("Referer" to "https://ref.example/"))
            val rec = store.waitTerminal(id, 30_000)
            assertEquals(rec.error, DownloadState.DONE, rec.state)
            val cookies = server.recorded.mapNotNull { r -> r.headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value }
            assertTrue("every request must carry the live cookie: $cookies", cookies.isNotEmpty() && cookies.all { it == "sid=one" })
            assertEquals("the stored record must not hold the cookie", null, rec.headers.keys.firstOrNull { it.equals("Cookie", true) })
        } finally {
            manager.shutdown()
            supervisor.cancelAndJoin()
            server.close()
        }
    }
}
