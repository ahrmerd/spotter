package dev.ahmed.spotter.engine.hls

/** Sample buffer for [MediaMuxerRemuxer]: never a fixed 64 KiB (key frames on 720p exceed that). */
object MuxBuffers {
    const val MIN = 256 * 1024
    const val CEILING = 8 * 1024 * 1024

    fun sizeFor(maxInputSize: Int): Int {
        val want = maxInputSize.toLong().coerceAtLeast(0) + 4096
        return maxOf(MIN.toLong(), want).coerceAtMost(CEILING.toLong()).toInt()
    }

    fun grow(current: Int, needed: Int): Int {
        val doubled = current.toLong() * 2
        val want = maxOf(doubled, needed.toLong().coerceAtLeast(0))
        return want.coerceAtMost(CEILING.toLong()).toInt().coerceAtLeast(current)
    }
}
