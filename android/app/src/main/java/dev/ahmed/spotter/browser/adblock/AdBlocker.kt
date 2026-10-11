package dev.ahmed.spotter.browser.adblock

import dev.ahmed.spotter.browser.MediaClassifier
import dev.ahmed.spotter.browser.MediaKind
import dev.ahmed.spotter.engine.model.EngineSettings
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides what the browser lets through. Three layers, because filter lists alone miss the hosts that streaming
 * sites rotate every few days (on animepahe.pw only 3 of 9 ad hosts seen were in EasyList):
 *
 * 1. Network filters (EasyList/EasyPrivacy) for sub-resources and for navigations.
 * 2. Behaviour rules that need no list: a page may not open a window from a tap that is not on a link (click-under),
 *    and may not move the tab to another site without the user's tap (script redirect loops).
 * 3. Cosmetic: hide known ad containers and remove invisible full-screen click catchers.
 *
 * Media is never blocked (the sniffer must still see it), and the user can switch it off per site.
 */
class AdBlocker(
    private val openLists: () -> List<InputStream>,
    private val settings: () -> EngineSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onBlocked: (tabId: Long) -> Unit = {},
) {
    @Volatile private var engine: FilterEngine? = null
    private val counts = ConcurrentHashMap<Long, AtomicInteger>()
    private val lastUserNav = ConcurrentHashMap<Long, Long>()

    /** Parses the bundled lists on a background thread; until it finishes only the behaviour rules apply. */
    fun loadAsync() {
        Thread({
            val e = FilterEngine()
            for (s in openLists()) s.bufferedReader().use { e.load(it.lineSequence()) }
            engine = e
        }, "adblock-load").apply { isDaemon = true }.start()
    }

    fun setEngineForTest(e: FilterEngine) { engine = e }

    fun blockedCount(tabId: Long): Int = counts[tabId]?.get() ?: 0
    fun resetCount(tabId: Long) { counts.remove(tabId) }

    fun enabledFor(pageUrl: String): Boolean {
        val s = settings()
        if (!s.adBlock) return false
        val host = hostOf(pageUrl)
        return host.isEmpty() || host !in s.adBlockOffHosts()
    }

    /** A sub-resource request. [pageUrl] is the page that made it. */
    fun requestBlocked(tabId: Long, url: String, type: Int, pageUrl: String): Boolean {
        if (!enabledFor(pageUrl)) return false
        val e = engine ?: return false
        if (isMedia(url)) return false
        val host = hostOf(url)
        val blocked = e.shouldBlock(url, host, type, hostOf(pageUrl))
        if (blocked) count(tabId)
        return blocked
    }

    /** The user typed or tapped a link: what happens in the next moments is allowed to leave the site. */
    fun noteUserNavigation(tabId: Long) { lastUserNav[tabId] = clock() }

    /** A main-frame navigation the page started (link tap, script, redirect). Typed addresses never get here. */
    fun navigationBlocked(tabId: Long, url: String, hasGesture: Boolean, pageUrl: String): Boolean {
        if (!enabledFor(pageUrl)) return false
        val target = hostOf(url)
        val here = hostOf(pageUrl)
        val e = engine
        if (e != null && !isMedia(url) && e.shouldBlock(url, target, FilterEngine.DOCUMENT or FilterEngine.POPUP, here)) {
            count(tabId); return true
        }
        if (hasGesture) {
            noteUserNavigation(tabId)
            return false
        }
        if (here.isEmpty() || FilterEngine.sameSite(target, here)) return false
        val sinceTap = clock() - (lastUserNav[tabId] ?: 0L)
        if (sinceTap in 0..USER_NAV_GRACE_MS) return false
        count(tabId)
        return true
    }

    /**
     * A page asks for a new window. A tap on a real link may open one (target=_blank); a tap on anything else is
     * the click-under trick, so it is refused.
     */
    fun popupBlocked(tabId: Long, pageUrl: String, tapOnLink: Boolean): Boolean {
        if (!enabledFor(pageUrl)) return false
        if (tapOnLink) return false
        count(tabId)
        return true
    }

    /** Script injected into each page: hides ad containers and removes invisible full-screen click catchers. */
    fun pageScript(pageUrl: String): String? {
        if (!enabledFor(pageUrl)) return null
        val css = engine?.cosmeticCss(hostOf(pageUrl)).orEmpty()
        return AdScripts.build(css)
    }

    private fun count(tabId: Long) {
        counts.getOrPut(tabId) { AtomicInteger() }.incrementAndGet()
        onBlocked(tabId)
    }

    private fun isMedia(url: String): Boolean {
        val k = MediaClassifier.kind(url)
        return k == MediaKind.MEDIA || k == MediaKind.PLAYLIST
    }

    companion object {
        const val USER_NAV_GRACE_MS = 2500L

        fun hostOf(url: String): String = MediaClassifier.hostOf(url).orEmpty()

        /** What kind of sub-resource is this? Best effort from the extension and the Accept header. */
        fun typeOf(url: String, accept: String?, mainFrame: Boolean): Int {
            if (mainFrame) return FilterEngine.DOCUMENT
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            val a = accept?.lowercase().orEmpty()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> FilterEngine.SCRIPT
                path.endsWith(".css") || "text/css" in a -> FilterEngine.STYLESHEET
                path.endsWithAny(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif") || a.startsWith("image/") -> FilterEngine.IMAGE
                path.endsWithAny(".woff", ".woff2", ".ttf", ".otf") -> FilterEngine.FONT
                path.endsWithAny(".mp4", ".webm", ".m3u8", ".ts", ".mp3") -> FilterEngine.MEDIA
                "text/html" in a -> FilterEngine.SUBDOCUMENT
                else -> FilterEngine.XHR or FilterEngine.OTHER or FilterEngine.SCRIPT
            }
        }

        private fun String.endsWithAny(vararg s: String) = s.any { endsWith(it) }
    }
}
