package dev.ahmed.spotter.engine.hls

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Remux a TS whose keyframes exceed 64 KiB (the old buffer). Needs the `big.ts` asset
 * produced by `tools/make_hls_fixtures.sh` or a one-off ffmpeg 720p clip.
 */
@RunWith(AndroidJUnit4::class)
class MediaMuxerRemuxerTest {
    @Test
    fun remuxesTsWithLargeKeyframesToMp4() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "hls-remux").apply { mkdirs() }
        val ts = File(dir, "big.ts")
        val asset = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("hls/big.ts")
        } catch (e: java.io.FileNotFoundException) {
            null
        }
        Assume.assumeTrue("androidTest assets/hls/big.ts missing", asset != null)
        asset!!.use { input -> ts.outputStream().use { input.copyTo(it) } }
        assertTrue("fixture must be larger than the old 64KiB sample buffer", ts.length() > 64 * 1024)
        val dest = File(dir, "out.mp4")
        val result = MediaMuxerRemuxer().remux(ts, null, dest)
        assertEquals(HlsHeaders.MIME_MP4, result.mime)
        assertTrue(dest.exists() && dest.length() > 0)
        assertEquals(null, result.note)
    }
}
