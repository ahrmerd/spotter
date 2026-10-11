package dev.ahmed.spotter.engine.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxBuffersTest {
    @Test
    fun startsAtLeast256KiBAndCoversA215KiBKeyframe() {
        assertEquals(256 * 1024, MuxBuffers.sizeFor(0))
        assertTrue(MuxBuffers.sizeFor(215 * 1024) > 215 * 1024)
        assertEquals(MuxBuffers.CEILING, MuxBuffers.sizeFor(Int.MAX_VALUE))
        assertEquals(512 * 1024, MuxBuffers.grow(256 * 1024, 300 * 1024))
    }
}
