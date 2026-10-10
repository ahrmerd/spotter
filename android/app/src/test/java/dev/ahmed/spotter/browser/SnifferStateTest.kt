package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferStateTest {
    @Test
    fun newestFirstAndClearedOnNavigation() {
        val s = SnifferState()
        s.observe(1, "https://cdn.example/a.mp4", mapOf("Referer" to "https://iframe.example/"), "https://page.example/", "A", now = 10)
        s.observe(1, "https://cdn.example/b.mp4", emptyMap(), "https://page.example/", "A", now = 20)
        val items = s.items(1)
        assertEquals(listOf("https://cdn.example/b.mp4", "https://cdn.example/a.mp4"), items.map { it.url })
        assertEquals("https://iframe.example/", items.last().headers["Referer"])
        s.onNavigation(1)
        assertTrue(s.items(1).isEmpty())
    }

    @Test
    fun playEventPinsToTop() {
        val s = SnifferState()
        s.observe(1, "https://cdn.example/old.mp4", emptyMap(), "https://p/", "T", now = 1)
        s.observe(1, "https://cdn.example/play.mp4", emptyMap(), "https://p/", "T", fromPlay = true, now = 2)
        s.observe(1, "https://cdn.example/new.mp4", emptyMap(), "https://p/", "T", now = 3)
        assertEquals("https://cdn.example/play.mp4", s.items(1).first().url)
    }

    @Test
    fun skipsSegmentsAndDoesNotUsePlaylistLength() {
        val s = SnifferState()
        assertNull(s.observe(1, "https://cdn.example/seg.ts", emptyMap(), "https://p/", "T", contentLength = 1_000_000))
        val item = s.observe(
            1,
            "https://cdn.example/master.m3u8",
            mapOf("Origin" to "https://iframe.example"),
            "https://p/",
            "(1) Show - YouTube",
            mimeType = "application/vnd.apple.mpegurl",
            contentLength = 380,
        )!!
        assertEquals(MediaKind.PLAYLIST, item.kind)
        assertNull(item.size)
        assertEquals("Show.m3u8", item.suggestedName)
        assertEquals("https://iframe.example", item.headers["Origin"])
    }

    @Test
    fun jsUnknownUrlIsKeptForProbe() {
        val s = SnifferState()
        val item = s.observe(
            1,
            "https://cdn.example/mystery",
            emptyMap(),
            "https://p/",
            "T",
            fromJs = true,
        )!!
        assertEquals(MediaKind.UNKNOWN, item.kind)
    }

    @Test
    fun interceptUnknownWithoutExtIsDropped() {
        val s = SnifferState()
        assertNull(s.observe(1, "https://cdn.example/mystery", emptyMap(), "https://p/", "T", fromJs = false))
    }
}
