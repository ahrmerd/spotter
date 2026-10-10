package dev.ahmed.spotter.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    @Test
    fun mimeWins() {
        assertEquals(Categories.VIDEO, Categories.of("video/mp4", "a.bin"))
        assertEquals(Categories.AUDIO, Categories.of("audio/mpeg", "a.bin"))
        assertEquals(Categories.IMAGE, Categories.of("image/png", "a.bin"))
        assertEquals(Categories.DOCUMENT, Categories.of("application/pdf", "a.bin"))
    }

    @Test
    fun extensionFallback() {
        assertEquals(Categories.VIDEO, Categories.of(null, "clip.webm"))
        assertEquals(Categories.AUDIO, Categories.of(null, "song.mp3"))
        assertEquals(Categories.IMAGE, Categories.of(null, "pic.jpg"))
        assertEquals(Categories.DOCUMENT, Categories.of(null, "notes.txt"))
        assertEquals(Categories.OTHER, Categories.of(null, "blob.dat"))
    }
}
