package dev.ahmed.spotter.engine.sink

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MediaStoreSinkTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val created = mutableListOf<String>()

    @Before
    fun requireApi() {
        Assume.assumeTrue("MediaStore Downloads requires API 29+", Build.VERSION.SDK_INT >= 29)
    }

    @After
    fun cleanup() {
        created.forEach { MediaStoreSink.deleteUri(resolver, it) }
        created.clear()
    }

    @Test
    fun reopenStoredUriContinuesWriting() {
        val name = "spotter-m2-resume-${System.nanoTime()}.bin"
        val first = Random(1).nextBytes(64 * 1024)
        val second = Random(2).nextBytes(64 * 1024)
        val total = first.size + second.size

        val sink1 = MediaStoreSink.create(resolver, name, "application/octet-stream")
        created += sink1.outputPath
        sink1.setLength(total.toLong())
        sink1.writeAt(0, first, 0, first.size)
        sink1.force()
        val uri = sink1.outputPath
        sink1.close()

        val sink2 = MediaStoreSink.open(resolver, name, uri, "application/octet-stream")
        assertEquals("must reopen the same row, not insert a new one", uri, sink2.outputPath)
        assertTrue("pre-allocated length must survive close", sink2.length() >= total)
        sink2.writeAt(first.size.toLong(), second, 0, second.size)
        sink2.complete()

        val read = resolver.openInputStream(android.net.Uri.parse(uri))!!.use { it.readBytes() }
        assertEquals(total, read.size)
        assertArrayEquals(first + second, read)
        assertEquals(sha256(first + second), sha256(read))
    }

    @Test
    fun missingUriCreatesNewRow() {
        val name = "spotter-m2-missing-${System.nanoTime()}.bin"
        val sink1 = MediaStoreSink.create(resolver, name, "application/octet-stream")
        created += sink1.outputPath
        val old = sink1.outputPath
        sink1.close()
        MediaStoreSink.deleteUri(resolver, old)
        created.remove(old)

        val sink2 = MediaStoreSink.open(resolver, name, old, "application/octet-stream")
        created += sink2.outputPath
        assertNotEquals(old, sink2.outputPath)
        assertTrue(sink2.outputPath.startsWith("content:"))
        sink2.close()
    }

    @Test
    fun openExistingReturnsNullWhenGone() {
        val name = "spotter-m2-gone-${System.nanoTime()}.bin"
        val sink = MediaStoreSink.create(resolver, name, "application/octet-stream")
        val uri = android.net.Uri.parse(sink.outputPath)
        sink.close()
        resolver.delete(uri, null, null)
        assertNull(MediaStoreSink.openExisting(resolver, uri))
    }

    @Test
    fun uniqueDisplayNameAvoidsCollision() {
        val base = "spotter-m2-unique-${System.nanoTime()}.bin"
        val a = MediaStoreSink.create(resolver, base, "application/octet-stream")
        created += a.outputPath
        a.close()
        val b = MediaStoreSink.create(resolver, base, "application/octet-stream")
        created += b.outputPath
        b.close()
        val names = listOf(a, b).map { displayName(it.outputPath) }
        assertEquals(2, names.toSet().size)
    }

    @Test
    fun reopenAfterTruncateReportsShortLength() {
        val name = "spotter-m2-trunc-${System.nanoTime()}.bin"
        val sink1 = MediaStoreSink.create(resolver, name, "application/octet-stream")
        created += sink1.outputPath
        sink1.setLength(200_000)
        sink1.writeAt(0, ByteArray(1000) { 1 }, 0, 1000)
        sink1.force()
        val uri = sink1.uri
        sink1.close()

        resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel.truncate(500)
        }

        val sink2 = MediaStoreSink.openExisting(resolver, uri)
        assertNotNull(sink2)
        try {
            assertEquals(500L, sink2!!.length())
        } finally {
            sink2?.close()
        }
    }

    private fun displayName(contentUri: String): String {
        resolver.query(
            android.net.Uri.parse(contentUri),
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        error("no display name for $contentUri")
    }

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
