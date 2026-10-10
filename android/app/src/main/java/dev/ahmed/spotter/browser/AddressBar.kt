package dev.ahmed.spotter.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AddressBar {
    fun resolve(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return t
        if (t.contains(' ') && !t.contains("://")) return search(t)
        if (looksLikeUrl(t)) {
            return if ("://" in t) t else "https://$t"
        }
        return search(t)
    }

    fun search(query: String): String =
        "https://duckduckgo.com/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("about:")) return true
        val host = s.substringBefore('/').substringBefore('?')
        return host.contains('.') && !host.contains(' ')
    }
}
