package dev.ahmed.spotter.browser

/**
 * Block `window.open` / new-window requests without a user gesture, and a small host blocklist.
 * Never block the main-frame navigation the user typed.
 */
object PopupRules {
    val BLOCKED_HOSTS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads-twitter.com",
        "scorecardresearch.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adcash.com",
        "exoclick.com",
    )

    fun allowNewWindow(userGesture: Boolean): Boolean = userGesture

    /**
     * @param typedUrl the address-bar URL the user submitted for this tab, if any
     */
    fun blockHost(url: String, typedUrl: String?): Boolean {
        val host = MediaClassifier.hostOf(url) ?: return false
        val typedHost = typedUrl?.let { MediaClassifier.hostOf(it) }
        if (typedHost != null && (host == typedHost || host.endsWith(".$typedHost") || typedHost.endsWith(".$host"))) {
            return false
        }
        return BLOCKED_HOSTS.any { blocked -> host == blocked || host.endsWith(".$blocked") }
    }
}
