package dev.ahmed.spotter.engine.store

import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.model.PartRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryDownloadStore : DownloadStore {
    private val lock = Mutex()
    private var nextId = 1L
    private val downloads = LinkedHashMap<Long, DownloadRecord>()
    private val parts = HashMap<Long, List<PartRecord>>()
    private val settings = HashMap<String, String>()
    private val all = MutableStateFlow<List<DownloadRecord>>(emptyList())

    override suspend fun insert(record: DownloadRecord): Long = lock.withLock {
        val id = if (record.id == 0L) nextId++ else record.id.also { nextId = maxOf(nextId, it + 1) }
        downloads[id] = record.copy(id = id)
        emitLocked()
        id
    }

    override suspend fun update(record: DownloadRecord) {
        lock.withLock {
            downloads[record.id] = record
            emitLocked()
        }
    }

    override suspend fun get(id: Long): DownloadRecord? = lock.withLock { downloads[id] }

    override suspend fun getAll(): List<DownloadRecord> = lock.withLock { downloads.values.toList() }

    override fun observeAll(): Flow<List<DownloadRecord>> = all.asStateFlow()

    override suspend fun delete(id: Long) {
        lock.withLock {
            downloads.remove(id)
            parts.remove(id)
            emitLocked()
        }
    }

    override suspend fun replaceParts(downloadId: Long, parts: List<PartRecord>) {
        lock.withLock {
            this.parts[downloadId] = parts.map { it.copy(downloadId = downloadId) }
        }
    }

    override suspend fun getParts(downloadId: Long): List<PartRecord> =
        lock.withLock { parts[downloadId].orEmpty() }

    override suspend fun getSetting(key: String): String? = lock.withLock { settings[key] }

    override suspend fun setSetting(key: String, value: String) {
        lock.withLock { settings[key] = value }
    }

    override suspend fun loadSettings(): EngineSettings = lock.withLock {
        EngineSettings(
            maxConcurrentDownloads = settings[EngineSettings.KEY_MAX_CONCURRENT]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_MAX_CONCURRENT,
            connectionsPerFile = settings[EngineSettings.KEY_CONNECTIONS_PER_FILE]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_CONNECTIONS,
            maxTotalConnections = settings[EngineSettings.KEY_MAX_TOTAL_CONNECTIONS]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_MAX_CONNECTIONS,
            speedLimitBps = settings[EngineSettings.KEY_SPEED_LIMIT_BPS]?.toLongOrNull() ?: 0L,
            wifiOnly = settings[EngineSettings.KEY_WIFI_ONLY]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun emitLocked() {
        all.value = downloads.values.toList()
    }
}
