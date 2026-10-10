package dev.ahmed.spotter.engine.hls

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Test-only remuxer. The app uses [MediaMuxerRemuxer] on device. */
class FfmpegCliRemuxer : HlsRemuxer {
    override fun remux(video: File, audio: File?, destMp4: File): RemuxResult {
        destMp4.parentFile?.mkdirs()
        val cmd = mutableListOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", video.absolutePath)
        if (audio != null) cmd += listOf("-i", audio.absolutePath)
        cmd += listOf("-c", "copy", "-movflags", "+faststart", destMp4.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IOException("ffmpeg remux timed out")
        }
        if (proc.exitValue() != 0 || !destMp4.exists() || destMp4.length() == 0L) {
            return RemuxResult(
                video,
                HlsHeaders.MIME_TS,
                "ffmpeg remux failed: ${log.take(300).ifBlank { "exit ${proc.exitValue()}" }}",
            )
        }
        return RemuxResult(destMp4, HlsHeaders.MIME_MP4, null)
    }
}
