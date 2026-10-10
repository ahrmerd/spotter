package dev.ahmed.spotter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareUrlsTest {
    @Test
    fun extractsBareUrl() {
        assertEquals("https://example.com/a.bin", ShareUrls.extractHttpUrl("https://example.com/a.bin"))
    }

    @Test
    fun extractsUrlFromSentence() {
        assertEquals(
            "http://10.0.2.2:8080/file.mp4",
            ShareUrls.extractHttpUrl("watch this http://10.0.2.2:8080/file.mp4 please"),
        )
    }

    @Test
    fun trimsTrailingPunctuation() {
        assertEquals("https://ex.com/x", ShareUrls.extractHttpUrl("see https://ex.com/x."))
    }

    @Test
    fun rejectsNonHttp() {
        assertNull(ShareUrls.extractHttpUrl("ftp://example.com/a"))
        assertNull(ShareUrls.extractHttpUrl("not a url"))
        assertNull(ShareUrls.extractHttpUrl(null))
    }
}
