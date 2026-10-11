package dev.ahmed.spotter.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Search engines the address bar can use. `%s` in a template is replaced by the URL-encoded query. */
enum class SearchEngine(val id: String, val label: String, val searchTemplate: String, val suggestTemplate: String?) {
    GOOGLE(
        "google", "Google",
        "https://www.google.com/search?q=%s",
        "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
    ),
    DUCKDUCKGO(
        "duckduckgo", "DuckDuckGo",
        "https://duckduckgo.com/?q=%s",
        "https://duckduckgo.com/ac/?type=list&q=%s",
    ),
    BING(
        "bing", "Bing",
        "https://www.bing.com/search?q=%s",
        "https://api.bing.com/osjson.aspx?query=%s",
    ),
    BRAVE(
        "brave", "Brave",
        "https://search.brave.com/search?q=%s",
        "https://search.brave.com/api/suggest?q=%s",
    ),
    CUSTOM("custom", "Custom", "", null);

    companion object {
        fun byId(id: String): SearchEngine = entries.firstOrNull { it.id == id } ?: GOOGLE
        fun encode(q: String): String = URLEncoder.encode(q, StandardCharsets.UTF_8.name())

        /** The search URL for [query]; a custom template without `%s` falls back to Google. */
        fun searchUrl(engineId: String, customTemplate: String, query: String): String {
            val engine = byId(engineId)
            val template = if (engine == CUSTOM && customTemplate.contains("%s") && customTemplate.startsWith("http")) {
                customTemplate
            } else if (engine == CUSTOM) {
                GOOGLE.searchTemplate
            } else {
                engine.searchTemplate
            }
            return template.replace("%s", encode(query))
        }
    }
}

/**
 * Search suggestions while typing. Privacy rules (owner decision): at least 2 characters, never text that looks like a
 * URL or contains a scheme, HTTPS only, no cookies and no identifying headers, nothing logged.
 */
object SearchSuggestions {
    const val MIN_CHARS = 2

    fun shouldSuggest(query: String): Boolean {
        val q = query.trim()
        if (q.length < MIN_CHARS) return false
        if ("://" in q) return false
        if (q.startsWith("www.", ignoreCase = true)) return false
        // "example.com", "host:8080/path": looks like a place to go, not a search.
        if (!q.contains(' ') && AddressBar.looksLikeUrl(q)) return false
        return true
    }

    /** OpenSearch suggestion JSON: `["query", ["suggestion 1", "suggestion 2", ...], ...]`. */
    fun parse(body: String, limit: Int = 6): List<String> = try {
        val root = Json.parseToJsonElement(body)
        val list = (root as? JsonArray)?.getOrNull(1) as? JsonArray
        list?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.isNotBlank() }?.distinct()?.take(limit).orEmpty()
    } catch (e: Exception) {
        emptyList()
    }
}

/** Fetches suggestions with a client that has no cookie jar (the app's shared client has none). */
class SuggestionClient(private val client: OkHttpClient) {
    fun fetch(engineId: String, query: String): List<String> {
        val template = SearchEngine.byId(engineId).suggestTemplate ?: return emptyList()
        if (!SearchSuggestions.shouldSuggest(query)) return emptyList()
        val request = Request.Builder()
            .url(template.replace("%s", SearchEngine.encode(query.trim())))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) emptyList() else SearchSuggestions.parse(resp.body?.string().orEmpty())
        }
    }
}
