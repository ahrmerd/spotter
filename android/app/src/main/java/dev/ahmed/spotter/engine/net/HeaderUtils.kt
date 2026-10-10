package dev.ahmed.spotter.engine.net

object HeaderUtils {
    private val SKIP = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
        "range",
        "if-range",
        "expect",
        "upgrade",
        "proxy-connection",
        "keep-alive",
        "te",
        "trailer",
        "accept-encoding",
    )

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun merge(captured: Map<String, String>, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var hasUa = false
        for ((k, v) in captured) {
            val lk = k.lowercase()
            if (lk in SKIP || lk.startsWith("x-spotter-")) continue
            out[k] = v
            if (lk == "user-agent") hasUa = true
        }
        for ((k, v) in extra) {
            out[k] = v
            if (k.equals("user-agent", ignoreCase = true)) hasUa = true
        }
        if (!hasUa) out["User-Agent"] = DEFAULT_USER_AGENT
        out["Accept-Encoding"] = "identity"
        if (out.keys.none { it.equals("Accept", ignoreCase = true) }) {
            out["Accept"] = "*/*"
        }
        return out
    }

    fun redact(name: String): Boolean {
        val n = name.lowercase()
        return n == "cookie" || n == "set-cookie" || n == "authorization" || n == "proxy-authorization"
    }
}
