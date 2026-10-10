package dev.ahmed.spotter.browser

import dev.ahmed.spotter.engine.Filenames

object MediaNames {
    fun finalize(userTyped: String, item: DetectedItem): String {
        val raw = userTyped.trim().ifBlank { item.suggestedName }
        val sanitized = Filenames.sanitize(raw)
        val mime = when (item.kind) {
            MediaKind.PLAYLIST -> "application/vnd.apple.mpegurl"
            else -> item.type.takeIf { '/' in it }
        }
        return Filenames.ensureExtension(sanitized, mime, item.url)
    }
}
