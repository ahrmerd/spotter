package dev.ahmed.spotter.engine.hls

import java.io.File
import java.io.IOException

data class RemuxResult(
    val output: File,
    val mime: String,
    val note: String?,
)

fun interface HlsRemuxer {
    /**
     * Mux [video] (MPEG-TS or fMP4) and optional [audio] into [destMp4].
     * On codec failure return the original container with a user-facing [RemuxResult.note].
     */
    fun remux(video: File, audio: File?, destMp4: File): RemuxResult
}

object KeepTsRemuxer : HlsRemuxer {
    override fun remux(video: File, audio: File?, destMp4: File): RemuxResult {
        if (audio != null) {
            throw IOException("separate audio rendition requires remux")
        }
        return RemuxResult(video, HlsHeaders.MIME_TS, "Could not remux to MP4 on this runtime. Kept MPEG-TS.")
    }
}
