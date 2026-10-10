package dev.ahmed.spotter.engine.net

/**
 * [canDownload] is the Wi-Fi-only gate (unmetered / Wi-Fi / ethernet).
 * [isOnline] is any usable network; used to yield running jobs on airplane mode.
 */
interface NetworkPolicy {
    fun canDownload(): Boolean
    fun isOnline(): Boolean

    companion object {
        val ALWAYS = object : NetworkPolicy {
            override fun canDownload(): Boolean = true
            override fun isOnline(): Boolean = true
        }
    }
}
