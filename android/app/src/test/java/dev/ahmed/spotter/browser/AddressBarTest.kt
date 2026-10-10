package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBarTest {
    @Test
    fun httpsPassthrough() {
        assertEquals("https://example.com/a", AddressBar.resolve("https://example.com/a"))
    }

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://example.com", AddressBar.resolve("example.com"))
    }

    @Test
    fun wordsGoToSearch() {
        val url = AddressBar.resolve("cute otters")
        assertTrue(url.startsWith("https://duckduckgo.com/?q="))
        assertTrue(url.contains("cute"))
    }
}
