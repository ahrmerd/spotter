package dev.ahmed.spotter.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorRedactTest {
    @Test
    fun redactsCookieAuthorizationAndTokens() {
        val raw = "GET https://cdn.example/v.mp4?token=SECRET123&key=abcd " +
            "Cookie: sid=SECRETCOOKIE Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
        val out = ErrorRedact.redact(raw)
        assertFalse("out=$out", out.contains("SECRET123"))
        assertFalse("out=$out", out.contains("SECRETCOOKIE"))
        assertFalse("out=$out", out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue("out=$out", out.contains("***"))
    }

    @Test
    fun copyTextIncludesFilenameAndRedactedUrl() {
        val text = ErrorRedact.copyText(
            filename = "clip.mp4",
            url = "https://cdn.example/v.mp4?token=SECRET123",
            state = "ERROR",
            error = "Authorization: Bearer abcdef",
        )
        assertTrue(text.contains("clip.mp4"))
        assertFalse(text.contains("SECRET123"))
        assertFalse(text.contains("abcdef"))
        assertTrue(text.contains("ERROR"))
    }
}
