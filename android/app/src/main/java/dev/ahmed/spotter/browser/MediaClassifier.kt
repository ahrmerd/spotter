package dev.ahmed.spotter.browser

enum class MediaKind {
    MEDIA,
    PLAYLIST,
    SEGMENT,
    PAGE_RESOLVER,
    UNKNOWN,
    IGNORE,
}

object MediaClassifier {
    private val MEDIA_EXT = Regex(
        """\.(mp4|webm|mkv|mov|m4v|flv|mp3|m4a|ogg|oga|wav|m3u8|mpd)(\?|$|#)""",
        RegexOption.IGNORE_CASE,
    )
    private val SEGMENT_EXT = Regex("""\.(ts|m4s|m4f)(\?|$|#)""", RegexOption.IGNORE_CASE)
    private val PLAYLIST_EXT = Regex("""\.(m3u8|mpd)(\?|$|#)""", RegexOption.IGNORE_CASE)
    private val MEDIA_CT = Regex(
        """^(video/|audio/|application/(vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAYLIST_CT = Regex("""mpegurl|dash\+xml""", RegexOption.IGNORE_CASE)
    private val TS_CT = Regex("""mp2t""", RegexOption.IGNORE_CASE)
    private val RESOLVER_HOST = Regex(
        """(^|\.)((youtube\.com)|(youtu\.be)|(vimeo\.com)|(twitter\.com)|(x\.com)|(tiktok\.com)|(facebook\.com)|(instagram\.com)|(dailymotion\.com)|(twitch\.tv))$""",
        RegexOption.IGNORE_CASE,
    )

    fun kind(url: String, mimeType: String? = null, contentLength: Long? = null): MediaKind {
        val path = url.substringBefore('#').substringBefore('?')
        val mime = mimeType?.substringBefore(';')?.trim().orEmpty()
        if (isResolverPage(url)) return MediaKind.PAGE_RESOLVER
        if (SEGMENT_EXT.containsMatchIn(path) || TS_CT.containsMatchIn(mime)) return MediaKind.SEGMENT
        if (PLAYLIST_EXT.containsMatchIn(path) || PLAYLIST_CT.containsMatchIn(mime)) return MediaKind.PLAYLIST
        val byType = MEDIA_CT.containsMatchIn(mime)
        val byExt = MEDIA_EXT.containsMatchIn(path)
        if (!byType && !byExt) {
            if (url.startsWith("blob:", ignoreCase = true)) return MediaKind.UNKNOWN
            return MediaKind.IGNORE
        }
        // Tiny non-playlist bodies are fragments, not the video.
        if (contentLength != null && contentLength in 1 until 100 * 1024) return MediaKind.IGNORE
        return MediaKind.MEDIA
    }

    fun looksLikeMediaRequest(url: String): Boolean {
        val k = kind(url)
        return k == MediaKind.MEDIA || k == MediaKind.PLAYLIST || k == MediaKind.UNKNOWN
    }

    fun isPlaylist(url: String, mimeType: String? = null): Boolean =
        kind(url, mimeType) == MediaKind.PLAYLIST

    /** Playlist byte length is not the video size. */
    fun displaySize(kind: MediaKind, contentLength: Long?): Long? {
        if (kind == MediaKind.PLAYLIST || kind == MediaKind.PAGE_RESOLVER) return null
        if (contentLength == null || contentLength <= 0) return null
        return contentLength
    }

    fun isResolverPage(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (RESOLVER_HOST.containsMatchIn(host)) {
            return Regex(
                """youtube\.com/(watch|shorts)|youtu\.be/|vimeo\.com/\d|twitter\.com/.+/status|x\.com/.+/status|tiktok\.com/.+/video|facebook\.com/.+/videos|instagram\.com/(p|reel)|dailymotion\.com/video|twitch\.tv/(videos|.+/clip)""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(url)
        }
        return false
    }

    fun hostOf(url: String): String? {
        val noScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (noScheme.isEmpty()) return null
        return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .lowercase()
            .removePrefix("www.")
    }
}
