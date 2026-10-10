package dev.ahmed.spotter.browser

import dev.ahmed.spotter.engine.Filenames

/**
 * Page titles are dirty: `(48) Title - YouTube`. Same cleaning as the desktop popup.
 */
object PageTitles {
    private val LEADING_COUNT = Regex("""^\(\d+\)\s*""")
    private val SITE_SUFFIX = Regex(
        """\s+[-|–—]\s+(YouTube|Vimeo|TikTok|X|Twitter|Facebook|Instagram|Dailymotion|Twitch|Reddit)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ILLEGAL = Regex("""[\\/:*?"<>|]+""")

    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var t = raw.replace(LEADING_COUNT, "").trim()
        t = t.replace(SITE_SUFFIX, "").trim()
        t = ILLEGAL.replace(t, " ").replace(Regex("""\s+"""), " ").trim()
        if (t.isBlank()) return ""
        return Filenames.sanitize(t).take(150)
    }

    fun suggestedFilename(pageTitle: String?, mediaUrl: String, mime: String?): String {
        val cleaned = clean(pageTitle)
        val fromUrl = Filenames.sanitize(Filenames.urlPathName(mediaUrl))
        val base = cleaned.ifBlank { fromUrl.ifBlank { "download" } }
        return Filenames.ensureExtension(base, mime, mediaUrl)
    }
}
