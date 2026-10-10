package dev.ahmed.spotter.engine.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object HeaderJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** Headers that must never be written to disk (and therefore never reach backups). */
    private val SECRET = setOf("cookie", "authorization", "proxy-authorization", "set-cookie")

    fun persistable(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() !in SECRET }

    fun encode(headers: Map<String, String>): String = json.encodeToString(serializer, persistable(headers))

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return json.decodeFromString(serializer, raw)
    }
}
