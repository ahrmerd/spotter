package dev.ahmed.spotter.engine.store

import androidx.room.withTransaction
import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.model.EngineSettings
import dev.ahmed.spotter.engine.model.PartRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDownloadStore(private val db: SpotterDatabase) : DownloadStore {
    private val downloads = db.downloadDao()
    private val parts = db.partDao()
    private val settings = db.settingDao()

    override suspend fun insert(record: DownloadRecord): Long = downloads.insert(record.toEntity())

    override suspend fun update(record: DownloadRecord) {
        downloads.update(record.toEntity())
    }

    override suspend fun get(id: Long): DownloadRecord? = downloads.get(id)?.toRecord()

    override suspend fun getAll(): List<DownloadRecord> = downloads.getAll().map { it.toRecord() }

    override fun observeAll(): Flow<List<DownloadRecord>> =
        downloads.observeAll().map { list -> list.map { it.toRecord() } }

    override suspend fun delete(id: Long) {
        db.withTransaction {
            parts.deleteForDownload(id)
            downloads.delete(id)
        }
    }

    override suspend fun replaceParts(downloadId: Long, partsList: List<PartRecord>) {
        db.withTransaction {
            parts.deleteForDownload(downloadId)
            if (partsList.isNotEmpty()) {
                parts.insertAll(partsList.map { it.toEntity(downloadId) })
            }
        }
    }

    override suspend fun getParts(downloadId: Long): List<PartRecord> =
        parts.getForDownload(downloadId).map { it.toRecord() }

    override suspend fun getSetting(key: String): String? = settings.get(key)?.value

    override suspend fun setSetting(key: String, value: String) {
        settings.upsert(SettingEntity(key, value))
    }

    override suspend fun loadSettings(): EngineSettings {
        val rows = settings.getAll().associate { it.key to it.value }
        return EngineSettings(
            maxConcurrentDownloads = rows[EngineSettings.KEY_MAX_CONCURRENT]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_MAX_CONCURRENT,
            connectionsPerFile = rows[EngineSettings.KEY_CONNECTIONS_PER_FILE]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_CONNECTIONS,
            maxTotalConnections = rows[EngineSettings.KEY_MAX_TOTAL_CONNECTIONS]?.toIntOrNull()
                ?: EngineSettings.DEFAULT_MAX_CONNECTIONS,
            speedLimitBps = rows[EngineSettings.KEY_SPEED_LIMIT_BPS]?.toLongOrNull() ?: 0L,
            wifiOnly = rows[EngineSettings.KEY_WIFI_ONLY]?.toBooleanStrictOrNull() ?: false,
        )
    }
}

internal fun DownloadRecord.toEntity() = DownloadEntity(
    id = id,
    url = url,
    headersJson = HeaderJson.encode(headers),
    filename = filename,
    outputPath = outputPath,
    state = state.name,
    size = size,
    downloaded = downloaded,
    etag = etag,
    lastModified = lastModified,
    ranged = ranged,
    mimeType = mimeType,
    error = error,
    createdAt = createdAt,
)

internal fun DownloadEntity.toRecord() = DownloadRecord(
    id = id,
    url = url,
    headers = HeaderJson.decode(headersJson),
    filename = filename,
    outputPath = outputPath,
    state = DownloadState.valueOf(state),
    size = size,
    downloaded = downloaded,
    etag = etag,
    lastModified = lastModified,
    ranged = ranged,
    mimeType = mimeType,
    error = error,
    createdAt = createdAt,
)

internal fun PartRecord.toEntity(downloadId: Long) = PartEntity(
    id = id,
    downloadId = downloadId,
    startByte = start,
    currentByte = current,
    endByte = end,
)

internal fun PartEntity.toRecord() = PartRecord(
    id = id,
    downloadId = downloadId,
    start = startByte,
    current = currentByte,
    end = endByte,
)
