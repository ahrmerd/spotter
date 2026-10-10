package dev.ahmed.spotter.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineErrorsTest {
    @Test
    fun mapsConnectionAbort() {
        assertEquals(
            "Connection lost, tap to retry",
            EngineErrors.userMessage("Software caused connection abort"),
        )
    }

    @Test
    fun mapsHttpAndSpaceAndDoesNotLeakRawExceptions() {
        assertEquals("Server error (418)", EngineErrors.userMessage("Probe HTTP 418"))
        assertEquals(
            "Access denied. Open the page in the browser and try again.",
            EngineErrors.userMessage("HTTP 403"),
        )
        assertEquals(
            "Not enough free space (need 10 MB, have 1 MB)",
            EngineErrors.userMessage("Not enough free space (need 10 MB, have 1 MB)"),
        )
        assertEquals("Download failed", EngineErrors.userMessage("java.net.SocketException: boom"))
        assertEquals("Live HLS is not supported", EngineErrors.userMessage("Live HLS is not supported (no #EXT-X-ENDLIST)"))
    }
}
