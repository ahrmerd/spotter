package dev.ahmed.spotter.engine.hls

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class MediaMuxerRemuxer : HlsRemuxer {
    override fun remux(video: File, audio: File?, destMp4: File): RemuxResult {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        try {
            destMp4.parentFile?.mkdirs()
            muxer = MediaMuxer(destMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = mutableListOf<Pair<MediaExtractor, Int>>()
            addTracks(video, muxer, extractors, map)
            if (audio != null) addTracks(audio, muxer, extractors, map)
            if (map.isEmpty()) throw IOException("No tracks to mux")
            muxer.start()
            val buf = ByteBuffer.allocate(64 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((ex, dst) in map) {
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.offset = 0
                    info.size = n
                    info.presentationTimeUs = ex.sampleTime.coerceAtLeast(0)
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(dst, buf, info)
                    buf.clear()
                    ex.advance()
                }
            }
            muxer.stop()
            muxer.release()
            muxer = null
            return RemuxResult(destMp4, HlsHeaders.MIME_MP4, null)
        } catch (e: Exception) {
            try {
                muxer?.release()
            } catch (close: Exception) {
                e.addSuppressed(close)
            }
            destMp4.delete()
            val why = e.message ?: e.javaClass.simpleName
            return RemuxResult(
                video,
                HlsHeaders.MIME_TS,
                "Could not remux to MP4 ($why). Kept MPEG-TS.",
            )
        } finally {
            for (ex in extractors) {
                try {
                    ex.release()
                } catch (release: Exception) {
                    // already released after a failed setDataSource
                }
            }
        }
    }

    private fun addTracks(
        file: File,
        muxer: MediaMuxer,
        extractors: MutableList<MediaExtractor>,
        map: MutableList<Pair<MediaExtractor, Int>>,
    ) {
        val probe = MediaExtractor()
        probe.setDataSource(file.absolutePath)
        val count = probe.trackCount
        probe.release()
        for (i in 0 until count) {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            extractors.add(ex)
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: "unknown"
            try {
                val dst = muxer.addTrack(fmt)
                ex.selectTrack(i)
                map.add(ex to dst)
            } catch (e: Exception) {
                throw IOException("Codec $mime cannot go in MP4: ${e.message}")
            }
        }
    }
}
