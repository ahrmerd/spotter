package dev.ahmed.spotter.browser.adblock

/**
 * A small matcher for Adblock Plus / EasyList filters, written for this app (no native code).
 *
 * Supported: `||host^` and path rules, `|` anchors, `*` and `^`, `@@` exceptions, the options
 * `third-party`, `~third-party`, `domain=`, and the request types below. Rules that use options this engine
 * does not understand (redirect, csp, removeparam, ...) are dropped rather than applied wrongly.
 * Cosmetic filters: plain `##selector` (generic and per-domain); extended pseudo-classes are dropped.
 *
 * Speed: rules are indexed by host (for `||host`) or by one token of the pattern, so a request only tries the few
 * rules that share a host suffix or a token with its URL. No regex runs against rules that cannot match.
 */
class FilterEngine {
    class Rule(
        val pattern: String,
        val types: Int,
        val thirdParty: Int, // 0 = any, 1 = only third-party, 2 = only first-party
        val domainsOn: Set<String>?,
        val domainsOff: Set<String>?,
    ) {
        private val regex: Regex by lazy(LazyThreadSafetyMode.PUBLICATION) { toRegex(pattern) }
        fun matches(url: String): Boolean = regex.containsMatchIn(url)
    }

    private val blockByHost = HashMap<String, MutableList<Rule>>()
    private val blockByToken = HashMap<String, MutableList<Rule>>()
    private val blockLoose = ArrayList<Rule>()
    private val allowByHost = HashMap<String, MutableList<Rule>>()
    private val allowByToken = HashMap<String, MutableList<Rule>>()
    private val allowLoose = ArrayList<Rule>()

    private val genericHide = LinkedHashSet<String>()
    private val hideByDomain = HashMap<String, MutableList<String>>()

    var networkRules = 0
        private set
    var cosmeticRules = 0
        private set

    fun load(lines: Sequence<String>) {
        for (raw in lines) addLine(raw.trim())
    }

    fun addLine(line: String) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        val cos = line.indexOf("##")
        if (cos >= 0 && !line.contains("#@#") && !line.contains("#?#") && !line.contains("#$#")) {
            addCosmetic(line.substring(0, cos), line.substring(cos + 2))
            return
        }
        if (line.contains("#@#") || line.contains("#?#") || line.contains("#%#") || line.contains("#$#")) return
        addNetwork(line)
    }

    private fun addCosmetic(domains: String, selector: String) {
        if (selector.isBlank() || selector.startsWith("+js")) return
        if (UNSUPPORTED_CSS.any { it in selector }) return
        if (selector.contains('{') || selector.contains('}') || selector.contains("\\")) return
        cosmeticRules++
        if (domains.isEmpty()) {
            genericHide.add(selector)
        } else {
            val parts = domains.split(',')
            if (parts.any { it.startsWith("~") }) return
            for (d in parts) hideByDomain.getOrPut(d.lowercase()) { ArrayList() }.add(selector)
        }
    }

    private fun addNetwork(lineIn: String) {
        var line = lineIn
        val exception = line.startsWith("@@")
        if (exception) line = line.substring(2)
        var options = ""
        val dollar = line.lastIndexOf('$')
        if (dollar >= 0 && !line.substring(dollar).contains('/')) {
            options = line.substring(dollar + 1)
            line = line.substring(0, dollar)
        }
        if (line.isEmpty() && options.isEmpty()) return
        if (line.length >= 2 && line.startsWith("/") && line.endsWith("/")) return // regex filters: skipped

        var types = 0
        var negTypes = 0
        var third = 0
        var on: MutableSet<String>? = null
        var off: MutableSet<String>? = null
        if (options.isNotEmpty()) {
            for (opt in options.split(',')) {
                val o = opt.trim().lowercase()
                when {
                    o == "third-party" || o == "3p" -> third = 1
                    o == "~third-party" || o == "~3p" || o == "first-party" || o == "1p" -> third = 2
                    o == "match-case" -> {}
                    o == "all" -> types = types or ALL_TYPES or POPUP or DOCUMENT
                    o.startsWith("domain=") -> {
                        for (d in o.substring(7).split('|')) {
                            if (d.isEmpty()) continue
                            if (d.startsWith("~")) (off ?: HashSet<String>().also { off = it }).add(d.substring(1))
                            else (on ?: HashSet<String>().also { on = it }).add(d)
                        }
                    }
                    o.startsWith("~") && TYPE_BITS.containsKey(o.substring(1)) -> negTypes = negTypes or TYPE_BITS.getValue(o.substring(1))
                    TYPE_BITS.containsKey(o) -> types = types or TYPE_BITS.getValue(o)
                    else -> return // unsupported option: drop the whole rule
                }
            }
        }
        val effective = when {
            types != 0 -> types and negTypes.inv()
            else -> (ALL_TYPES and negTypes.inv())
        }
        if (effective == 0) return
        val rule = Rule(line, effective, third, on, off)
        networkRules++
        val host = hostKey(line)
        val token = if (host == null) bestToken(line) else null
        val (byHost, byToken, loose) = if (exception) Triple(allowByHost, allowByToken, allowLoose)
        else Triple(blockByHost, blockByToken, blockLoose)
        when {
            host != null -> byHost.getOrPut(host) { ArrayList(1) }.add(rule)
            token != null -> byToken.getOrPut(token) { ArrayList(1) }.add(rule)
            else -> loose.add(rule)
        }
    }

    /** `||example.com^...` or `||example.com/path`: the host part, else null. */
    private fun hostKey(p: String): String? {
        if (!p.startsWith("||")) return null
        var i = 2
        while (i < p.length && (p[i].isLetterOrDigit() || p[i] == '.' || p[i] == '-')) i++
        if (i == 2) return null
        if (i < p.length && p[i] != '^' && p[i] != '/' && p[i] != ':' && p[i] != '?') return null
        val h = p.substring(2, i).lowercase()
        return if (h.contains('.')) h else null
    }

    private fun bestToken(p: String): String? {
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (p[i].isLetterOrDigit()) {
                val start = i
                while (i < p.length && p[i].isLetterOrDigit()) i++
                val bounded = start > 0 && p[start - 1] != '*' && i < p.length && p[i] != '*'
                val len = i - start
                if (bounded && len >= 3 && (best == null || len > best.length)) best = p.substring(start, i).lowercase()
            } else {
                i++
            }
        }
        return best
    }

    /** Is this request blocked? [type] is one of the type constants, [pageHost] the host of the page that made it. */
    fun shouldBlock(url: String, host: String, type: Int, pageHost: String): Boolean {
        val third = pageHost.isNotEmpty() && !sameSite(host, pageHost)
        val lower = url.lowercase()
        if (!matches(lower, host, type, third, pageHost, blockByHost, blockByToken, blockLoose)) return false
        return !matches(lower, host, type, third, pageHost, allowByHost, allowByToken, allowLoose)
    }

    private fun matches(
        url: String, host: String, type: Int, third: Boolean, pageHost: String,
        byHost: Map<String, List<Rule>>, byToken: Map<String, List<Rule>>, loose: List<Rule>,
    ): Boolean {
        var h = host
        while (true) {
            byHost[h]?.let { if (it.any { r -> applies(r, url, type, third, pageHost) }) return true }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (!h.contains('.')) break
        }
        val seen = HashSet<String>()
        var i = 0
        while (i < url.length) {
            if (url[i].isLetterOrDigit()) {
                val start = i
                while (i < url.length && url[i].isLetterOrDigit()) i++
                if (i - start >= 3) {
                    val tok = url.substring(start, i)
                    if (seen.add(tok)) byToken[tok]?.let { if (it.any { r -> applies(r, url, type, third, pageHost) }) return true }
                }
            } else {
                i++
            }
        }
        return loose.any { applies(it, url, type, third, pageHost) }
    }

    private fun applies(r: Rule, url: String, type: Int, third: Boolean, pageHost: String): Boolean {
        if (r.types and type == 0) return false
        if (r.thirdParty == 1 && !third) return false
        if (r.thirdParty == 2 && third) return false
        r.domainsOn?.let { on -> if (on.none { pageHost == it || pageHost.endsWith(".$it") }) return false }
        r.domainsOff?.let { off -> if (off.any { pageHost == it || pageHost.endsWith(".$it") }) return false }
        return r.matches(url)
    }

    /** CSS that hides ad containers for [host] (generic rules plus rules for that host and its parents). */
    fun cosmeticCss(host: String, includeGeneric: Boolean = true): String {
        val sb = StringBuilder()
        val sels = ArrayList<String>()
        var h = host
        while (true) {
            hideByDomain[h]?.let { sels.addAll(it) }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        if (includeGeneric) sels.addAll(genericHide)
        // One rule per selector: a single bad selector would invalidate a comma-joined group.
        for (s in sels) sb.append(s).append("{display:none!important}")
        return sb.toString()
    }

    companion object {
        const val SCRIPT = 1
        const val IMAGE = 2
        const val STYLESHEET = 4
        const val SUBDOCUMENT = 8
        const val XHR = 16
        const val MEDIA = 32
        const val FONT = 64
        const val PING = 128
        const val OTHER = 256
        const val DOCUMENT = 512
        const val POPUP = 1024
        const val ALL_TYPES = SCRIPT or IMAGE or STYLESHEET or SUBDOCUMENT or XHR or MEDIA or FONT or PING or OTHER

        private val TYPE_BITS = mapOf(
            "script" to SCRIPT, "image" to IMAGE, "stylesheet" to STYLESHEET, "subdocument" to SUBDOCUMENT,
            "xmlhttprequest" to XHR, "xhr" to XHR, "media" to MEDIA, "font" to FONT, "ping" to PING,
            "other" to OTHER, "document" to DOCUMENT, "doc" to DOCUMENT, "popup" to POPUP,
            "object" to OTHER, "websocket" to OTHER,
        )
        private val UNSUPPORTED_CSS = listOf(":-abp-", ":has-text", ":xpath", ":matches-css", ":contains(", ":upward", ":nth-ancestor", ":min-text", ":style(", ":remove(")

        fun sameSite(a: String, b: String): Boolean = registrableDomain(a) == registrableDomain(b)

        private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac")

        /** eTLD+1 approximation: last two labels, or three for names like `example.co.uk`. */
        fun registrableDomain(host: String): String {
            val labels = host.lowercase().trim('.').split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val tld = labels.last()
            val second = labels[labels.size - 2]
            return if (tld.length == 2 && second in SECOND_LEVEL) labels.takeLast(3).joinToString(".")
            else labels.takeLast(2).joinToString(".")
        }

        /** Converts an ABP pattern to a regex: `*` any, `^` separator, `|` anchors, `||` host anchor. */
        fun toRegex(pattern: String): Regex {
            var p = pattern
            val sb = StringBuilder()
            when {
                p.startsWith("||") -> { sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?"); p = p.substring(2) }
                p.startsWith("|") -> { sb.append("^"); p = p.substring(1) }
            }
            val endAnchor = p.endsWith("|")
            if (endAnchor) p = p.dropLast(1)
            for (c in p) {
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            if (endAnchor) sb.append("$")
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
