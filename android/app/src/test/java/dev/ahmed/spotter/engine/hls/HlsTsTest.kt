package dev.ahmed.spotter.engine.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsTsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun findsSyncAfterJunk() {
        val packets = tsPackets(4)
        val junk = "JUNKHDR".toByteArray()
        val buf = junk + packets
        assertEquals(junk.size, HlsTs.findTsStart(buf))
        assertTrue(HlsTs.isTsSync(buf, junk.size))
    }

    @Test
    fun ignoresStray0x47InJunk() {
        val packets = tsPackets(3)
        val junk = byteArrayOf(0x00, 0x47, 0x11, 0x22, 0x33)
        val buf = junk + packets
        assertEquals(junk.size, HlsTs.findTsStart(buf))
    }

    @Test
    fun sniffsFileAndSkipCopy() {
        val packets = tsPackets(5)
        val junk = ByteArray(13) { 0xAA.toByte() }
        val src = tmp.newFile("seg.jpg")
        src.writeBytes(junk + packets)
        val (kind, skip) = HlsTs.sniff(src)
        assertEquals(HlsPayloadKind.MPEG_TS, kind)
        assertEquals(13, skip)
        val dest = tmp.newFile("out.ts")
        HlsTs.copySkipping(src, skip, dest)
        assertTrue(dest.readBytes().contentEquals(packets))
    }

    @Test
    fun sniffsFmp4Ftyp() {
        val box = ByteArray(32)
        box[3] = 24
        "ftyp".toByteArray().copyInto(box, 4)
        val src = tmp.newFile("init.m4s")
        src.writeBytes(byteArrayOf(1, 2, 3) + box)
        val (kind, skip) = HlsTs.sniff(src)
        assertEquals(HlsPayloadKind.FMP4, kind)
        assertEquals(3, skip)
    }

    private fun tsPackets(n: Int): ByteArray {
        val out = ByteArray(n * HlsTs.PACKET)
        for (i in 0 until n) {
            out[i * HlsTs.PACKET] = HlsTs.SYNC
            out[i * HlsTs.PACKET + 1] = i.toByte()
        }
        return out
    }
}
