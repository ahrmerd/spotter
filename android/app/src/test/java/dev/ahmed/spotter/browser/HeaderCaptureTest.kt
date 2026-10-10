package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderCaptureTest {
    @Test
    fun keepsIframeRefererAndOrigin() {
        val kept = HeaderCapture.keep(
            mapOf(
                "referer" to "https://kwik.example/player",
                "Origin" to "https://kwik.example",
                "Accept" to "*/*",
                "cookie" to "sid=secret",
            ),
        )
        assertEquals("https://kwik.example/player", kept["Referer"])
        assertEquals("https://kwik.example", kept["Origin"])
        assertEquals("sid=secret", kept["Cookie"])
        assertFalse(kept.containsKey("Accept"))
    }

    @Test
    fun mergeCookieDoesNotOverwriteIntercepted() {
        val merged = HeaderCapture.mergeCookie(mapOf("Cookie" to "a=1"), "b=2")
        assertEquals("a=1", merged["Cookie"])
        val added = HeaderCapture.mergeCookie(emptyMap(), "b=2")
        assertEquals("b=2", added["Cookie"])
    }

    @Test
    fun redactHidesCookie() {
        val r = HeaderCapture.redact(mapOf("Cookie" to "sid=supersecret", "Referer" to "https://x"))
        assertTrue(r["Cookie"]!!.startsWith("<redacted"))
        assertEquals("https://x", r["Referer"])
    }
}
