package dev.ahmed.spotter.engine.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    @Test
    fun mediaPlaylistRelativeAndAbsoluteAndQuery() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:9.0,
            seg0.ts
            #EXTINF:9.0,
            /abs/seg1.ts?token=a
            #EXTINF:9.0,
            https://cdn.example/v/seg2.ts?x=1
            #EXTINF:4.5,
            ../up/seg3.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val doc = HlsPlaylist.parse(text, "https://cdn.example/hls/video/prog.m3u8?session=1")
        val media = doc as HlsDocument.Media
        assertTrue(media.endList)
        assertEquals(5L, media.mediaSequence)
        assertEquals(4, media.segments.size)
        assertEquals("https://cdn.example/hls/video/seg0.ts", media.segments[0].uri)
        assertEquals(5L, media.segments[0].sequence)
        assertEquals("https://cdn.example/abs/seg1.ts?token=a", media.segments[1].uri)
        assertEquals("https://cdn.example/v/seg2.ts?x=1", media.segments[2].uri)
        assertEquals("https://cdn.example/hls/up/seg3.ts", media.segments[3].uri)
        assertEquals(8L, media.segments[3].sequence)
    }

    @Test
    fun masterStreamInfAndAudioRendition() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="audio.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Commentary",DEFAULT=NO,URI="comment.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio"
            hi.m3u8?token=z
        """.trimIndent()
        val master = HlsPlaylist.parse(text, "https://ex.test/hls/master.m3u8") as HlsDocument.Master
        assertEquals(2, master.variants.size)
        assertEquals("https://ex.test/hls/low.m3u8", master.variants[0].uri)
        assertEquals(640, master.variants[0].width)
        assertEquals(360, master.variants[0].height)
        assertEquals("audio", master.variants[0].audioGroupId)
        val best = master.best()
        assertEquals("https://ex.test/hls/hi.m3u8?token=z", best.uri)
        assertEquals(1, master.bestIndex())
        val audio = master.audioFor(best)
        assertEquals("https://ex.test/hls/audio.m3u8", audio?.uri)
        assertEquals("Best (1280x720 · 2.5 Mbps)", HlsPlaylist.qualityLabel(best, best = true))
        assertEquals("640x360 · 800 kbps", HlsPlaylist.qualityLabel(master.variants[0], best = false))
    }

    @Test
    fun keyMapAndByteRange() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="keys/enc.key",IV=0x0000000000000000000000000000000A
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="1200@0"
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:500@1200
            main.mp4
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:500
            main.mp4
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2.0,
            plain.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsPlaylist.parse(text, "https://ex.test/fmp4/prog.m3u8") as HlsDocument.Media
        assertEquals("https://ex.test/fmp4/init.mp4", media.map?.uri)
        assertEquals(1200L, media.map?.byteRange?.length)
        assertEquals(0L, media.map?.byteRange?.offset)
        assertEquals(HlsKeyMethod.AES_128, media.segments[0].key?.method)
        assertEquals("https://ex.test/fmp4/keys/enc.key", media.segments[0].key?.uri)
        assertArrayEquals(HlsPlaylist.parseIv("0x0000000000000000000000000000000A"), media.segments[0].key?.iv)
        assertEquals(HlsByteRange(500, 1200), media.segments[0].byteRange)
        assertEquals(HlsByteRange(500, 1700), media.segments[1].byteRange)
        assertEquals(HlsKeyMethod.NONE, media.segments[2].key?.method)
        assertTrue(media.endList)
    }

    @Test
    fun sequenceIvIsLow64Bits() {
        val iv = HlsPlaylist.sequenceIv(10)
        assertEquals(16, iv.size)
        assertTrue(iv.copyOfRange(0, 15).all { it == 0.toByte() })
        assertEquals(10.toByte(), iv[15])
        val big = HlsPlaylist.sequenceIv(0x0102030405060708)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            big,
        )
    }

    @Test
    fun sampleAesAndDrmAreDistinct() {
        val sample = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:1.0,
            a.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://ex.test/a.m3u8",
        ) as HlsDocument.Media
        assertEquals(HlsKeyMethod.SAMPLE_AES, sample.segments[0].key?.method)

        val drm = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:1.0,
            a.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://ex.test/a.m3u8",
        ) as HlsDocument.Media
        assertEquals(HlsKeyMethod.DRM, drm.segments[0].key?.method)
    }

    @Test
    fun liveHasNoEndlist() {
        val media = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:6.0,
            live.ts
            """.trimIndent(),
            "https://ex.test/live.m3u8",
        ) as HlsDocument.Media
        assertFalse(media.endList)
        assertEquals(1, media.segments.size)
    }

    @Test
    fun protocolRelativeAndQueryOnBase() {
        assertEquals(
            "https://cdn.example/seg.ts?k=1",
            HlsPlaylist.resolveUrl("https://site.test/p/prog.m3u8", "//cdn.example/seg.ts?k=1"),
        )
        assertEquals(
            "https://site.test/p/seg.ts?x=1",
            HlsPlaylist.resolveUrl("https://site.test/p/prog.m3u8?token=z", "seg.ts?x=1"),
        )
    }

    @Test
    fun looksLikeHlsFromUrlHeaderMime() {
        assertTrue(HlsPlaylist.looksLikeHls("https://x/a.m3u8?y=1"))
        assertTrue(HlsPlaylist.looksLikeHls("https://x/noext", mapOf(HlsHeaders.VARIANT to "https://x/v.m3u8")))
        assertTrue(HlsPlaylist.looksLikeHls("https://x/noext", mime = "application/vnd.apple.mpegurl; charset=utf-8"))
        assertFalse(HlsPlaylist.looksLikeHls("https://x/clip.mp4"))
        assertTrue(HlsPlaylist.looksLikeDash("https://x/a.mpd?z=1"))
        assertFalse(HlsPlaylist.looksLikeDash("https://x/a.m3u8"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingExtm3uRejected() {
        HlsPlaylist.parse("#EXTINF:1.0,\nseg.ts\n", "https://ex.test/a.m3u8")
    }

    @Test
    fun quotedCodecsWithCommasStayIntact() {
        val master = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS="avc1.42c01e,mp4a.40.2",RESOLUTION=16x16
            a.m3u8
            """.trimIndent(),
            "https://ex.test/m.m3u8",
        ) as HlsDocument.Master
        assertEquals("avc1.42c01e,mp4a.40.2", master.variants[0].codecs)
    }

    @Test
    fun bestPrefersHigherResolutionWhenBandwidthTies() {
        val master = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360
            a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720
            b.m3u8
            """.trimIndent(),
            "https://ex.test/m.m3u8",
        ) as HlsDocument.Master
        assertEquals("https://ex.test/b.m3u8", master.best().uri)
    }

    @Test
    fun audioWithoutUriIsIgnored() {
        val master = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="muxed",DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="audio"
            v.m3u8
            """.trimIndent(),
            "https://ex.test/m.m3u8",
        ) as HlsDocument.Master
        assertNull(master.audioFor(master.best())?.uri)
    }
}
