package dev.ahmed.spotter.engine.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object HeaderJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(headers: Map<String, String>): String = json.encodeToString(serializer, headers)

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return json.decodeFromString(serializer, raw)
    }
}
