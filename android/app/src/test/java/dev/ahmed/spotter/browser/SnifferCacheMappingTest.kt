package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SnifferCacheMappingTest {
    @Test
    fun roundTripPreservesHeadersAndPlayFlag() {
        val item = DetectedItem(
            url = "https://cdn.example/a.mp4",
            kind = MediaKind.MEDIA,
            type = "video/mp4",
            size = 1234,
            headers = mapOf("Referer" to "https://iframe.example/", "Origin" to "https://iframe.example"),
            pageUrl = "https://page.example/",
            pageTitle = "Show",
            suggestedName = "Show.mp4",
            detectedAt = 99,
            fromPlay = true,
        )
        val back = item.toCacheEntity(7).toDetectedItem()
        assertEquals(item, back)
    }
}
