package dev.ahmed.spotter.engine.net

import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.engine.http.HttpStatusException
import dev.ahmed.spotter.engine.http.NeedsRefreshException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ProbeResult(
    val size: Long?,
    val ranged: Boolean,
    val filename: String,
    val mimeType: String?,
    val etag: String?,
    val lastModified: String?,
)

class RangeProbe(
    private val client: OkHttpClient,
    private val cookies: CookieSource = CookieSource.NONE,
) {
    fun probe(
        url: String,
        capturedHeaders: Map<String, String>,
        extra: Map<String, String> = emptyMap(),
    ): ProbeResult {
        var lastIo: IOException? = null
        repeat(3) { attempt ->
            try {
                return probeOnce(url, capturedHeaders, extra)
            } catch (e: IOException) {
                lastIo = e
                client.connectionPool.evictAll()
            }
        }
        throw lastIo ?: IOException("probe failed")
    }

    private fun probeOnce(
        url: String,
        capturedHeaders: Map<String, String>,
        extra: Map<String, String>,
    ): ProbeResult {
        val headers = HeaderUtils.merge(
            with(CookieSource) { capturedHeaders.withCookie(url, cookies) },
            extra + ("Range" to "bytes=0-0"),
        )
        val req = Request.Builder().url(url).get().apply {
            for ((k, v) in headers) header(k, v)
        }.build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                401, 403, 404, 410 -> throw NeedsRefreshException(resp.code, "Probe HTTP ${resp.code}")
                206, 200 -> Unit
                else -> throw HttpStatusException(resp.code, "Probe HTTP ${resp.code}")
            }
            val contentRange = resp.header("Content-Range")
            val ranged = resp.code == 206 && !contentRange.isNullOrBlank()
            val size = when {
                ranged -> parseTotalFromContentRange(contentRange)
                else -> resp.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            }
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val fromCd = Filenames.fromContentDisposition(resp.header("Content-Disposition"))
            val urlName = Filenames.urlPathName(resp.request.url.toString())
            val rawName = fromCd?.ifBlank { null } ?: urlName.ifBlank { "download" }
            val filename = Filenames.ensureExtension(
                Filenames.sanitize(rawName),
                mime,
                resp.request.url.toString(),
            )
            return ProbeResult(
                size = size,
                ranged = ranged,
                filename = filename,
                mimeType = mime,
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
            )
        }
    }

    private fun parseTotalFromContentRange(cr: String?): Long? {
        if (cr == null) return null
        val total = cr.substringAfter('/', missingDelimiterValue = "").trim()
        if (total == "*" || total.isEmpty()) return null
        return total.toLongOrNull()
    }
}
