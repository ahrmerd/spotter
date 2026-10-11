package dev.ahmed.spotter.resolver

data class RawVideo(
    val height: Int,
    /** "mp4" or "webm". */
    val format: String,
    val codec: String,
    val url: String,
    val sizeBytes: Long,
    val bitrate: Int,
)

data class RawAudio(
    /** "m4a" or "webm"/"opus". */
    val format: String,
    val bitrateKbps: Int,
    val url: String,
    val sizeBytes: Long,
)

/**
 * Picks the options worth showing. Only H.264 in MP4 and AAC in M4A are offered for merging: Android's MediaMuxer can
 * put those into an MP4 on every supported version, VP9/Opus cannot be relied on.
 */
object OptionBuilder {
    fun build(videoOnly: List<RawVideo>, muxed: List<RawVideo>, audio: List<RawAudio>): List<MediaOption> {
        val bestAudio = audio.filter { it.format == "m4a" }.maxByOrNull { it.bitrateKbps }
        val out = mutableListOf<MediaOption>()
        val merged: Map<Int, RawVideo> = if (bestAudio == null) emptyMap() else {
            videoOnly
                .filter { it.format == "mp4" && it.codec.startsWith("avc1", ignoreCase = true) }
                .groupBy { it.height }
                .mapValues { (_, list) -> list.maxByOrNull { it.bitrate }!! }
        }
        for (height in merged.keys.sortedDescending()) {
            val v = merged.getValue(height)
            out += MediaOption(
                id = "v$height",
                label = "${height}p · MP4 (video + audio)",
                kind = OptionKind.MERGED,
                video = StreamRef(v.url, "video/mp4", ".mp4", v.sizeBytes),
                audio = StreamRef(bestAudio!!.url, "audio/mp4", ".m4a", bestAudio.sizeBytes),
            )
        }
        for (m in muxed.filter { it.format == "mp4" }.sortedByDescending { it.height }) {
            if (merged.containsKey(m.height)) continue
            out += MediaOption(
                id = "m${m.height}",
                label = "${m.height}p · MP4 (one file)",
                kind = OptionKind.MUXED,
                video = StreamRef(m.url, "video/mp4", ".mp4", m.sizeBytes),
                audio = null,
            )
        }
        if (bestAudio != null) {
            out += MediaOption(
                id = "a",
                label = "Audio only · M4A ${bestAudio.bitrateKbps} kbps",
                kind = OptionKind.AUDIO_ONLY,
                video = null,
                audio = StreamRef(bestAudio.url, "audio/mp4", ".m4a", bestAudio.sizeBytes),
            )
        }
        // Highest quality first, audio last; "one file" entries sort by height among the video ones.
        return out.sortedWith(
            compareBy<MediaOption> { it.kind == OptionKind.AUDIO_ONLY }
                .thenByDescending { it.id.drop(1).toIntOrNull() ?: 0 },
        )
    }
}
