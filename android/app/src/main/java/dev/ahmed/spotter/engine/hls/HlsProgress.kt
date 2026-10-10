package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState

object HlsProgress {
    fun isSegmentMode(rec: DownloadRecord): Boolean {
        if (rec.state == DownloadState.DONE) return false
        val mime = rec.mimeType?.lowercase()?.substringBefore(';')?.trim()
        return mime == HlsHeaders.MIME_PLAYLIST || mime == HlsHeaders.MIME_PLAYLIST_ALT
    }

    fun line(downloaded: Long, size: Long): String = "$downloaded / $size segments"
}
