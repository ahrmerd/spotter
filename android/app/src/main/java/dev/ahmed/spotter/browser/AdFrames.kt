package dev.ahmed.spotter.browser

import java.net.URI
import java.net.URISyntaxException

/**
 * B7: the JS bridge is visible to every frame. Ignore reports from well-known ad/tracker
 * iframe hosts so they cannot stuff the detected-media list. Cross-origin video players
 * (kwik, CDNs) are not in this list.
 */
object AdFrames {
    private val SUFFIXES = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "2mdn.net",
        "amazon-adsystem.com",
        "ads-twitter.com",
        "ads.yahoo.com",
        "scorecardresearch.com",
        "criteo.com",
        "criteo.net",
        "outbrain.com",
        "taboola.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "moatads.com",
        "casalemedia.com",
        "adsafeprotected.com",
        "adservice.google.com",
        "facebook.com",
    )

    /**
     * Ignore JS reports from ad iframe origins, unless that origin *is* the page
     * (so browsing facebook.com still detects its own videos).
     */
    fun ignore(frameOrigin: String, pageUrl: String = ""): Boolean {
        val host = hostOf(frameOrigin) ?: return false
        val pageHost = hostOf(pageUrl)
        if (pageHost != null && sameSite(host, pageHost)) return false
        return SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    private fun sameSite(a: String, b: String): Boolean =
        a == b || a.endsWith(".$b") || b.endsWith(".$a") || root(a) == root(b)

    private fun root(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    fun hostOf(originOrUrl: String): String? {
        val raw = originOrUrl.trim()
        if (raw.isEmpty()) return null
        return try {
            val uri = if ("://" in raw) URI(raw) else URI("https://$raw")
            uri.host?.lowercase()?.trim('.')
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
