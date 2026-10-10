package dev.ahmed.spotter.engine.store

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val headersJson: String,
    val filename: String,
    val outputPath: String,
    val state: String,
    val size: Long,
    val downloaded: Long,
    val etag: String?,
    val lastModified: String?,
    val ranged: Boolean,
    val mimeType: String?,
    val error: String?,
    val createdAt: Long,
)
