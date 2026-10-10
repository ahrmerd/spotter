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
    fun keepsUnknown() {
        assertEquals("Probe HTTP 418", EngineErrors.userMessage("Probe HTTP 418"))
    }
}
