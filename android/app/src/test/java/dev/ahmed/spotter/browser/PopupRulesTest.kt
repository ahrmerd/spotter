package dev.ahmed.spotter.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRulesTest {
    @Test
    fun windowOpenRequiresGesture() {
        assertFalse(PopupRules.allowNewWindow(userGesture = false))
        assertTrue(PopupRules.allowNewWindow(userGesture = true))
    }

    @Test
    fun blocksKnownAdHosts() {
        assertTrue(PopupRules.blockHost("https://ad.doubleclick.net/jump", typedUrl = "https://example.com/watch"))
        assertTrue(PopupRules.blockHost("https://foo.googlesyndication.com/x", typedUrl = null))
    }

    @Test
    fun neverBlocksTypedMainFrameHost() {
        assertFalse(
            PopupRules.blockHost(
                "https://doubleclick.net/",
                typedUrl = "https://doubleclick.net/campaign",
            ),
        )
    }
}
