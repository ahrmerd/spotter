package dev.ahmed.spotter.engine.net

/**
 * Supplies the current cookies for a URL at request time (on Android: the WebView's `CookieManager`).
 * Cookies are never persisted with a download: they are read here for each request, so a paused or
 * restarted download uses fresh cookies and nothing sensitive sits in the database or in backups.
 */
fun interface CookieSource {
    fun cookieFor(url: String): String?

    companion object {
        val NONE = CookieSource { null }

        /** Add a `Cookie` header from [source] unless the caller already supplied one. */
        fun Map<String, String>.withCookie(url: String, source: CookieSource): Map<String, String> {
            if (keys.any { it.equals("Cookie", ignoreCase = true) }) return this
            val cookie = source.cookieFor(url)?.takeIf { it.isNotBlank() } ?: return this
            return this + ("Cookie" to cookie)
        }
    }
}
