package dev.ahmed.spotter.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedUrlTest {
    @Test
    fun planSurvivesTheUrlRoundTrip() {
        val plan = ResolvedPlan(
            page = "https://www.youtube.com/watch?v=abc",
            title = "Title with é and / and ?",
            optionId = "v720",
            kind = "MERGED",
            video = PlanStream("https://x.googlevideo.com/videoplayback?a=1&b=2", 123456789L, "video/mp4", ".mp4"),
            audio = PlanStream("https://x.googlevideo.com/videoplayback?a=3", 456L, "audio/mp4", ".m4a"),
        )
        val url = ResolvedUrls.encode(plan)
        assertTrue(ResolvedUrls.isResolved(url))
        assertFalse("must be a single safe token", url.any { it == ' ' || it == '\n' })
        assertEquals(plan, ResolvedUrls.decode(url))
    }

    @Test
    fun garbageAndForeignUrlsDecodeToNull() {
        assertNull(ResolvedUrls.decode("https://example.com/a.mp4"))
        assertNull(ResolvedUrls.decode(ResolvedUrls.PREFIX + "!!!not-base64!!!"))
        assertNull(ResolvedUrls.decode(ResolvedUrls.PREFIX + "bm90IGpzb24"))
    }

    @Test
    fun youtubeLinkDetection() {
        fun yes(u: String) = assertTrue(u, YoutubeResolver.isYoutubeVideoUrl(u))
        fun no(u: String) = assertFalse(u, YoutubeResolver.isYoutubeVideoUrl(u))
        yes("https://www.youtube.com/watch?v=bzGU0b5XAKc")
        yes("https://m.youtube.com/watch?v=bzGU0b5XAKc&t=10")
        yes("https://youtu.be/bzGU0b5XAKc")
        yes("https://www.youtube.com/shorts/abc123")
        yes("https://music.youtube.com/watch?v=abc")
        yes("https://www.youtube.com/watch?v=abc&list=PL123")
        no("https://www.youtube.com/playlist?list=PL123")
        no("https://example.com/watch?v=abc")
        no("https://vimeo.com/123")
    }
}
