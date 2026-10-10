package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSettingsTest {
    @Test
    fun roundTripAllKeys() {
        val original = EngineSettings(
            maxConcurrentDownloads = 5,
            connectionsPerFile = 8,
            maxTotalConnections = 40,
            speedLimitBps = 256 * 1024,
            wifiOnly = true,
            resumeOnBoot = true,
            defaultHlsQuality = "720",
            notificationsEnabled = false,
            theme = EngineSettings.THEME_DARK,
            saveFolderUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            batteryPromptDone = true,
        )
        val restored = EngineSettings.fromRows(original.toRows())
        assertEquals(original, restored)
    }

    @Test
    fun missingRowsUseDefaults() {
        val s = EngineSettings.fromRows(emptyMap())
        assertEquals(3, s.maxConcurrentDownloads)
        assertEquals(EngineSettings.QUALITY_BEST, s.defaultHlsQuality)
        assertTrue(s.notificationsEnabled)
        assertEquals(EngineSettings.THEME_SYSTEM, s.theme)
        assertEquals("", s.saveFolderUri)
        assertFalse(s.batteryPromptDone)
    }

    @Test
    fun rejectsUnknownThemeAndQuality() {
        val s = EngineSettings.fromRows(
            mapOf(
                EngineSettings.KEY_THEME to "neon",
                EngineSettings.KEY_DEFAULT_HLS_QUALITY to "144",
            ),
        )
        assertEquals(EngineSettings.THEME_SYSTEM, s.theme)
        assertEquals(EngineSettings.QUALITY_BEST, s.defaultHlsQuality)
    }
}
