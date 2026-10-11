package dev.ahmed.spotter.resolver

/** One downloadable stream of a page's media (a direct URL, not a manifest). */
data class StreamRef(
    val url: String,
    val mime: String,
    val extension: String,
    /** Exact size when the site reports it, else 0 (then the file is fetched in one request). */
    val sizeBytes: Long,
)

enum class OptionKind {
    /** Separate video-only and audio-only streams that must be merged into one MP4. */
    MERGED,

    /** One file that already has picture and sound. */
    MUXED,

    AUDIO_ONLY,
}

/**
 * One choice shown to the user. [id] is stable across lookups of the same page ("v1080", "m360", "a"), so a download can
 * be resumed with a fresh link after the old one expired.
 */
data class MediaOption(
    val id: String,
    val label: String,
    val kind: OptionKind,
    val video: StreamRef?,
    val audio: StreamRef?,
) {
    val approxBytes: Long get() = (video?.sizeBytes ?: 0L) + (audio?.sizeBytes ?: 0L)
}

data class ResolvedMedia(
    val pageUrl: String,
    val title: String,
    val author: String?,
    val durationSeconds: Long,
    val options: List<MediaOption>,
)

class ResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Turns a page URL (YouTube watch page, ...) into real downloadable streams. */
interface SiteResolver {
    fun canHandle(url: String): Boolean

    @Throws(ResolveException::class)
    suspend fun resolve(url: String): ResolvedMedia
}
