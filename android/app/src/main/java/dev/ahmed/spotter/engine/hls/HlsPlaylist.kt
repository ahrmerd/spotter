package dev.ahmed.spotter.engine.hls

import java.net.URI

object HlsHeaders {
    const val VARIANT = "X-Spotter-Variant"
    const val AUDIO = "X-Spotter-Audio"
    const val MIME_PLAYLIST = "application/vnd.apple.mpegurl"
    const val MIME_PLAYLIST_ALT = "application/x-mpegurl"
    const val MIME_MP4 = "video/mp4"
    const val MIME_TS = "video/mp2t"
}

enum class HlsKeyMethod { NONE, AES_128, SAMPLE_AES, DRM }

data class HlsByteRange(val length: Long, val offset: Long?)

data class HlsKey(
    val method: HlsKeyMethod,
    val uri: String?,
    val iv: ByteArray?,
    val keyFormat: String?,
)

data class HlsMap(
    val uri: String,
    val byteRange: HlsByteRange?,
)

data class HlsSegment(
    val uri: String,
    val duration: Double,
    val sequence: Long,
    val key: HlsKey?,
    val byteRange: HlsByteRange?,
)

data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val audioGroupId: String?,
)

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String,
    val default: Boolean,
    val uri: String?,
    val language: String?,
)

sealed class HlsDocument {
    data class Master(
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition>,
    ) : HlsDocument() {
        fun best(): HlsVariant {
            require(variants.isNotEmpty()) { "master playlist has no variants" }
            return variants.maxWith(compareBy<HlsVariant> { it.bandwidth }.thenBy { it.height ?: 0 })
        }

        fun bestIndex(): Int = variants.indexOf(best())

        fun audioFor(variant: HlsVariant): HlsRendition? {
            val group = variant.audioGroupId ?: return null
            val inGroup = renditions.filter { it.type.equals("AUDIO", ignoreCase = true) && it.groupId == group }
            return inGroup.firstOrNull { it.default && !it.uri.isNullOrBlank() }
                ?: inGroup.firstOrNull { !it.uri.isNullOrBlank() }
        }
    }

    data class Media(
        val targetDuration: Double?,
        val mediaSequence: Long,
        val endList: Boolean,
        val playlistType: String?,
        val map: HlsMap?,
        val segments: List<HlsSegment>,
        val version: Int,
    ) : HlsDocument()
}

object HlsPlaylist {
    fun looksLikeHls(url: String, headers: Map<String, String> = emptyMap(), mime: String? = null): Boolean {
        if (headers.keys.any { it.equals(HlsHeaders.VARIANT, true) || it.equals(HlsHeaders.AUDIO, true) }) {
            return true
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (path.endsWith(".m3u8")) return true
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        return m == HlsHeaders.MIME_PLAYLIST || m == HlsHeaders.MIME_PLAYLIST_ALT
    }

    fun looksLikeDash(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".mpd")
    }

    fun parse(text: String, baseUrl: String): HlsDocument {
        val lines = normalize(text)
        if (lines.none { it.equals("#EXTM3U", ignoreCase = true) }) {
            throw IllegalArgumentException("Not an HLS playlist (missing #EXTM3U)")
        }
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        return if (isMaster) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
    }

    fun resolveUrl(base: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return base
        if (trimmed.contains("://")) return trimmed
        val baseUri = URI(base)
        if (trimmed.startsWith("//")) {
            val scheme = baseUri.scheme ?: "https"
            return URI("$scheme:$trimmed").toString()
        }
        return baseUri.resolve(trimmed).toString()
    }

    fun qualityLabel(variant: HlsVariant, best: Boolean): String {
        val res = when {
            variant.width != null && variant.height != null -> "${variant.width}x${variant.height}"
            variant.height != null -> "${variant.height}p"
            else -> "unknown"
        }
        val br = formatBitrate(variant.bandwidth)
        val body = "$res · $br"
        return if (best) "Best ($body)" else body
    }

    fun formatBitrate(bps: Long): String {
        if (bps <= 0) return "0 kbps"
        val kbps = bps / 1000.0
        return if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000.0) else "${kbps.toInt()} kbps"
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsDocument.Master {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    renditions += HlsRendition(
                        type = a["TYPE"].orEmpty(),
                        groupId = a["GROUP-ID"].orEmpty(),
                        name = a["NAME"].orEmpty(),
                        default = a["DEFAULT"].equals("YES", ignoreCase = true),
                        uri = a["URI"]?.let { resolveUrl(baseUrl, it) },
                        language = a["LANGUAGE"],
                    )
                    i++
                }
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    i++
                    while (i < lines.size && (lines[i].startsWith("#") || lines[i].isBlank())) {
                        if (lines[i].startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) break
                        i++
                    }
                    if (i >= lines.size || lines[i].startsWith("#")) {
                        throw IllegalArgumentException("STREAM-INF without URI")
                    }
                    val uri = resolveUrl(baseUrl, lines[i])
                    val res = a["RESOLUTION"]?.split('x')
                    val bw = a["BANDWIDTH"]?.toLongOrNull()
                        ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                        ?: 0L
                    variants += HlsVariant(
                        uri = uri,
                        bandwidth = bw,
                        width = res?.getOrNull(0)?.toIntOrNull(),
                        height = res?.getOrNull(1)?.toIntOrNull(),
                        codecs = a["CODECS"],
                        audioGroupId = a["AUDIO"],
                    )
                    i++
                }
                else -> i++
            }
        }
        if (variants.isEmpty()) throw IllegalArgumentException("master playlist has no variants")
        return HlsDocument.Master(variants, renditions)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsDocument.Media {
        var targetDuration: Double? = null
        var mediaSequence = 0L
        var endList = false
        var playlistType: String? = null
        var version = 1
        var map: HlsMap? = null
        var currentKey: HlsKey? = null
        var pendingRange: HlsByteRange? = null
        var nextOffset = 0L
        val segments = mutableListOf<HlsSegment>()
        var duration = 0.0
        var seq = 0L
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-VERSION:", ignoreCase = true) -> {
                    version = line.substringAfter(':').trim().toIntOrNull() ?: 1
                    i++
                }
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull()
                    i++
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    seq = mediaSequence
                    i++
                }
                line.startsWith("#EXT-X-PLAYLIST-TYPE:", ignoreCase = true) -> {
                    playlistType = line.substringAfter(':').trim()
                    i++
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                    i++
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    currentKey = parseKey(parseAttrs(line.substringAfter(':')), baseUrl)
                    i++
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val a = parseAttrs(line.substringAfter(':'))
                    val uri = a["URI"] ?: throw IllegalArgumentException("EXT-X-MAP missing URI")
                    map = HlsMap(resolveUrl(baseUrl, uri), parseByteRangeAttr(a["BYTERANGE"]))
                    i++
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    pendingRange = parseByteRangeAttr(line.substringAfter(':').trim())
                    i++
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val payload = line.substringAfter(':')
                    duration = payload.substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    i++
                    while (i < lines.size && lines[i].startsWith("#") &&
                        !lines[i].startsWith("#EXTINF", ignoreCase = true) &&
                        !lines[i].startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
                    ) {
                        val tag = lines[i]
                        when {
                            tag.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                                pendingRange = parseByteRangeAttr(tag.substringAfter(':').trim())
                            }
                            tag.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                                currentKey = parseKey(parseAttrs(tag.substringAfter(':')), baseUrl)
                            }
                            tag.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                                val a = parseAttrs(tag.substringAfter(':'))
                                val uri = a["URI"] ?: throw IllegalArgumentException("EXT-X-MAP missing URI")
                                map = HlsMap(resolveUrl(baseUrl, uri), parseByteRangeAttr(a["BYTERANGE"]))
                            }
                        }
                        i++
                    }
                    if (i >= lines.size || lines[i].startsWith("#")) {
                        throw IllegalArgumentException("EXTINF without URI")
                    }
                    val uri = resolveUrl(baseUrl, lines[i])
                    val range = pendingRange
                    if (range != null && range.offset == null) {
                        pendingRange = HlsByteRange(range.length, nextOffset)
                    }
                    val applied = pendingRange
                    if (applied != null) {
                        nextOffset = (applied.offset ?: 0L) + applied.length
                    }
                    segments += HlsSegment(
                        uri = uri,
                        duration = duration,
                        sequence = seq,
                        key = currentKey,
                        byteRange = applied,
                    )
                    seq++
                    pendingRange = null
                    i++
                }
                else -> i++
            }
        }
        return HlsDocument.Media(
            targetDuration = targetDuration,
            mediaSequence = mediaSequence,
            endList = endList,
            playlistType = playlistType,
            map = map,
            segments = segments,
            version = version,
        )
    }

    private fun parseKey(attrs: Map<String, String>, baseUrl: String): HlsKey {
        val methodRaw = attrs["METHOD"]?.uppercase().orEmpty()
        val keyFormat = attrs["KEYFORMAT"]
        val method = when {
            methodRaw == "NONE" -> HlsKeyMethod.NONE
            methodRaw == "AES-128" && (keyFormat.isNullOrBlank() || keyFormat.equals("identity", true)) ->
                HlsKeyMethod.AES_128
            methodRaw.startsWith("SAMPLE-AES") -> HlsKeyMethod.SAMPLE_AES
            !keyFormat.isNullOrBlank() && !keyFormat.equals("identity", true) -> HlsKeyMethod.DRM
            methodRaw == "AES-128" -> HlsKeyMethod.AES_128
            methodRaw.isEmpty() || methodRaw == "NONE" -> HlsKeyMethod.NONE
            else -> HlsKeyMethod.DRM
        }
        val uri = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
        val iv = attrs["IV"]?.let { parseIv(it) }
        return HlsKey(method, uri, iv, keyFormat)
    }

    fun parseIv(raw: String): ByteArray {
        val hex = raw.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 32 || hex.any { it !in "0123456789abcdefABCDEF" }) {
            throw IllegalArgumentException("IV must be 128-bit hex")
        }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var n = sequence
        for (i in 15 downTo 8) {
            iv[i] = (n and 0xffL).toByte()
            n = n ushr 8
        }
        return iv
    }

    fun parseByteRangeAttr(raw: String?): HlsByteRange? {
        if (raw.isNullOrBlank()) return null
        val spec = raw.trim()
        val length = spec.substringBefore('@').trim().toLongOrNull()
            ?: throw IllegalArgumentException("bad BYTERANGE $raw")
        val offset = if ('@' in spec) spec.substringAfter('@').trim().toLongOrNull() else null
        return HlsByteRange(length, offset)
    }

    internal fun parseAttrs(line: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        val s = line
        while (i < s.length) {
            while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++
            if (i >= s.length) break
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            i = eq + 1
            if (i < s.length && s[i] == '"') {
                i++
                val end = s.indexOf('"', i)
                val value = if (end < 0) s.substring(i) else s.substring(i, end)
                out[key] = value
                i = if (end < 0) s.length else end + 1
            } else {
                val comma = s.indexOf(',', i)
                val value = if (comma < 0) s.substring(i) else s.substring(i, comma)
                out[key] = value.trim()
                i = if (comma < 0) s.length else comma
            }
        }
        return out
    }

    private fun normalize(text: String): List<String> {
        var s = text
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s.split('\n').map { it.trimEnd('\r').trim() }.filter { it.isNotEmpty() }
    }
}
