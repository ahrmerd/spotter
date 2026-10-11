package dev.ahmed.spotter.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.atomic.AtomicBoolean

/** YouTube through NewPipeExtractor (GPL-3.0). Only YouTube; other sites get their own [SiteResolver] later. */
class YoutubeResolver(private val client: OkHttpClient) : SiteResolver {
    override fun canHandle(url: String): Boolean = isYoutubeVideoUrl(url)

    override suspend fun resolve(url: String): ResolvedMedia = withContext(Dispatchers.IO) {
        ensureInit()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, url)
        } catch (e: Exception) {
            throw ResolveException("Could not read this video (${e.message ?: e.javaClass.simpleName})", e)
        }
        val options = OptionBuilder.build(
            videoOnly = info.videoOnlyStreams.mapNotNull(::rawVideo),
            muxed = info.videoStreams.mapNotNull(::rawVideo),
            audio = info.audioStreams.mapNotNull(::rawAudio),
        )
        if (options.isEmpty()) throw ResolveException("No downloadable streams found for this video")
        ResolvedMedia(
            pageUrl = url,
            title = info.name.orEmpty().ifBlank { "YouTube video" },
            author = info.uploaderName,
            durationSeconds = info.duration,
            options = options,
        )
    }

    private fun ensureInit() {
        if (initialised.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader(client), Localization.DEFAULT, ContentCountry.DEFAULT)
        }
    }

    private fun rawVideo(s: VideoStream): RawVideo? {
        if (!s.isUrl || s.content.isNullOrBlank()) return null
        val format = s.format?.suffix.orEmpty().lowercase()
        return RawVideo(
            height = s.height,
            format = format,
            codec = s.codec.orEmpty(),
            url = s.content,
            sizeBytes = s.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L,
            bitrate = s.bitrate,
        )
    }

    private fun rawAudio(s: AudioStream): RawAudio? {
        if (!s.isUrl || s.content.isNullOrBlank()) return null
        val format = s.format?.suffix.orEmpty().lowercase()
        return RawAudio(
            format = format,
            bitrateKbps = s.averageBitrate,
            url = s.content,
            sizeBytes = s.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L,
        )
    }

    companion object {
        private val initialised = AtomicBoolean(false)
        private val YT = Regex(
            """^https?://((www|m|music)\.)?(youtube\.com/(watch\?|shorts/|live/|embed/)|youtu\.be/)""",
            RegexOption.IGNORE_CASE,
        )

        /** A single-video link: playlists without a `v=` are not handled. */
        fun isYoutubeVideoUrl(url: String): Boolean {
            val u = url.trim()
            if (!YT.containsMatchIn(u)) return false
            return !(u.contains("list=") && !u.contains("v=") && !u.contains("youtu.be/"))
        }
    }
}
