package dev.ahmed.spotter.engine.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMemoryTest {
    @Test
    fun buffersStayBounded() {
        assertEquals(64 * 1024, HlsHttp.BUFFER)
        assertEquals(2 * 1024 * 1024, HlsHttp.MAX_PLAYLIST)
        assertEquals(64, HlsHttp.MAX_KEY)
        assertEquals(8, HlsHttp.MAX_ATTEMPTS)
        assertTrue(HlsHttp.BUFFER <= 256 * 1024)
        assertEquals(30_000L, HlsHttp.backoffMs(20))
    }
}
