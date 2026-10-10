package dev.ahmed.spotter.engine.store

import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.PartRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Behaves like Room: every suspend call hops dispatchers with `withContext`, which throws immediately when the caller
 * is already cancelled. The plain [InMemoryDownloadStore] never does, which hid the "stuck in RUNNING after pause" bug.
 */
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

/** After [freeze], progress writes (parts/records) are dropped: simulates the process dying with no final persist. */
class FreezableStore(private val d: DownloadStore) : DownloadStore by d {
    @Volatile var frozen = false
    override suspend fun update(record: DownloadRecord) { if (!frozen) d.update(record) }
    override suspend fun replaceParts(downloadId: Long, parts: List<PartRecord>) { if (!frozen) d.replaceParts(downloadId, parts) }
}
