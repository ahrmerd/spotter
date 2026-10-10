package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.Filenames

object HlsNames {
    fun withExt(filename: String, url: String, ext: String): String {
        val raw = filename.ifBlank { Filenames.urlPathName(url) }
        val sanitized = Filenames.sanitize(raw)
        val old = Filenames.extensionOf(sanitized)
        val base = sanitized.dropLast(old.length).ifBlank { "download" }
        return Filenames.sanitize(base + ext)
    }
}
