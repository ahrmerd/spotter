package dev.ahmed.spotter.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePingTest {
    @Test
    fun payloadHasOnlyTheFiveAnonymousFields() {
        val j = Json.parseToJsonElement(UsagePing.payload("abc", "0.2.0", 2, 35, "arm64-v8a")).jsonObject
        assertEquals(setOf("id", "v", "c", "sdk", "abi"), j.keys)
        assertEquals("abc", j.getValue("id").jsonPrimitive.content)
        assertEquals(35, j.getValue("sdk").jsonPrimitive.int)
    }

    @Test
    fun endpointIsHttps() {
        assertTrue(UsagePing.ENDPOINT.startsWith("https://"))
    }
}
