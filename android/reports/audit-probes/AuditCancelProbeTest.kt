package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.*
import dev.ahmed.spotter.engine.net.HttpClients
import dev.ahmed.spotter.engine.queue.DownloadManager
import dev.ahmed.spotter.engine.store.DownloadStore
import dev.ahmed.spotter.engine.store.InMemoryDownloadStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Mimics Room: suspend DAO calls hop to another dispatcher via withContext, which throws immediately
// when the calling coroutine is already cancelled (this is what happens on a real device).
class RoomLikeStore(private val d: DownloadStore) : DownloadStore {
    private suspend fun <T> io(b: suspend () -> T): T = withContext(Dispatchers.IO) { b() }
    override suspend fun insert(record: DownloadRecord) = io { d.insert(record) }
    override suspend fun update(record: DownloadRecord) = io { d.update(record) }
    override suspend fun get(id: Long) = io { d.get(id) }
    override suspend fun getAll() = io { d.getAll() }
    override fun observeAll(): Flow<List<DownloadRecord>> = d.observeAll()
    override suspend fun delete(id: Long) = io { d.delete(id) }
    override suspend fun replaceParts(downloadId: Long, parts: List<PartRecord>) = io { d.replaceParts(downloadId, parts) }
    override suspend fun getParts(downloadId: Long) = io { d.getParts(downloadId) }
    override suspend fun getSetting(key: String) = io { d.getSetting(key) }
    override suspend fun setSetting(key: String, value: String) = io { d.setSetting(key, value) }
    override suspend fun loadSettings() = io { d.loadSettings() }
}

class AuditCancelProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pauseWithRoomLikeStoreEndsPaused() = runBlocking {
        val server = RangeTestServer()
        server.body = randomBytes(3 * 1024 * 1024, seed = 21)
        server.throttleBytesPerSec = 400_000
        val inner = InMemoryDownloadStore()
        val store = RoomLikeStore(inner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = DownloadManager(store = store, outputDir = tmp.newFolder("o"), scope = scope, client = HttpClients.create(readTimeoutSeconds = 15, writeTimeoutSeconds = 15))
        val id = m.enqueue(server.url("/p.bin"))
        delay(2500)
        val before = inner.getParts(id).sumOf { it.current - it.start }
        m.pause(id)
        delay(2000)
        val rec = inner.get(id)!!
        val partsAfter = inner.getParts(id).sumOf { it.current - it.start }
        println("AUDIT cancel state=${rec.state} persistedBefore=$before persistedAfter=$partsAfter downloadedField=${rec.downloaded}")
        assertEquals(DownloadState.PAUSED, rec.state)
        m.shutdown(); server.close()
    }
}
