package dev.ahmed.spotter.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionBuilderTest {
    private fun v(h: Int, fmt: String = "mp4", codec: String = "avc1.640028", br: Int = 1000, size: Long = h * 1000L) =
        RawVideo(h, fmt, codec, "https://v/$h/$fmt/$codec/$br", size, br)

    private fun a(fmt: String, kbps: Int, size: Long = 1000L) = RawAudio(fmt, kbps, "https://a/$fmt/$kbps", size)

    @Test
    fun mergedOptionsUseH264MpAndM4aOnlyAndAreSortedHighestFirst() {
        val opts = OptionBuilder.build(
            videoOnly = listOf(v(720), v(1080), v(1080, "webm", "vp09"), v(360), v(720, br = 500)),
            muxed = listOf(v(360, size = 5000)),
            audio = listOf(a("m4a", 48), a("m4a", 128), a("webm", 160)),
        )
        assertEquals(listOf("v1080", "v720", "v360", "a"), opts.map { it.id })
        val best = opts.first()
        assertEquals(OptionKind.MERGED, best.kind)
        assertTrue(best.video!!.url.contains("/1080/mp4/avc1"))
        assertTrue("best AAC bitrate is paired", best.audio!!.url.endsWith("m4a/128"))
        assertEquals(1080 * 1000L + 1000L, best.approxBytes)
    }

    @Test
    fun webmOnlyHeightsAreNotOffered() {
        val opts = OptionBuilder.build(
            videoOnly = listOf(v(2160, "webm", "vp09")),
            muxed = emptyList(),
            audio = listOf(a("m4a", 128)),
        )
        assertEquals(listOf("a"), opts.map { it.id })
    }

    @Test
    fun muxedOptionAppearsWhenNoMergedOptionExistsForThatHeight() {
        val opts = OptionBuilder.build(
            videoOnly = listOf(v(1080)),
            muxed = listOf(v(360), v(1080)),
            audio = listOf(a("m4a", 128)),
        )
        // 1080 is merged (not duplicated as muxed); 360 only exists muxed.
        assertEquals(listOf("v1080", "m360", "a"), opts.map { it.id })
        assertEquals(OptionKind.MUXED, opts[1].kind)
        assertNull(opts[1].audio)
    }

    @Test
    fun withoutAnAacTrackOnlyMuxedOptionsRemain() {
        val opts = OptionBuilder.build(
            videoOnly = listOf(v(1080)),
            muxed = listOf(v(360)),
            audio = listOf(a("webm", 160)),
        )
        assertEquals(listOf("m360"), opts.map { it.id })
        assertFalse(opts.any { it.kind == OptionKind.AUDIO_ONLY })
    }

    @Test
    fun emptyInputGivesNoOptions() {
        assertTrue(OptionBuilder.build(emptyList(), emptyList(), emptyList()).isEmpty())
    }
}
