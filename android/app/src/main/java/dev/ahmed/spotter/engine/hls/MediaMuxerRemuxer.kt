package dev.ahmed.spotter.engine.hls

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
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
            val tracks = mutableListOf<Track>()
            addTracks(video, muxer, extractors, tracks)
            if (audio != null) addTracks(audio, muxer, extractors, tracks)
            if (tracks.isEmpty()) throw IOException("No tracks to mux")
            muxer.start()
            var buf = ByteBuffer.allocate(MuxBuffers.sizeFor(tracks.maxOf { it.maxInput }))
            val info = MediaCodec.BufferInfo()
            val lastPts = LongArray(tracks.size) { -1L }
            while (true) {
                val idx = tracks.indices.filter { tracks[it].ex.sampleTime >= 0 }
                    .minByOrNull { tracks[it].ex.sampleTime } ?: break
                val t = tracks[idx]
                buf = readCurrent(t, buf)
                var pts = t.pts
                // Video with B-frames legitimately has non-increasing presentation times in decode order: never touch it.
                // Audio timestamps must grow, otherwise several frames of one transport packet collapse onto one tick.
                if (t.isAudio && pts <= lastPts[idx]) pts = lastPts[idx] + 1
                lastPts[idx] = pts
                info.offset = 0
                info.size = t.size
                info.presentationTimeUs = pts
                info.flags = t.flags
                muxer.writeSampleData(t.dst, buf, info)
                t.ex.advance()
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
        tracks: MutableList<Track>,
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
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "unknown"
            val maxInput = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
            try {
                val dst = muxer.addTrack(fmt)
                ex.selectTrack(i)
                tracks.add(Track(ex, dst, maxInput, isAudio = mime.startsWith("audio/")))
            } catch (e: Exception) {
                throw IOException("Codec $mime cannot go in MP4: ${e.message}")
            }
        }
    }

    private fun readCurrent(track: Track, buffer: ByteBuffer): ByteBuffer {
        var buf = buffer
        while (true) {
            // getSampleSize() exists from API 28 (lint NewApi, minSdk 26). Older devices grow the buffer on failure below.
            val sampleSize = if (Build.VERSION.SDK_INT >= 28) track.ex.sampleSize else -1L
            if (sampleSize > buf.capacity() && sampleSize > 0) {
                val cap = MuxBuffers.grow(buf.capacity(), sampleSize.toInt())
                if (sampleSize > cap) {
                    throw IOException("sample $sampleSize exceeds remux ceiling $cap")
                }
                buf = ByteBuffer.allocate(cap)
            }
            val n = try {
                track.ex.readSampleData(buf, 0)
            } catch (e: IllegalArgumentException) {
                val cap = MuxBuffers.grow(buf.capacity(), buf.capacity() * 2)
                if (cap <= buf.capacity()) throw e
                buf = ByteBuffer.allocate(cap)
                continue
            }
            if (n < 0) throw IOException("readSampleData returned $n")
            track.size = n
            track.pts = track.ex.sampleTime.coerceAtLeast(0)
            track.flags = track.ex.sampleFlags
            return buf
        }
    }

    private class Track(
        val ex: MediaExtractor,
        val dst: Int,
        val maxInput: Int,
        val isAudio: Boolean = false,
        var pts: Long = 0,
        var size: Int = 0,
        var flags: Int = 0,
    )
}
