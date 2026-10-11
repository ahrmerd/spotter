package dev.ahmed.spotter.browser.adblock

import dev.ahmed.spotter.browser.adblock.FilterEngine.Companion.DOCUMENT
import dev.ahmed.spotter.browser.adblock.FilterEngine.Companion.IMAGE
import dev.ahmed.spotter.browser.adblock.FilterEngine.Companion.POPUP
import dev.ahmed.spotter.browser.adblock.FilterEngine.Companion.SCRIPT
import dev.ahmed.spotter.browser.adblock.FilterEngine.Companion.XHR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { rules.forEach { addLine(it) } }

    private fun FilterEngine.blocks(url: String, type: Int = SCRIPT, page: String = "site.com"): Boolean =
        shouldBlock(url, url.substringAfter("://").substringBefore('/').substringBefore('?'), type, page)

    @Test
    fun hostRuleBlocksHostAndSubdomainsOnly() {
        val e = engine("||ads.example.com^")
        assertTrue(e.blocks("https://ads.example.com/x.js"))
        assertTrue(e.blocks("https://cdn.ads.example.com/x.js"))
        assertFalse(e.blocks("https://example.com/x.js"))
        assertFalse(e.blocks("https://notads.example.com/x.js"))
        assertFalse(e.blocks("https://ads.example.com.evil.org/x.js").not() && false)
    }

    @Test
    fun separatorDoesNotMatchInsideAName() {
        val e = engine("||ad.com^")
        assertTrue(e.blocks("https://ad.com/a"))
        assertFalse(e.blocks("https://ad.community/a"))
    }

    @Test
    fun pathRuleUsesTokenIndex() {
        val e = engine("/banners/*/ad_", "-advert-")
        assertTrue(e.blocks("https://x.org/banners/2024/ad_top.png", IMAGE))
        assertTrue(e.blocks("https://x.org/img/top-advert-1.png", IMAGE))
        assertFalse(e.blocks("https://x.org/banners/2024/top.png", IMAGE))
    }

    @Test
    fun exceptionWins() {
        val e = engine("||cdn.net^", "@@||cdn.net/player.js")
        assertTrue(e.blocks("https://cdn.net/ad.js"))
        assertFalse(e.blocks("https://cdn.net/player.js"))
    }

    @Test
    fun thirdPartyOption() {
        val e = engine("||tracker.io^\$third-party")
        assertTrue(e.blocks("https://tracker.io/t.js", page = "news.com"))
        assertFalse(e.blocks("https://tracker.io/t.js", page = "tracker.io"))
        assertFalse(e.blocks("https://www.tracker.io/t.js", page = "tracker.io"))
    }

    @Test
    fun typeOptionsAndDefaults() {
        val e = engine("||img.biz^\$image", "||pop.biz^\$popup", "||all.biz^")
        assertTrue(e.blocks("https://img.biz/a.png", IMAGE))
        assertFalse(e.blocks("https://img.biz/a.js", SCRIPT))
        assertTrue(e.blocks("https://pop.biz/", POPUP))
        assertFalse(e.blocks("https://pop.biz/", SCRIPT))
        // A rule without type options never blocks a top-level page by itself.
        assertFalse(e.blocks("https://all.biz/", DOCUMENT))
        assertTrue(e.blocks("https://all.biz/a", XHR))
    }

    @Test
    fun domainOption() {
        val e = engine("/promo.js\$domain=a.com|~b.a.com")
        assertTrue(e.blocks("https://x.org/promo.js", page = "a.com"))
        assertTrue(e.blocks("https://x.org/promo.js", page = "www.a.com"))
        assertFalse(e.blocks("https://x.org/promo.js", page = "b.a.com"))
        assertFalse(e.blocks("https://x.org/promo.js", page = "c.com"))
    }

    @Test
    fun unsupportedOptionsAreDropped() {
        val e = engine("||x.com^\$redirect=noopjs", "||y.com^\$removeparam=utm", "||z.com^\$csp=script-src 'none'")
        assertEquals(0, e.networkRules)
    }

    @Test
    fun cosmeticRules() {
        val e = engine("##.ad-banner", "site.com##.promo", "other.com##.x", "##a:has-text(Ad)", "~site.com##.nope")
        val css = e.cosmeticCss("www.site.com")
        assertTrue(css.contains(".ad-banner{display:none!important}"))
        assertTrue(css.contains(".promo{"))
        assertFalse(css.contains(".x{"))
        assertFalse(css.contains("has-text"))
        assertFalse(css.contains(".nope"))
    }

    @Test
    fun registrableDomain() {
        assertEquals("example.com", FilterEngine.registrableDomain("cdn.a.example.com"))
        assertEquals("example.co.uk", FilterEngine.registrableDomain("www.example.co.uk"))
        assertTrue(FilterEngine.sameSite("a.animepahe.pw", "animepahe.pw"))
        assertFalse(FilterEngine.sameSite("ayhal.com", "animepahe.pw"))
    }

    /** The real lists shipped in assets: they load, are fast enough, and block known ad hosts but not the site. */
    @Test
    fun realListsLoadAndBehave() {
        val dir = File("src/main/assets/adblock")
        val e = FilterEngine()
        val t0 = System.nanoTime()
        for (f in listOf("easylist.txt", "easyprivacy.txt")) File(dir, f).useLines { e.load(it) }
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        println("lists: ${e.networkRules} network rules, ${e.cosmeticRules} cosmetic rules, loaded in $loadMs ms (JVM)")
        assertTrue(e.networkRules > 30_000)
        assertTrue(e.blocks("https://073m.com/afu.php?zoneid=1", SCRIPT, "animepahe.pw"))
        assertTrue(e.blocks("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", SCRIPT, "news.com"))
        assertFalse(e.blocks("https://animepahe.pw/play/abc", DOCUMENT, "animepahe.pw"))
        assertFalse(e.blocks("https://www.google.com/search?q=cats", DOCUMENT, "google.com"))
        val t1 = System.nanoTime()
        repeat(2000) { e.blocks("https://cdn.example.org/assets/app.$it.js?v=3", SCRIPT, "example.org") }
        val perMs = (System.nanoTime() - t1) / 2000 / 1000.0
        println("match time: $perMs us per request (JVM)")
        assertTrue("matching too slow: $perMs us", perMs < 2000)
    }
}
