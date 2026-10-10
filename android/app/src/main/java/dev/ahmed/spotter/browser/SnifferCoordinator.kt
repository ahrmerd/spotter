package dev.ahmed.spotter.browser

import dev.ahmed.spotter.engine.net.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-tab sniffer wiring: intercept + JS + probe, restore-on-process-death, persist.
 * WebView-free so it can be unit-tested on the JVM.
 */
class SnifferCoordinator(
    val state: SnifferState = SnifferState(),
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val probe: (url: String, headers: Map<String, String>) -> ProbeResult? = { _, _ -> null },
    private val cookieOf: (url: String) -> String? = { null },
    private val persist: (tabId: Long, items: List<DetectedItem>) -> Unit = { _, _ -> },
    private val onChange: (tabId: Long) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val restoreGuard = HashSet<Long>()
    private val probing = HashSet<String>()

    fun restore(tabId: Long, items: List<DetectedItem>) {
        state.replace(tabId, items)
        pinRestore(tabId)
        onChange(tabId)
    }

    /** Keep the current list across the next main-frame load (WebView recreate). */
    fun pinRestore(tabId: Long) {
        synchronized(lock) { restoreGuard.add(tabId) }
    }

    /**
     * Main-frame navigation. The first load after [restore] keeps the cached list
     * (process death); later navigations and refresh clear it.
     */
    fun onNavigation(tabId: Long) {
        val keep = synchronized(lock) { restoreGuard.remove(tabId) }
        if (keep) {
            onChange(tabId)
            return
        }
        state.onNavigation(tabId)
        persist(tabId, emptyList())
        onChange(tabId)
    }

    fun onIntercept(
        tabId: Long,
        url: String,
        headers: Map<String, String>,
        pageUrl: String,
        pageTitle: String,
    ) {
        if (!isHttpOrBlob(url)) return
        val merged = HeaderCapture.mergeCookie(HeaderCapture.keep(headers), cookieOf(url))
        val item = state.observe(
            tabId = tabId,
            url = url,
            headers = merged,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            fromJs = false,
            now = clock(),
        )
        if (item != null) emit(tabId)
    }

    fun onJs(
        tabId: Long,
        url: String,
        pageUrl: String,
        pageTitle: String,
        play: Boolean,
    ) {
        if (!isHttpOrBlob(url)) return
        val merged = HeaderCapture.mergeCookie(emptyMap(), cookieOf(url))
        val item = state.observe(
            tabId = tabId,
            url = url,
            headers = merged,
            pageUrl = pageUrl,
            pageTitle = pageTitle,
            fromJs = true,
            fromPlay = play,
            now = clock(),
        ) ?: return
        emit(tabId)
        if (item.kind == MediaKind.UNKNOWN && url.startsWith("http")) {
            requestProbe(tabId, item, pageUrl, pageTitle)
        }
    }

    fun removeTab(tabId: Long) {
        state.removeTab(tabId)
        synchronized(lock) { restoreGuard.remove(tabId) }
        persist(tabId, emptyList())
        onChange(tabId)
    }

    fun items(tabId: Long): List<DetectedItem> = state.items(tabId)

    private fun requestProbe(tabId: Long, item: DetectedItem, pageUrl: String, pageTitle: String) {
        val key = "$tabId|${item.url}"
        synchronized(lock) { if (!probing.add(key)) return }
        scope.launch {
            val result = try {
                withContext(io) { probe(item.url, item.headers) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                synchronized(lock) { probing.remove(key) }
            }
            if (result == null) return@launch
            val refined = state.observe(
                tabId = tabId,
                url = item.url,
                headers = item.headers,
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                mimeType = result.mimeType,
                contentLength = result.size,
                fromJs = true,
                fromPlay = item.fromPlay,
                now = item.detectedAt,
            )
            if (refined == null) state.removeUrl(tabId, item.url)
            emit(tabId)
        }
    }

    private fun emit(tabId: Long) {
        persist(tabId, state.items(tabId))
        onChange(tabId)
    }

    companion object {
        fun isHttpOrBlob(url: String): Boolean {
            val u = url.trim()
            return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("blob:")
        }
    }
}
