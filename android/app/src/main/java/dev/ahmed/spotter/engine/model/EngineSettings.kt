package dev.ahmed.spotter.engine.model

data class EngineSettings(
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    val connectionsPerFile: Int = DEFAULT_CONNECTIONS,
    val maxTotalConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val speedLimitBps: Long = 0,
    val wifiOnly: Boolean = false,
    val resumeOnBoot: Boolean = false,
    val defaultHlsQuality: String = QUALITY_BEST,
    val notificationsEnabled: Boolean = true,
    val theme: String = THEME_SYSTEM,
    val saveFolderUri: String = "",
    val batteryPromptDone: Boolean = false,
    val searchEngine: String = "google",
    val customSearchUrl: String = "",
    val searchSuggestions: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val DEFAULT_CONNECTIONS = 4
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val MIN_CONNECTIONS = 1
        const val MAX_CONNECTIONS_PER_FILE = 16

        const val QUALITY_BEST = "best"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        const val KEY_CONNECTIONS_PER_FILE = "connections_per_file"
        const val KEY_MAX_TOTAL_CONNECTIONS = "max_total_connections"
        const val KEY_SPEED_LIMIT_BPS = "speed_limit_bps"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_RESUME_ON_BOOT = "resume_on_boot"
        const val KEY_DEFAULT_HLS_QUALITY = "default_hls_quality"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_THEME = "theme"
        const val KEY_SAVE_FOLDER_URI = "save_folder_uri"
        const val KEY_BATTERY_PROMPT_DONE = "battery_prompt_done"
        const val KEY_SEARCH_ENGINE = "search_engine"
        const val KEY_CUSTOM_SEARCH_URL = "custom_search_url"
        const val KEY_SEARCH_SUGGESTIONS = "search_suggestions"

        val HLS_QUALITY_OPTIONS = listOf(QUALITY_BEST, "1080", "720", "480", "360")
        val THEME_OPTIONS = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

        fun fromRows(rows: Map<String, String>): EngineSettings = EngineSettings(
            maxConcurrentDownloads = rows[KEY_MAX_CONCURRENT]?.toIntOrNull() ?: DEFAULT_MAX_CONCURRENT,
            connectionsPerFile = rows[KEY_CONNECTIONS_PER_FILE]?.toIntOrNull() ?: DEFAULT_CONNECTIONS,
            maxTotalConnections = rows[KEY_MAX_TOTAL_CONNECTIONS]?.toIntOrNull() ?: DEFAULT_MAX_CONNECTIONS,
            speedLimitBps = rows[KEY_SPEED_LIMIT_BPS]?.toLongOrNull() ?: 0L,
            wifiOnly = rows[KEY_WIFI_ONLY]?.toBooleanStrictOrNull() ?: false,
            resumeOnBoot = rows[KEY_RESUME_ON_BOOT]?.toBooleanStrictOrNull() ?: false,
            defaultHlsQuality = rows[KEY_DEFAULT_HLS_QUALITY]?.takeIf { it in HLS_QUALITY_OPTIONS } ?: QUALITY_BEST,
            notificationsEnabled = rows[KEY_NOTIFICATIONS]?.toBooleanStrictOrNull() ?: true,
            theme = rows[KEY_THEME]?.takeIf { it in THEME_OPTIONS } ?: THEME_SYSTEM,
            saveFolderUri = rows[KEY_SAVE_FOLDER_URI].orEmpty(),
            batteryPromptDone = rows[KEY_BATTERY_PROMPT_DONE]?.toBooleanStrictOrNull() ?: false,
            searchEngine = rows[KEY_SEARCH_ENGINE]?.ifBlank { null } ?: "google",
            customSearchUrl = rows[KEY_CUSTOM_SEARCH_URL].orEmpty(),
            searchSuggestions = rows[KEY_SEARCH_SUGGESTIONS]?.toBooleanStrictOrNull() ?: true,
        )
    }

    fun connectionsPerFileCapped(): Int =
        connectionsPerFile.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS_PER_FILE)

    fun maxConcurrentCapped(): Int = maxConcurrentDownloads.coerceAtLeast(1)

    fun toRows(): Map<String, String> = mapOf(
        KEY_MAX_CONCURRENT to maxConcurrentCapped().toString(),
        KEY_CONNECTIONS_PER_FILE to connectionsPerFileCapped().toString(),
        KEY_MAX_TOTAL_CONNECTIONS to maxTotalConnections.coerceIn(1, 64).toString(),
        KEY_SPEED_LIMIT_BPS to speedLimitBps.coerceAtLeast(0).toString(),
        KEY_WIFI_ONLY to wifiOnly.toString(),
        KEY_RESUME_ON_BOOT to resumeOnBoot.toString(),
        KEY_DEFAULT_HLS_QUALITY to (defaultHlsQuality.takeIf { it in HLS_QUALITY_OPTIONS } ?: QUALITY_BEST),
        KEY_NOTIFICATIONS to notificationsEnabled.toString(),
        KEY_THEME to (theme.takeIf { it in THEME_OPTIONS } ?: THEME_SYSTEM),
        KEY_SAVE_FOLDER_URI to saveFolderUri,
        KEY_BATTERY_PROMPT_DONE to batteryPromptDone.toString(),
        KEY_SEARCH_ENGINE to searchEngine,
        KEY_CUSTOM_SEARCH_URL to customSearchUrl,
        KEY_SEARCH_SUGGESTIONS to searchSuggestions.toString(),
    )
}
