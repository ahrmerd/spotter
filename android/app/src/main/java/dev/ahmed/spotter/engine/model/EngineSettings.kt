package dev.ahmed.spotter.engine.model

data class EngineSettings(
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    val connectionsPerFile: Int = DEFAULT_CONNECTIONS,
    val maxTotalConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val speedLimitBps: Long = 0,
    val wifiOnly: Boolean = false,
    val resumeOnBoot: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val DEFAULT_CONNECTIONS = 4
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val MIN_CONNECTIONS = 1
        const val MAX_CONNECTIONS_PER_FILE = 16

        const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        const val KEY_CONNECTIONS_PER_FILE = "connections_per_file"
        const val KEY_MAX_TOTAL_CONNECTIONS = "max_total_connections"
        const val KEY_SPEED_LIMIT_BPS = "speed_limit_bps"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_RESUME_ON_BOOT = "resume_on_boot"
    }

    fun connectionsPerFileCapped(): Int =
        connectionsPerFile.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS_PER_FILE)

    fun maxConcurrentCapped(): Int = maxConcurrentDownloads.coerceAtLeast(1)
}
