package dev.ahmed.spotter.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFramesTest {
    @Test
    fun ignoresAdIframesOnUnrelatedPages() {
        assertTrue(AdFrames.ignore("https://pagead2.googlesyndication.com", "https://watch.example/ep"))
        assertTrue(AdFrames.ignore("https://ads.doubleclick.net", "https://watch.example/ep"))
        assertFalse(AdFrames.ignore("https://kwik.cx/e/abc", "https://watch.example/ep"))
        assertFalse(AdFrames.ignore("", "https://watch.example/ep"))
    }

    @Test
    fun keepsReportsWhenThePageItselfIsThatHost() {
        assertFalse(AdFrames.ignore("https://www.facebook.com", "https://www.facebook.com/watch"))
        assertFalse(AdFrames.ignore("https://m.facebook.com", "https://www.facebook.com/"))
    }
}
