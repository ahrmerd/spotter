package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTitlesTest {
    @Test
    fun stripsNotificationCountAndYouTubeSuffix() {
        assertEquals("Cool Video", PageTitles.clean("(48) Cool Video - YouTube"))
    }

    @Test
    fun stripsOtherSiteSuffixes() {
        assertEquals("A clip", PageTitles.clean("A clip - TikTok"))
        assertEquals("Post", PageTitles.clean("Post | X"))
    }

    @Test
    fun suggestedNameGetsExtension() {
        val name = PageTitles.suggestedFilename("(3) Talk - YouTube", "https://cdn.example/a.mp4", "video/mp4")
        assertEquals("Talk.mp4", name)
    }

    @Test
    fun blankTitleFallsBackToUrlName() {
        val name = PageTitles.suggestedFilename(null, "https://cdn.example/file.webm", null)
        assertEquals("file.webm", name)
    }

    @Test
    fun sanitizesIllegalFilenameChars() {
        val cleaned = PageTitles.clean("a/b:c*")
        assertFalse(cleaned.contains('/'))
        assertFalse(cleaned.contains(':'))
        assertTrue(cleaned.isNotBlank())
    }
}
