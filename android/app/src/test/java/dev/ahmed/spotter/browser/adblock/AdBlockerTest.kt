package dev.ahmed.spotter.browser.adblock

import dev.ahmed.spotter.engine.model.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The behaviour rules, replayed from what animepahe.pw did on the owner's phone (history of 2026-10-11). */
class AdBlockerTest {
    private var now = 1_000_000L
    private var settings = EngineSettings()
    private val page = "https://animepahe.pw/play/abc"

    private fun blocker(vararg rules: String) = AdBlocker(
        openLists = { emptyList() }, settings = { settings }, clock = { now },
    ).apply { setEngineForTest(FilterEngine().apply { rules.forEach { addLine(it) } }) }

    @Test
    fun tapOnPageThatIsNotALinkCannotOpenAWindow() {
        val b = blocker()
        assertTrue(b.popupBlocked(1, page, tapOnLink = false))
        assertEquals(1, b.blockedCount(1))
        assertFalse(b.popupBlocked(1, page, tapOnLink = true))
    }

    @Test
    fun scriptRedirectToAnotherSiteIsBlockedButNotAfterAUserTap() {
        val b = blocker()
        // ayhal.com redirect loop: no gesture, other site, long after the last tap.
        assertTrue(b.navigationBlocked(1, "https://ayhal.com/x", tapOnLink = false, pageUrl = page))
        // Same site is fine.
        assertFalse(b.navigationBlocked(1, "https://animepahe.pw/next", tapOnLink = false, pageUrl = page))
        // The user tapped a link, then the other site redirects (no gesture): allowed inside the grace window.
        assertFalse(b.navigationBlocked(2, "https://example.org/a", tapOnLink = true, pageUrl = page))
        now += 1000
        assertFalse(b.navigationBlocked(2, "https://example.org/b", tapOnLink = false, pageUrl = "https://example.org/a"))
        // ...but not forever.
        now += AdBlocker.USER_NAV_GRACE_MS + 1
        assertTrue(b.navigationBlocked(2, "https://tracker.example.net/c", tapOnLink = false, pageUrl = "https://example.org/b"))
    }

    @Test
    fun firstTapOnThePlayerCannotSendTheTabToAnAdNetwork() {
        // animepahe.pw 2026-10-11: tapping the player opened a casino tab and moved the page to crdema.com.
        val b = blocker()
        assertTrue(b.popupBlocked(1, page, tapOnLink = false))
        assertTrue(b.navigationBlocked(1, "https://crdema.com/pu/?psid=1", tapOnLink = false, pageUrl = page))
        assertEquals(2, b.blockedCount(1))
    }

    @Test
    fun typedAddressStartsAGracePeriod() {
        val b = blocker()
        b.noteUserNavigation(3)
        assertFalse(b.navigationBlocked(3, "https://www.other.com/", tapOnLink = false, pageUrl = "https://other.com/"))
        assertFalse(b.navigationBlocked(3, "https://m.shop.com/", tapOnLink = false, pageUrl = ""))
    }

    @Test
    fun listedHostIsBlockedEvenWithATap() {
        val b = blocker("||21bet.ng^\$popup,document")
        assertTrue(b.navigationBlocked(1, "https://21bet.ng/?x=1", tapOnLink = true, pageUrl = page))
    }

    @Test
    fun subRequestsAreBlockedButMediaIsNot() {
        val b = blocker("||073m.com^", "||cdn.ad.net^")
        assertTrue(b.requestBlocked(1, "https://073m.com/afu.php", FilterEngine.SCRIPT, page))
        assertFalse(b.requestBlocked(1, "https://cdn.ad.net/master.m3u8", FilterEngine.XHR, page))
        assertFalse(b.requestBlocked(1, "https://cdn.ad.net/video.mp4", FilterEngine.MEDIA, page))
        assertFalse(b.requestBlocked(1, "https://fine.com/app.js", FilterEngine.SCRIPT, page))
    }

    @Test
    fun switchOffGlobalAndPerSite() {
        val b = blocker("||073m.com^")
        settings = EngineSettings(adBlock = false)
        assertFalse(b.requestBlocked(1, "https://073m.com/a", FilterEngine.SCRIPT, page))
        assertFalse(b.popupBlocked(1, page, false))
        settings = EngineSettings(adBlockOff = "animepahe.pw")
        assertFalse(b.requestBlocked(1, "https://073m.com/a", FilterEngine.SCRIPT, page))
        assertTrue(b.requestBlocked(1, "https://073m.com/a", FilterEngine.SCRIPT, "https://other.com/"))
        assertEquals(null, b.pageScript(page))
    }

    @Test
    fun requestTypesFromExtensionAndAccept() {
        assertEquals(FilterEngine.SCRIPT, AdBlocker.typeOf("https://a.com/x.js?v=1", null, false))
        assertEquals(FilterEngine.IMAGE, AdBlocker.typeOf("https://a.com/x.png", null, false))
        assertEquals(FilterEngine.STYLESHEET, AdBlocker.typeOf("https://a.com/x", "text/css,*/*", false))
        assertEquals(FilterEngine.DOCUMENT, AdBlocker.typeOf("https://a.com/", null, true))
    }
}
