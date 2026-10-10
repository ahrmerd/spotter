package dev.ahmed.spotter.engine

/** Strip cookies, tokens and Authorization from text that might be copied or logged. */
object ErrorRedact {
    private val PATTERNS = listOf(
        Regex("(?i)(cookie|set-cookie)\\s*[:=]\\s*\\S+"),
        Regex("(?i)(authorization|proxy-authorization)\\s*[:=]\\s*\\S+(?:\\s+\\S+)?"),
        Regex("(?i)([?&](?:token|access_token|auth|key|sig|signature|expires|cookie|jwt|session|password)=)[^&\\s]+"),
        Regex("(?i)\\b(token|access_token|api_key|password)\\s*[:=]\\s*\\S+"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-+/=]+"),
        Regex("(?i)\\b(key|iv)\\s*[:=]\\s*[0-9a-f]{16,}"),
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (p in PATTERNS) {
            out = p.replace(out) { m ->
                val label = m.groupValues.getOrNull(1).orEmpty()
                if (p.pattern.startsWith("(?i)bearer") || label.isEmpty()) {
                    "***"
                } else if (label.startsWith("?") || label.startsWith("&")) {
                    "${label}***"
                } else {
                    "$label=***"
                }
            }
        }
        return out
    }

    fun copyText(filename: String, url: String, state: String, error: String?): String = buildString {
        appendLine("filename: ${redact(filename)}")
        appendLine("url: ${redact(url)}")
        appendLine("state: $state")
        appendLine("error: ${redact(error.orEmpty()).ifBlank { "(none)" }}")
    }
}
