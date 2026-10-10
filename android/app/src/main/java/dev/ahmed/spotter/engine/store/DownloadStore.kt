package dev.ahmed.spotter.engine.store

import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.model.PartRecord
import kotlinx.coroutines.flow.Flow

interface DownloadStore {
    suspend fun insert(record: DownloadRecord): Long
    suspend fun update(record: DownloadRecord)
    suspend fun get(id: Long): DownloadRecord?
    suspend fun getAll(): List<DownloadRecord>
    fun observeAll(): Flow<List<DownloadRecord>>
    suspend fun delete(id: Long)

    suspend fun replaceParts(downloadId: Long, parts: List<PartRecord>)
    suspend fun getParts(downloadId: Long): List<PartRecord>

    suspend fun getSetting(key: String): String?
    suspend fun setSetting(key: String, value: String)
    suspend fun loadSettings(): EngineSettings
}
