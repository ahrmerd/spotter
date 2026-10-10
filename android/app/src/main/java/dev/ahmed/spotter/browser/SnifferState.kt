package dev.ahmed.spotter.browser

/**
 * Per-tab detections. Cleared on navigation. Newest first; a `play` event pins that item at the top
 * (feed pages preload many videos).
 */
class SnifferState {
    private val lock = Any()
    private val byTab = HashMap<Long, LinkedHashMap<String, DetectedItem>>()

    fun observe(
        tabId: Long,
        url: String,
        headers: Map<String, String>,
        pageUrl: String,
        pageTitle: String,
        mimeType: String? = null,
        contentLength: Long? = null,
        fromJs: Boolean = false,
        fromPlay: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): DetectedItem? {
        var kind = MediaClassifier.kind(url, mimeType, contentLength)
        if (fromJs && kind == MediaKind.IGNORE) kind = MediaKind.UNKNOWN
        if (kind == MediaKind.SEGMENT || kind == MediaKind.IGNORE) return null
        if (!fromJs && kind == MediaKind.UNKNOWN && !url.startsWith("blob:")) return null
        val kept = HeaderCapture.keep(headers)
        val display = MediaClassifier.displaySize(kind, contentLength)
        val typeLabel = when (kind) {
            MediaKind.PLAYLIST -> "HLS/DASH stream"
            MediaKind.PAGE_RESOLVER -> "page (resolver)"
            else -> mimeType?.substringBefore(';')?.trim()?.ifBlank { null }
                ?: if (url.startsWith("blob:")) "blob" else "stream"
        }
        val name = PageTitles.suggestedFilename(
            pageTitle,
            url,
            if (kind == MediaKind.PLAYLIST) "application/vnd.apple.mpegurl" else mimeType,
        )
        synchronized(lock) {
            val tab = byTab.getOrPut(tabId) { LinkedHashMap() }
            val prev = tab[url]
            val item = DetectedItem(
                url = url,
                kind = kind,
                type = typeLabel,
                size = display ?: prev?.size,
                headers = if (kept.isNotEmpty()) kept else prev?.headers.orEmpty(),
                pageUrl = pageUrl.ifBlank { prev?.pageUrl.orEmpty() },
                pageTitle = pageTitle.ifBlank { prev?.pageTitle.orEmpty() },
                suggestedName = name,
                detectedAt = prev?.detectedAt ?: now,
                fromPlay = fromPlay || prev?.fromPlay == true,
            )
            tab[url] = item
            return item
        }
    }

    fun onNavigation(tabId: Long) {
        synchronized(lock) { byTab.remove(tabId) }
    }

    fun removeTab(tabId: Long) {
        synchronized(lock) { byTab.remove(tabId) }
    }

    fun removeUrl(tabId: Long, url: String) {
        synchronized(lock) { byTab[tabId]?.remove(url) }
    }

    fun items(tabId: Long): List<DetectedItem> {
        val values = synchronized(lock) { byTab[tabId]?.values?.toList().orEmpty() }
        return values.sortedWith(
            compareByDescending<DetectedItem> { it.fromPlay }.thenByDescending { it.detectedAt },
        )
    }

    fun replace(tabId: Long, items: List<DetectedItem>) {
        val map = LinkedHashMap<String, DetectedItem>()
        for (item in items) map[item.url] = item
        synchronized(lock) { byTab[tabId] = map }
    }

    fun snapshot(): Map<Long, List<DetectedItem>> =
        synchronized(lock) { byTab.mapValues { (_, v) -> v.values.toList() } }
}
