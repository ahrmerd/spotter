package dev.ahmed.spotter.resolver

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlanStream(val url: String, val size: Long, val mime: String, val ext: String)

/**
 * Everything the downloader needs, kept inside the download's own URL field so no database change is needed:
 * `spotter-resolved://v1/<base64url(json)>`. It holds stream links, never cookies.
 */
@Serializable
data class ResolvedPlan(
    val page: String,
    val title: String,
    val optionId: String,
    val kind: String,
    val video: PlanStream? = null,
    val audio: PlanStream? = null,
)

object ResolvedUrls {
    const val PREFIX = "spotter-resolved://v1/"
    private val json = Json { ignoreUnknownKeys = true }

    fun isResolved(url: String): Boolean = url.startsWith(PREFIX)

    fun encode(plan: ResolvedPlan): String {
        val raw = json.encodeToString(plan).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun decode(url: String): ResolvedPlan? {
        if (!isResolved(url)) return null
        return try {
            val raw = Base64.getUrlDecoder().decode(url.removePrefix(PREFIX))
            json.decodeFromString<ResolvedPlan>(String(raw, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    fun planFor(media: ResolvedMedia, option: MediaOption): ResolvedPlan = ResolvedPlan(
        page = media.pageUrl,
        title = media.title,
        optionId = option.id,
        kind = option.kind.name,
        video = option.video?.let { PlanStream(it.url, it.sizeBytes, it.mime, it.extension) },
        audio = option.audio?.let { PlanStream(it.url, it.sizeBytes, it.mime, it.extension) },
    )
}
