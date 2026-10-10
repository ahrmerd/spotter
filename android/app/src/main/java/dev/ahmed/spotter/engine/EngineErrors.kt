package dev.ahmed.spotter.engine

object EngineErrors {
    fun userMessage(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Download failed"
        val l = text.lowercase()
        if ("connection abort" in l || "connection reset" in l || "unexpected end of stream" in l ||
            "failed to connect" in l || "timeout" in l || "timed out" in l
        ) {
            return "Connection lost, tap to retry"
        }
        return text
    }
}
