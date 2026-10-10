package dev.ahmed.spotter.engine.hls

import java.io.File
import java.io.RandomAccessFile

enum class HlsPayloadKind { MPEG_TS, FMP4, UNKNOWN }

object HlsTs {
    const val PACKET = 188
    const val SYNC: Byte = 0x47
    const val SNIFF_BYTES = 64 * 1024

    fun sniff(file: File, mapPresent: Boolean = false): Pair<HlsPayloadKind, Int> {
        val buf = ByteArray(minOf(SNIFF_BYTES, file.length().toInt().coerceAtLeast(0)))
        if (buf.isEmpty()) return HlsPayloadKind.UNKNOWN to 0
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        val tsAt = findTsStart(buf)
        if (tsAt >= 0) return HlsPayloadKind.MPEG_TS to tsAt
        val mp4At = findMp4Start(buf)
        if (mp4At >= 0) return HlsPayloadKind.FMP4 to mp4At
        if (mapPresent) return HlsPayloadKind.FMP4 to 0
        return HlsPayloadKind.UNKNOWN to 0
    }

    fun findTsStart(buf: ByteArray): Int {
        val limit = (buf.size - PACKET).coerceAtLeast(0)
        for (i in 0..limit) {
            if (isTsSync(buf, i)) return i
        }
        return -1
    }

    fun isTsSync(buf: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset >= buf.size) return false
        if (buf[offset] != SYNC) return false
        var hits = 0
        var i = offset
        while (i < buf.size) {
            if (buf[i] != SYNC) return false
            hits++
            i += PACKET
            if (hits >= 3) return true
        }
        return hits >= 2
    }

    fun findMp4Start(buf: ByteArray): Int {
        val limit = buf.size - 8
        var i = 0
        while (i <= limit) {
            if (looksLikeBox(buf, i)) return i
            i++
        }
        return -1
    }

    private fun looksLikeBox(buf: ByteArray, offset: Int): Boolean {
        if (offset + 8 > buf.size) return false
        val type = String(buf, offset + 4, 4, Charsets.US_ASCII)
        return type == "ftyp" || type == "styp" || type == "moof" || type == "sidx" || type == "moov"
    }

    fun copySkipping(src: File, skip: Int, dest: File) {
        src.inputStream().use { input ->
            var left = skip.toLong()
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n
            }
            dest.outputStream().use { output ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }
}
