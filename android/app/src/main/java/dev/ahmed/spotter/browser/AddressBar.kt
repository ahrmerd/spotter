package dev.ahmed.spotter.browser

object AddressBar {
    /** Turns what the user typed into a URL: a link as typed, anything else a search with the chosen engine. */
    fun resolve(input: String, engineId: String = "google", customTemplate: String = ""): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        if (t.contains(' ') && !t.contains("://")) return search(t, engineId, customTemplate)
        if (looksLikeUrl(t)) {
            return if ("://" in t) t else "https://$t"
        }
        return search(t, engineId, customTemplate)
    }

    fun search(query: String, engineId: String = "google", customTemplate: String = ""): String =
        SearchEngine.searchUrl(engineId, customTemplate, query)

    fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("about:")) return true
        val host = s.substringBefore('/').substringBefore('?')
        return host.contains('.') && !host.contains(' ')
    }

    /** Short text for the address pill when it is not being edited: the host without `www.`. */
    fun hostOf(url: String): String {
        if (url.isBlank()) return ""
        val noScheme = url.substringAfter("://", url)
        return noScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
    }

    fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)
}
