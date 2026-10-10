package dev.ahmed.spotter.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.GeolocationPermissions
import java.util.concurrent.atomic.AtomicLong

data class TabRef(val id: AtomicLong)

class WebViewCallbacks(
    val typedUrl: (tabId: Long) -> String?,
    val pageUrl: (tabId: Long) -> String,
    val pageTitle: (tabId: Long) -> String,
    val onPageStarted: (tabId: Long, url: String) -> Unit,
    val onPageFinished: (tabId: Long, url: String, title: String) -> Unit,
    val onTitle: (tabId: Long, title: String) -> Unit,
    val onUrl: (tabId: Long, url: String) -> Unit,
    val onProgress: (tabId: Long, progress: Int) -> Unit,
    val onNavState: (tabId: Long, canBack: Boolean, canForward: Boolean) -> Unit,
    val onIntercept: (tabId: Long, url: String, headers: Map<String, String>) -> Unit,
    val onJsMedia: (tabId: Long, url: String, play: Boolean) -> Unit,
    val onDownload: (url: String, userAgent: String, contentDisposition: String?, mime: String?, length: Long) -> Unit,
    val onNewWindow: (userGesture: Boolean, attach: (WebView) -> Unit) -> Boolean,
)

class WebViewHolder {
    private val views = LinkedHashMap<Long, WebView>()
    private val refs = HashMap<Long, TabRef>()

    fun get(tabId: Long): WebView? = synchronized(views) { views[tabId] }

    fun getOrCreate(tabId: Long, context: Context, callbacks: WebViewCallbacks): WebView {
        synchronized(views) {
            views[tabId]?.let { return it }
        }
        return createWebView(context, tabId, callbacks)
    }

    fun rebind(oldId: Long, newId: Long) {
        if (oldId == newId) return
        synchronized(views) {
            val wv = views.remove(oldId) ?: return
            views[newId] = wv
            val ref = refs.remove(oldId) ?: TabRef(AtomicLong(newId))
            ref.id.set(newId)
            refs[newId] = ref
        }
    }

    fun destroy(tabId: Long) {
        val wv = synchronized(views) {
            refs.remove(tabId)
            views.remove(tabId)
        }
        wv?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
    }

    fun destroyAll() {
        val ids = synchronized(views) { views.keys.toList() }
        for (id in ids) destroy(id)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context, tabId: Long, callbacks: WebViewCallbacks): WebView {
        val ref = TabRef(AtomicLong(tabId))
        val wv = WebView(context)
        harden(wv)
        wv.webViewClient = SpotterWebViewClient(ref, callbacks)
        wv.webChromeClient = SpotterChromeClient(ref, callbacks, wv)
        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            callbacks.onDownload(url, userAgent.orEmpty(), contentDisposition, mimeType, contentLength)
        })
        wv.addJavascriptInterface(SpotterBridge(ref, callbacks), BRIDGE_NAME)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        synchronized(views) {
            views[tabId]?.let {
                wv.destroy()
                return it
            }
            refs[tabId] = ref
            views[tabId] = wv
        }
        return wv
    }

    companion object {
        const val BRIDGE_NAME = "SpotterSniffer"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun applyDesktop(wv: WebView, desktop: Boolean) {
            wv.settings.userAgentString = if (desktop) DESKTOP_UA else null
            wv.settings.useWideViewPort = desktop
            wv.settings.loadWithOverviewMode = desktop
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun harden(wv: WebView) {
    wv.settings.apply {
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        loadWithOverviewMode = true
        useWideViewPort = true
    }
}

/**
 * One narrow bridge: a media URL plus an optional "play" flag. Only http(s) and blob: pass.
 */
class SpotterBridge(
    private val ref: TabRef,
    private val callbacks: WebViewCallbacks,
) {
    @JavascriptInterface
    fun media(url: String?, kind: String?) {
        val u = url?.trim().orEmpty()
        if (!SnifferCoordinator.isHttpOrBlob(u)) return
        val play = kind?.equals("play", ignoreCase = true) == true
        callbacks.onJsMedia(ref.id.get(), u, play)
    }
}

private class SpotterWebViewClient(
    private val ref: TabRef,
    private val callbacks: WebViewCallbacks,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return true
        val tabId = ref.id.get()
        if (PopupRules.blockHost(url, callbacks.typedUrl(tabId))) return true
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val headers = HashMap<String, String>()
        request.requestHeaders?.forEach { (k, v) -> if (v != null) headers[k] = v }
        callbacks.onIntercept(ref.id.get(), url, headers)
        return null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        val tabId = ref.id.get()
        val u = url.orEmpty()
        callbacks.onPageStarted(tabId, u)
        callbacks.onUrl(tabId, u)
        callbacks.onNavState(tabId, view.canGoBack(), view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        val tabId = ref.id.get()
        val u = url.orEmpty()
        val title = view.title.orEmpty()
        callbacks.onPageFinished(tabId, u, title)
        callbacks.onNavState(tabId, view.canGoBack(), view.canGoForward())
        view.evaluateJavascript(SnifferJs.SOURCE, null)
    }
}

private class SpotterChromeClient(
    private val ref: TabRef,
    private val callbacks: WebViewCallbacks,
    private val host: WebView,
) : WebChromeClient() {
    override fun onReceivedTitle(view: WebView?, title: String?) {
        callbacks.onTitle(ref.id.get(), title.orEmpty())
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        val tabId = ref.id.get()
        callbacks.onProgress(tabId, newProgress)
        if (newProgress >= 100 && view != null) {
            callbacks.onNavState(tabId, view.canGoBack(), view.canGoForward())
        }
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val msg = resultMsg ?: return false
        return callbacks.onNewWindow(isUserGesture) { child ->
            val transport = msg.obj as WebView.WebViewTransport
            transport.webView = child
            msg.sendToTarget()
        }
    }

    override fun onCloseWindow(window: WebView) {
        // Tab close is user-driven in the Compose chrome.
        host.requestFocus()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }
}


