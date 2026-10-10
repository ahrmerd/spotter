package dev.ahmed.spotter.browser

/**
 * Keep the headers the browser actually sent. Referer/Origin often belong to a
 * cross-origin iframe player, not the top-level page.
 */
object HeaderCapture {
    val KEEP = setOf("referer", "origin", "cookie", "user-agent", "authorization")

    fun keep(headers: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in headers) {
            if (k.lowercase() in KEEP && v.isNotBlank()) out[canonical(k)] = v
        }
        return out
    }

    fun mergeCookie(headers: Map<String, String>, cookieHeader: String?): Map<String, String> {
        if (cookieHeader.isNullOrBlank()) return headers
        if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) return headers
        return headers + ("Cookie" to cookieHeader)
    }

    fun redact(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) ->
            if (k.equals("Cookie", ignoreCase = true) || k.equals("Authorization", ignoreCase = true)) {
                "<redacted ${v.length} chars>"
            } else {
                v
            }
        }

    private fun canonical(name: String): String = when (name.lowercase()) {
        "referer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        "user-agent" -> "User-Agent"
        "authorization" -> "Authorization"
        else -> name
    }
}
