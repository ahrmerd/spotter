package dev.ahmed.spotter.ui

object ShareUrls {
    private val HTTP = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun extractHttpUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (looksLikeUrl(trimmed)) return trimTrailing(trimmed)
        val match = HTTP.find(trimmed) ?: return null
        return trimTrailing(match.value)
    }

    private fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun trimTrailing(url: String): String = url.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
}
