package dev.ahmed.spotter.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameTest {
    @Test
    fun stripsPathAndNulAndLeadingDots() {
        val traversed = Filenames.sanitize("../../x")
        assertFalse(traversed.contains("/"))
        assertFalse(traversed.startsWith("."))
        assertTrue(traversed.contains("x"))
        assertEquals("ab", Filenames.sanitize("a\u0000b"))
        assertEquals("hidden", Filenames.sanitize("...hidden"))
        assertEquals("download", Filenames.sanitize("///"))
        assertEquals("download", Filenames.sanitize("."))
    }

    @Test
    fun capsLengthAndKeepsExtension() {
        val long = "a".repeat(400) + ".mp4"
        val out = Filenames.sanitize(long)
        assertTrue(out.length <= Filenames.MAX_LENGTH)
        assertTrue(out.endsWith(".mp4"))
    }

    @Test
    fun keepsEmoji() {
        assertEquals("video 😀.mp4", Filenames.sanitize("video 😀.mp4"))
    }

    @Test
    fun uniqueAddsNumericSuffix() {
        val taken = setOf("clip.mp4", "clip.mp4.part", "clip (1).mp4")
        assertEquals("clip (2).mp4", Filenames.unique("clip.mp4") { it in taken })
    }

    @Test
    fun addsExtensionFromMime() {
        assertEquals("movie.mp4", Filenames.ensureExtension("movie", "video/mp4", "http://x/a"))
    }

    @Test
    fun contentDispositionFilenameStar() {
        val name = Filenames.fromContentDisposition("attachment; filename*=UTF-8''cute%20cat.mp4")
        assertEquals("cute cat.mp4", name)
        assertFalse(name.isNullOrBlank())
    }
}
