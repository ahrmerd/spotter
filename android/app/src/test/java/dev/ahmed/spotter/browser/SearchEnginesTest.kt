package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEnginesTest {
    @Test
    fun googleIsTheDefaultAndQueriesAreEncoded() {
        assertEquals("https://www.google.com/search?q=cats+%26+dogs", AddressBar.resolve("cats & dogs"))
        assertEquals("https://duckduckgo.com/?q=a+b", AddressBar.resolve("a b", "duckduckgo"))
        assertEquals("https://www.bing.com/search?q=x+y", AddressBar.resolve("x y", "bing"))
        assertEquals("https://search.brave.com/search?q=x+y", AddressBar.resolve("x y", "brave"))
    }

    @Test
    fun customTemplateIsUsedOnlyWhenItIsAnHttpUrlWithAPlaceholder() {
        assertEquals("https://s.example/?q=hello+there", AddressBar.resolve("hello there", "custom", "https://s.example/?q=%s"))
        // Broken templates fall back to Google instead of producing a bad URL.
        assertEquals("https://www.google.com/search?q=hi+x", AddressBar.resolve("hi x", "custom", "no placeholder"))
        assertEquals("https://www.google.com/search?q=hi+x", AddressBar.resolve("hi x", "custom", "javascript:%s"))
        assertEquals("https://www.google.com/search?q=hi+x", AddressBar.resolve("hi x", "unknown-engine"))
    }

    @Test
    fun linksStayLinks() {
        assertEquals("https://example.com/a", AddressBar.resolve("https://example.com/a", "bing"))
        assertEquals("https://example.com", AddressBar.resolve("example.com", "bing"))
    }

    @Test
    fun suggestionsAreNeverRequestedForUrlsOrShortText() {
        assertFalse(SearchSuggestions.shouldSuggest("a"))
        assertFalse(SearchSuggestions.shouldSuggest("  "))
        assertFalse(SearchSuggestions.shouldSuggest("https://example.com/secret?token=1"))
        assertFalse(SearchSuggestions.shouldSuggest("example.com"))
        assertFalse(SearchSuggestions.shouldSuggest("www.example"))
        assertFalse(SearchSuggestions.shouldSuggest("10.0.2.2:8080/x"))
        assertTrue(SearchSuggestions.shouldSuggest("ca"))
        assertTrue(SearchSuggestions.shouldSuggest("cats and dogs"))
        assertTrue(SearchSuggestions.shouldSuggest("how to fix example.com errors"))
    }

    @Test
    fun openSearchJsonIsParsed() {
        val body = """["cat",["cat videos","cat food","cat videos","",5],[],{"google:suggesttype":["QUERY"]}]"""
        assertEquals(listOf("cat videos", "cat food"), SearchSuggestions.parse(body))
        assertEquals(emptyList<String>(), SearchSuggestions.parse("not json"))
        assertEquals(emptyList<String>(), SearchSuggestions.parse("""{"a":1}"""))
        assertEquals(3, SearchSuggestions.parse("""["q",["1","2","3","4"]]""", limit = 3).size)
    }

    @Test
    fun hostAndSecurityHelpers() {
        assertEquals("example.com", AddressBar.hostOf("https://www.example.com/a/b?x=1"))
        assertEquals("10.0.2.2:8080", AddressBar.hostOf("http://10.0.2.2:8080/p"))
        assertTrue(AddressBar.isSecure("https://a.b"))
        assertFalse(AddressBar.isSecure("http://a.b"))
    }
}
