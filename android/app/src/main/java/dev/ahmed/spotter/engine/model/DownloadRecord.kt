package dev.ahmed.spotter.engine.model

data class DownloadRecord(
    val id: Long = 0,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val filename: String = "",
    val outputPath: String = "",
    val state: DownloadState = DownloadState.QUEUED,
    val size: Long = 0,
    val downloaded: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val ranged: Boolean = false,
    val mimeType: String? = null,
    val error: String? = null,
    val createdAt: Long = 0,
)
