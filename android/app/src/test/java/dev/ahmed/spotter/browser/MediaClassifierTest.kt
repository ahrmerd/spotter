package dev.ahmed.spotter.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    @Test
    fun mp4ByExtension() {
        assertEquals(MediaKind.MEDIA, MediaClassifier.kind("https://cdn.example/v.mp4?token=1"))
        assertTrue(MediaClassifier.looksLikeMediaRequest("https://cdn.example/v.mp4"))
    }

    @Test
    fun m3u8IsPlaylistNotSized() {
        assertEquals(MediaKind.PLAYLIST, MediaClassifier.kind("https://cdn.example/master.m3u8"))
        assertEquals(
            MediaKind.PLAYLIST,
            MediaClassifier.kind("https://cdn.example/x", "application/vnd.apple.mpegurl", 412),
        )
        assertNull(MediaClassifier.displaySize(MediaKind.PLAYLIST, 412))
    }

    @Test
    fun skipsTsAndM4sSegments() {
        assertEquals(MediaKind.SEGMENT, MediaClassifier.kind("https://cdn.example/seg.ts"))
        assertEquals(MediaKind.SEGMENT, MediaClassifier.kind("https://cdn.example/a.m4s"))
        assertFalse(MediaClassifier.looksLikeMediaRequest("https://cdn.example/seg.ts"))
    }

    @Test
    fun skipsTinyNonPlaylistBodies() {
        assertEquals(MediaKind.IGNORE, MediaClassifier.kind("https://cdn.example/v.mp4", "video/mp4", 12_000))
        assertEquals(MediaKind.PLAYLIST, MediaClassifier.kind("https://cdn.example/a.m3u8", null, 200))
    }

    @Test
    fun youtubeWatchIsResolverPage() {
        assertEquals(
            MediaKind.PAGE_RESOLVER,
            MediaClassifier.kind("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun googlevideoChunkIsIgnoredWithoutMediaExt() {
        assertEquals(
            MediaKind.IGNORE,
            MediaClassifier.kind("https://rr1.googlevideo.com/videoplayback?id=1"),
        )
    }
}
