package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaNamesTest {
    private fun item(name: String, type: String = "video/mp4", url: String = "https://cdn.example/a.mp4") =
        DetectedItem(
            url = url,
            kind = MediaKind.MEDIA,
            type = type,
            size = 1,
            headers = emptyMap(),
            pageUrl = "https://p/",
            pageTitle = "T",
            suggestedName = name,
            detectedAt = 1,
        )

    @Test
    fun customNameHonoured() {
        assertEquals("My Clip.mp4", MediaNames.finalize("My Clip.mp4", item("Show.mp4")))
    }

    @Test
    fun missingExtensionAppended() {
        assertEquals("My Clip.mp4", MediaNames.finalize("My Clip", item("Show.mp4")))
    }

    @Test
    fun blankFallsBackToSuggested() {
        assertEquals("Show.mp4", MediaNames.finalize("  ", item("Show.mp4")))
    }

    @Test
    fun playlistKeepsM3u8() {
        val playlist = item("Show.m3u8", "HLS/DASH stream", "https://cdn.example/master.m3u8")
            .copy(kind = MediaKind.PLAYLIST)
        assertEquals("Show.m3u8", MediaNames.finalize("Show", playlist))
    }
}
