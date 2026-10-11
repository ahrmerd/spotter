package dev.ahmed.spotter.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ahmed.spotter.SpotterApp
import dev.ahmed.spotter.browser.AddressBar
import dev.ahmed.spotter.browser.BrowserStore
import dev.ahmed.spotter.browser.adblock.AdBlocker
import dev.ahmed.spotter.browser.DetectedItem
import dev.ahmed.spotter.engine.store.HeaderJson
import dev.ahmed.spotter.browser.MediaKind
import dev.ahmed.spotter.browser.MediaNames
import dev.ahmed.spotter.browser.PageTitles
import dev.ahmed.spotter.browser.PopupRules
import dev.ahmed.spotter.browser.SnifferCoordinator
import dev.ahmed.spotter.browser.WebViewCallbacks
import dev.ahmed.spotter.browser.WebViewHolder
import dev.ahmed.spotter.engine.EngineErrors
import dev.ahmed.spotter.engine.Filenames
import dev.ahmed.spotter.engine.hls.HlsDocument
import dev.ahmed.spotter.engine.hls.HlsHeaders
import dev.ahmed.spotter.engine.hls.HlsHttp
import dev.ahmed.spotter.engine.hls.HlsPlaylist
import dev.ahmed.spotter.engine.net.RangeProbe
import dev.ahmed.spotter.engine.store.BookmarkEntity
import dev.ahmed.spotter.engine.store.HistoryEntity
import dev.ahmed.spotter.engine.store.TabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class HlsQualityOption(
    val label: String,
    val variantUri: String,
    val audioUri: String?,
)

data class QualityPrompt(
    val item: DetectedItem,
    val name: String,
    val headers: Map<String, String>,
    val options: List<HlsQualityOption>,
    val selected: Int,
)

data class BrowserUi(
    val tabs: List<TabEntity> = emptyList(),
    val activeTabId: Long = 0L,
    val address: String = "",
    val title: String = "",
    val desktop: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val bookmarked: Boolean = false,
    val adBlockOn: Boolean = true,
    val blocked: Int = 0,
    val detected: List<DetectedItem> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val history: List<HistoryEntity> = emptyList(),
    val message: String? = null,
    val quality: QualityPrompt? = null,
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpotterApp
    private val store: BrowserStore get() = app.browserStore
    private val rangeProbe = RangeProbe(app.httpClient, app.cookieSource)
    private val nextTempId = AtomicLong(-1)

    private val activeTabId = MutableStateFlow(0L)
    private val address = MutableStateFlow("")
    private val loading = MutableStateFlow(false)
    private val progress = MutableStateFlow(0)
    private val canBack = MutableStateFlow(false)
    private val canForward = MutableStateFlow(false)
    private val detectedTick = MutableStateFlow(0L)
    private val message = MutableStateFlow<String?>(null)
    val qualityPrompt = MutableStateFlow<QualityPrompt?>(null)
    private val hlsHttp = HlsHttp(app.httpClient, cookies = app.cookieSource)
    private val typedUrl = ConcurrentHashMap<Long, String>()
    private val pageMeta = ConcurrentHashMap<Long, Pair<String, String>>()
    private val prevUrl = ConcurrentHashMap<Long, String>()

    private val sniffer = SnifferCoordinator(
        scope = viewModelScope,
        probe = { url, headers ->
            try {
                rangeProbe.probe(url, headers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        },
        cookieOf = { url ->
            try {
                CookieManager.getInstance().getCookie(url)
            } catch (_: Exception) {
                null
            }
        },
        persist = { tabId, items ->
            if (tabId <= 0) return@SnifferCoordinator
            viewModelScope.launch { store.saveSniffer(tabId, items) }
        },
        onChange = { detectedTick.value = detectedTick.value + 1 },
    )

    init {
        viewModelScope.launch { app.adBlockTick.collect { detectedTick.value = detectedTick.value + 1 } }
        viewModelScope.launch { app.settingsFlow.collect { detectedTick.value = detectedTick.value + 1 } }
    }

    val ui: StateFlow<BrowserUi> = combine(
        store.observeTabs(),
        store.observeBookmarks(),
        store.observeHistory(),
        combine(activeTabId, address, loading, progress) { a, b, c, d -> Quad(a, b, c, d) },
        combine(canBack, canForward, detectedTick, message, qualityPrompt) { a, b, c, d, q ->
            Quint(a, b, c, d, q)
        },
    ) { tabs, bookmarks, history, nav, extra ->
        val id = nav.a
        val addr = nav.b
        val tab = tabs.firstOrNull { it.id == id } ?: tabs.firstOrNull()
        val tabId = tab?.id ?: 0L
        val url = addr.ifBlank { tab?.url.orEmpty() }
        BrowserUi(
            tabs = tabs,
            activeTabId = tabId,
            address = url,
            title = tab?.title.orEmpty(),
            desktop = tab?.desktop == true,
            loading = nav.c,
            progress = nav.d,
            canBack = extra.a,
            canForward = extra.b,
            bookmarked = bookmarks.any { it.url == url },
            adBlockOn = app.adBlocker.enabledFor(url),
            blocked = app.adBlocker.blockedCount(tabId),
            detected = sniffer.items(tabId),
            bookmarks = bookmarks,
            history = history,
            message = extra.d,
            quality = extra.e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUi())

    fun callbacks(holder: WebViewHolder): WebViewCallbacks {
        lateinit var cb: WebViewCallbacks
        cb = WebViewCallbacks(
        typedUrl = { typedUrl[it] },
        pageUrl = { pageMeta[it]?.first.orEmpty() },
        pageTitle = { pageMeta[it]?.second.orEmpty() },
        onPageStarted = { tabId, url ->
            if (tabId == activeTabId.value) {
                loading.value = true
                progress.value = 0
                address.value = displayUrl(url)
            }
            pageMeta[tabId] = url to (pageMeta[tabId]?.second.orEmpty())
            if (app.adBlocker.blockedCount(tabId) > 0 && AdBlocker.hostOf(url) != AdBlocker.hostOf(prevUrl[tabId].orEmpty())) {
                app.adBlocker.resetCount(tabId)
            }
            prevUrl[tabId] = url
            sniffer.onNavigation(tabId)
        },
        onPageFinished = { tabId, url, title ->
            if (tabId == activeTabId.value) {
                loading.value = false
                progress.value = 100
                address.value = displayUrl(url)
            }
            val cleaned = PageTitles.clean(title).ifBlank { title }
            pageMeta[tabId] = url to cleaned
            viewModelScope.launch {
                val tab = store.tabs().firstOrNull { it.id == tabId } ?: return@launch
                store.updateTab(tab.copy(url = url, title = cleaned.ifBlank { tab.title }))
                store.addHistory(url, cleaned)
            }
        },
        onTitle = { tabId, title ->
            val cleaned = PageTitles.clean(title).ifBlank { title }
            val prev = pageMeta[tabId]
            pageMeta[tabId] = (prev?.first.orEmpty()) to cleaned
            viewModelScope.launch {
                val tab = store.tabs().firstOrNull { it.id == tabId } ?: return@launch
                if (tab.title != cleaned) store.updateTab(tab.copy(title = cleaned))
            }
        },
        onUrl = { tabId, url ->
            if (tabId == activeTabId.value) address.value = displayUrl(url)
        },
        onProgress = { tabId, p ->
            if (tabId == activeTabId.value) {
                progress.value = p
                loading.value = p in 1..99
            }
        },
        onNavState = { tabId, back, fwd ->
            if (tabId == activeTabId.value) {
                canBack.value = back
                canForward.value = fwd
            }
        },
        onIntercept = { tabId, url, headers ->
            val meta = pageMeta[tabId]
            sniffer.onIntercept(tabId, url, headers, meta?.first.orEmpty(), meta?.second.orEmpty())
        },
        onJsMedia = { tabId, url, play, frameOrigin ->
            val meta = pageMeta[tabId]
            sniffer.onJs(tabId, url, meta?.first.orEmpty(), meta?.second.orEmpty(), play, frameOrigin)
        },
        onDownload = { url, userAgent, contentDisposition, mime, _ ->
            enqueueWebDownload(url, userAgent, contentDisposition, mime)
        },
        shouldBlockRequest = { tabId, url, accept ->
            app.adBlocker.requestBlocked(tabId, url, AdBlocker.typeOf(url, accept, false), pageMeta[tabId]?.first.orEmpty())
        },
        shouldBlockNavigation = { tabId, url, gesture ->
            app.adBlocker.navigationBlocked(tabId, url, gesture, pageMeta[tabId]?.first.orEmpty())
        },
        pageScript = { tabId, url -> app.adBlocker.pageScript(url.ifBlank { pageMeta[tabId]?.first.orEmpty() }) },
        onNewWindow = { userGesture, tapOnLink, attach ->
            if (!PopupRules.allowNewWindow(userGesture)) return@WebViewCallbacks false
            if (app.adBlocker.popupBlocked(activeTabId.value, pageMeta[activeTabId.value]?.first.orEmpty(), tapOnLink)) {
                return@WebViewCallbacks false
            }
            val host = holder.get(activeTabId.value) ?: return@WebViewCallbacks false
            val tempId = nextTempId.getAndDecrement()
            val child = holder.createWebView(host.context, tempId, cb)
            attach(child)
            viewModelScope.launch {
                val tab = store.addTab(title = "New tab")
                holder.rebind(tempId, tab.id)
                selectTab(tab.id)
            }
            true
        },
        )
        return cb
    }

    init {
        viewModelScope.launch {
            val existing = store.tabs()
            val tabs = if (existing.isEmpty()) listOf(store.addTab()) else existing
            val saved = store.getActiveTabId()?.takeIf { id -> tabs.any { it.id == id } }
            val active = saved ?: tabs.first().id
            activeTabId.value = active
            address.value = tabs.first { it.id == active }.url
            for (tab in tabs) {
                val cached = store.loadSniffer(tab.id)
                if (cached.isNotEmpty()) sniffer.restore(tab.id, cached)
            }
        }
    }

    fun submitAddress(raw: String, holder: WebViewHolder) {
        val cfg = app.settingsSnap
        val url = AddressBar.resolve(raw, cfg.searchEngine, cfg.customSearchUrl)
        if (url.isBlank()) return
        val tabId = activeTabId.value
        typedUrl[tabId] = url
        app.adBlocker.noteUserNavigation(tabId)
        address.value = url
        holder.get(tabId)?.loadUrl(url)
        viewModelScope.launch {
            val tab = store.tabs().firstOrNull { it.id == tabId } ?: return@launch
            store.updateTab(tab.copy(url = url))
            store.setActiveTabId(tabId)
        }
    }

    fun selectTab(id: Long, holder: WebViewHolder? = null) {
        activeTabId.value = id
        detectedTick.value += 1
        val wv = holder?.get(id)
        canBack.value = wv?.canGoBack() == true
        canForward.value = wv?.canGoForward() == true
        address.value = wv?.url?.let { displayUrl(it) }
            ?: ui.value.tabs.firstOrNull { it.id == id }?.url.orEmpty()
        progress.value = 100
        loading.value = false
        viewModelScope.launch { store.setActiveTabId(id) }
    }

    /** Suggestions for the address bar text; empty when disabled or the text looks like a URL. */
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    private var suggestJob: kotlinx.coroutines.Job? = null
    private val suggestionClient by lazy { dev.ahmed.spotter.browser.SuggestionClient(app.httpClient) }

    fun typing(text: String) {
        suggestJob?.cancel()
        val cfg = app.settingsSnap
        if (!cfg.searchSuggestions || !dev.ahmed.spotter.browser.SearchSuggestions.shouldSuggest(text)) {
            suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            suggestions.value = try {
                withContext(Dispatchers.IO) { suggestionClient.fetch(cfg.searchEngine, text) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        suggestions.value = emptyList()
    }

    /** Opens [url] in a new tab and switches to it. */
    fun openInNewTab(url: String) {
        viewModelScope.launch {
            val tab = store.addTab(url = url)
            typedUrl[tab.id] = url
            activeTabId.value = tab.id
            address.value = url
            canBack.value = false
            canForward.value = false
            detectedTick.value += 1
            store.setActiveTabId(tab.id)
        }
    }

    fun newTab() {
        viewModelScope.launch {
            val tab = store.addTab()
            activeTabId.value = tab.id
            address.value = ""
            canBack.value = false
            canForward.value = false
            detectedTick.value += 1
            store.setActiveTabId(tab.id)
        }
    }

    fun closeTab(id: Long, holder: WebViewHolder) {
        viewModelScope.launch {
            val remaining = store.tabs().filter { it.id != id }
            store.deleteTab(id)
            sniffer.removeTab(id)
            holder.destroy(id)
            typedUrl.remove(id)
            pageMeta.remove(id)
            val next = remaining.lastOrNull() ?: store.addTab()
            selectTab(next.id, holder)
        }
    }

    fun back(holder: WebViewHolder) {
        holder.get(activeTabId.value)?.takeIf { it.canGoBack() }?.goBack()
    }

    fun forward(holder: WebViewHolder) {
        holder.get(activeTabId.value)?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload(holder: WebViewHolder) {
        holder.get(activeTabId.value)?.reload()
    }

    fun toggleDesktop(holder: WebViewHolder) {
        viewModelScope.launch {
            val tab = store.tabs().firstOrNull { it.id == activeTabId.value } ?: return@launch
            val next = !tab.desktop
            store.updateTab(tab.copy(desktop = next))
            val wv = holder.get(tab.id) ?: return@launch
            withContext(Dispatchers.Main) {
                WebViewHolder.applyDesktop(wv, next)
                wv.reload()
            }
        }
    }

    /** Switches the ad blocker off or on for the host of the current page, then reloads it. */
    fun toggleAdBlockForSite(holder: WebViewHolder) {
        val host = AdBlocker.hostOf(address.value)
        if (host.isEmpty()) return
        viewModelScope.launch {
            val cur = app.settingsSnap
            val off = cur.adBlockOffHosts().toMutableSet()
            if (!off.remove(host)) off.add(host)
            app.applySettings(cur.copy(adBlockOff = off.sorted().joinToString(",")))
            withContext(Dispatchers.Main) { holder.get(activeTabId.value)?.reload() }
        }
    }

    fun toggleBookmark() {
        viewModelScope.launch {
            val tab = store.tabs().firstOrNull { it.id == activeTabId.value } ?: return@launch
            val url = address.value.ifBlank { tab.url }
            if (url.isBlank()) return@launch
            if (store.isBookmarked(url)) store.removeBookmark(url)
            else store.addBookmark(url, tab.title)
        }
    }

    fun download(item: DetectedItem, name: String) {
        if (item.kind == MediaKind.PAGE_RESOLVER) {
            if (app.resolve.handles(item.url)) {
                app.resolve.start(item.url, name)
            } else {
                message.value = "This site is not supported yet."
            }
            return
        }
        if (item.url.startsWith("blob:")) {
            message.value = "blob: URLs cannot be downloaded; pick the intercepted media URL."
            return
        }
        val filename = MediaNames.finalize(name, item)
        viewModelScope.launch {
            // Cookie/Authorization are not stored with the download; the engine reads cookies from the
            // WebView's CookieManager for every request (see CookieSource).
            val headers = HeaderJson.persistable(item.headers)
            if (item.kind == MediaKind.PLAYLIST) {
                enqueuePlaylist(item, filename, headers)
            } else {
                app.manager.enqueue(item.url, headers, filename)
                message.value = "Queued $filename"
            }
        }
    }

    fun selectQuality(index: Int) {
        val prompt = qualityPrompt.value ?: return
        if (index in prompt.options.indices) {
            qualityPrompt.value = prompt.copy(selected = index)
        }
    }

    fun confirmQuality() {
        val prompt = qualityPrompt.value ?: return
        val option = prompt.options.getOrNull(prompt.selected) ?: return
        qualityPrompt.value = null
        viewModelScope.launch {
            val headers = HeaderJson.persistable(prompt.headers) + buildMap {
                put(HlsHeaders.VARIANT, option.variantUri)
                option.audioUri?.let { put(HlsHeaders.AUDIO, it) }
            }
            app.manager.enqueue(prompt.item.url, headers, prompt.name)
            message.value = "Queued ${prompt.name}"
        }
    }

    fun dismissQuality() {
        qualityPrompt.value = null
    }

    fun clearMessage() {
        message.value = null
    }

    private suspend fun enqueuePlaylist(item: DetectedItem, filename: String, headers: Map<String, String>) {
        if (HlsPlaylist.looksLikeDash(item.url)) {
            message.value = "DASH is not supported yet."
            return
        }
        try {
            val text = withContext(Dispatchers.IO) { hlsHttp.fetchText(item.url, headers) }
            when (val doc = HlsPlaylist.parse(text, item.url)) {
                is HlsDocument.Master -> {
                    if (doc.variants.isEmpty()) {
                        message.value = "Master playlist has no variants"
                        return
                    }
                    val best = doc.bestIndex()
                    val pref = app.settingsSnap.defaultHlsQuality
                    val selected = doc.preferredIndex(pref)
                    qualityPrompt.value = QualityPrompt(
                        item = item,
                        name = filename,
                        headers = headers,
                        options = doc.variants.mapIndexed { i, v ->
                            HlsQualityOption(
                                label = HlsPlaylist.qualityLabel(v, i == best),
                                variantUri = v.uri,
                                audioUri = doc.audioFor(v)?.uri,
                            )
                        },
                        selected = selected,
                    )
                }
                is HlsDocument.Media -> {
                    if (!doc.endList) {
                        message.value = "Live HLS is not supported (no #EXT-X-ENDLIST)"
                        return
                    }
                    app.manager.enqueue(item.url, HeaderJson.persistable(headers), filename)
                    message.value = "Queued $filename"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            message.value = EngineErrors.userMessage(e.message ?: e.javaClass.simpleName)
        }
    }

    fun loadIfNeeded(tab: TabEntity, wv: WebView) {
        WebViewHolder.applyDesktop(wv, tab.desktop)
        val current = wv.url
        if (tab.url.isNotBlank() && (current.isNullOrBlank() || current == "about:blank")) {
            sniffer.pinRestore(tab.id)
            typedUrl.putIfAbsent(tab.id, tab.url)
            wv.loadUrl(tab.url)
        }
    }

    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // CookieManager can throw if the WebView provider is not ready.
        }
    }

    private fun enqueueWebDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mime: String?,
    ) {
        if (!url.startsWith("http")) return
        viewModelScope.launch {
            val headers = HeaderJson.persistable(
                buildMap { if (userAgent.isNotBlank()) put("User-Agent", userAgent) },
            )
            val suggested = PageTitles.suggestedFilename(ui.value.title, url, mime)
            val fromCd = Filenames.fromContentDisposition(contentDisposition)
            val item = DetectedItem(
                url = url,
                kind = MediaKind.MEDIA,
                type = mime.orEmpty(),
                size = null,
                headers = headers,
                pageUrl = address.value,
                pageTitle = ui.value.title,
                suggestedName = suggested,
                detectedAt = System.currentTimeMillis(),
            )
            val name = MediaNames.finalize(fromCd.orEmpty(), item)
            app.manager.enqueue(url, headers, name)
            message.value = "Queued $name"
        }
    }

    private fun displayUrl(url: String): String = if (url == "about:blank") "" else url
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
