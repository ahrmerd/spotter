package dev.ahmed.spotter.engine

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object Filenames {
    const val MAX_LENGTH = 200

    private val MIME_EXT = mapOf(
        "video/mp4" to ".mp4",
        "video/webm" to ".webm",
        "video/x-matroska" to ".mkv",
        "video/quicktime" to ".mov",
        "audio/mpeg" to ".mp3",
        "audio/mp4" to ".m4a",
        "audio/webm" to ".weba",
        "audio/ogg" to ".ogg",
        "audio/wav" to ".wav",
        "application/pdf" to ".pdf",
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/webp" to ".webp",
        "image/gif" to ".gif",
        "application/zip" to ".zip",
        "text/plain" to ".txt",
        "application/vnd.apple.mpegurl" to ".m3u8",
        "video/mp2t" to ".ts",
    )

    fun sanitize(raw: String): String {
        var name = raw.replace("\u0000", "")
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("[\\x00-\\x1f]"), "")
            .trim()
        while (name.startsWith('.')) {
            name = name.drop(1).trim()
        }
        name = name.trimEnd('.', ' ')
        if (name.isBlank() || name.all { it == '_' || it == '.' || it == ' ' }) name = "download"
        if (name.length > MAX_LENGTH) {
            val ext = extensionOf(name)
            val maxBase = (MAX_LENGTH - ext.length).coerceAtLeast(1)
            name = name.dropLast(ext.length).take(maxBase) + ext
        }
        return name
    }

    fun extensionOf(name: String): String {
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.lastIndex) return ""
        val ext = name.substring(i)
        return if (ext.length in 2..8) ext else ""
    }

    fun ensureExtension(name: String, mime: String?, url: String): String {
        if (extensionOf(name).isNotEmpty()) return name
        val fromMime = mime?.lowercase()?.substringBefore(';')?.trim()?.let { MIME_EXT[it] }.orEmpty()
        if (fromMime.isNotEmpty()) return name + fromMime
        val urlExt = extensionOf(sanitize(urlPathName(url)))
        return if (urlExt.isNotEmpty()) name + urlExt else name
    }

    fun urlPathName(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val last = noQuery.substringAfterLast('/')
        return try {
            URLDecoder.decode(last, StandardCharsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            last
        }
    }

    fun unique(desired: String, taken: (String) -> Boolean): String {
        if (!taken(desired) && !taken("$desired.part")) return desired
        val ext = extensionOf(desired)
        val base = desired.dropLast(ext.length)
        var n = 1
        while (n <= 10_000) {
            val candidate = "$base ($n)$ext"
            if (!taken(candidate) && !taken("$candidate.part")) return candidate
            n++
        }
        return "$base (${System.nanoTime()})$ext"
    }

    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val raw = star.groupValues[1].trim().trim('"')
            return try {
                URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                raw
            }
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return plain?.groupValues?.get(1)?.trim()?.trim('"')
    }
}
