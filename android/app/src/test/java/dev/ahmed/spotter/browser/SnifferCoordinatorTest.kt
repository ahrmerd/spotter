package dev.ahmed.spotter.browser

import dev.ahmed.spotter.engine.net.ProbeResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnifferCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun coordinator(
        probe: (String, Map<String, String>) -> ProbeResult? = { _, _ -> null },
        cookie: (String) -> String? = { null },
        persisted: MutableList<Pair<Long, List<DetectedItem>>> = mutableListOf(),
    ) = SnifferCoordinator(
        scope = scope,
        io = dispatcher,
        probe = probe,
        cookieOf = cookie,
        persist = { id, items -> persisted += id to items },
        clock = { 10 },
    )

    @Test
    fun interceptRecordsMp4AndSkipsSegment() {
        val c = coordinator()
        c.onIntercept(1, "https://cdn.example/a.mp4", mapOf("Referer" to "https://iframe.example/"), "https://p/", "T")
        c.onIntercept(1, "https://cdn.example/seg.ts", emptyMap(), "https://p/", "T")
        assertEquals(listOf("https://cdn.example/a.mp4"), c.items(1).map { it.url })
        assertEquals("https://iframe.example/", c.items(1).single().headers["Referer"])
    }

    @Test
    fun cookieMergedWhenInterceptLacksIt() {
        val c = coordinator(cookie = { "sid=1" })
        c.onIntercept(1, "https://cdn.example/a.mp4", mapOf("Origin" to "https://iframe.example"), "https://p/", "T")
        assertEquals("sid=1", c.items(1).single().headers["Cookie"])
        assertEquals("https://iframe.example", c.items(1).single().headers["Origin"])
    }

    @Test
    fun jsUnknownIsProbedAndRefined() = scope.runTest {
        val c = coordinator(
            probe = { _, _ ->
                ProbeResult(size = 999_000, ranged = true, filename = "mystery.mp4", mimeType = "video/mp4", etag = null, lastModified = null)
            },
        )
        c.onJs(1, "https://cdn.example/mystery", "https://p/", "T", play = false)
        assertEquals(MediaKind.UNKNOWN, c.items(1).single().kind)
        advanceUntilIdle()
        assertEquals(MediaKind.MEDIA, c.items(1).single().kind)
        assertEquals(999_000L, c.items(1).single().size)
    }

    @Test
    fun restoreSurvivesFirstNavigationThenClears() {
        val item = DetectedItem(
            url = "https://cdn.example/a.mp4",
            kind = MediaKind.MEDIA,
            type = "video/mp4",
            size = 1,
            headers = emptyMap(),
            pageUrl = "https://p/",
            pageTitle = "T",
            suggestedName = "T.mp4",
            detectedAt = 1,
        )
        val c = coordinator()
        c.restore(1, listOf(item))
        c.onNavigation(1)
        assertEquals(1, c.items(1).size)
        c.onNavigation(1)
        assertTrue(c.items(1).isEmpty())
    }

    @Test
    fun rejectsJavascriptUrl() {
        val c = coordinator()
        c.onJs(1, "javascript:alert(1)", "https://p/", "T", play = false)
        c.onIntercept(1, "file:///sdcard/x.mp4", emptyMap(), "https://p/", "T")
        assertTrue(c.items(1).isEmpty())
    }
}
