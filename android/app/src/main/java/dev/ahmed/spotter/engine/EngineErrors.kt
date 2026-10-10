package dev.ahmed.spotter.engine

object EngineErrors {
    private val HTTP_CODE = Regex("""(?i)https?\s*(\d{3})""")
    private val EXCEPTION_NAME = Regex("""^[A-Za-z0-9_$.]+(?:Exception|Error)(?::\s*)?""")

    fun userMessage(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Download failed"
        val l = text.lowercase()
        when {
            "not enough free space" in l || "enospc" in l || "no space left" in l ->
                return if ("need" in l && "have" in l) text else "Not enough free space"
            "connection abort" in l || "connection reset" in l || "unexpected end of stream" in l ||
                "failed to connect" in l || "timeout" in l || "timed out" in l || "broken pipe" in l ||
                "network is unreachable" in l ->
                return "Connection lost, tap to retry"
            "http 401" in l || "http 403" in l ->
                return "Access denied. Open the page in the browser and try again."
            "http 404" in l || "http 410" in l ->
                return "The file is gone"
            "live hls" in l || "ext-x-endlist" in l ->
                return "Live HLS is not supported"
            "sample-aes" in l ->
                return "SAMPLE-AES encryption is not supported"
            "drm-protected" in l || l == "drm" || "drm-protected hls" in l ->
                return "DRM-protected HLS is not supported"
            "size mismatch" in l ->
                return "Download incomplete, tap to retry"
            "unknownhost" in l || "unable to resolve" in l || "no address associated" in l ->
                return "Could not reach the server"
            "sslhandshake" in l || "certpath" in l || "trust anchor" in l || "ssl protocol" in l ->
                return "Secure connection failed"
            "range ignored" in l || "entity changed" in l || "partial file missing" in l ->
                return "The file changed on the server, tap to retry"
            "dash is not supported" in l ->
                return "DASH is not supported yet"
        }
        HTTP_CODE.find(text)?.groupValues?.getOrNull(1)?.let { code ->
            return "Server error ($code)"
        }
        if (EXCEPTION_NAME.containsMatchIn(text) || text.contains('\n') || text.contains('\t')) {
            return "Download failed"
        }
        return text.take(160)
    }
}
