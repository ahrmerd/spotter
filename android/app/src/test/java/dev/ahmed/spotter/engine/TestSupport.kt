package dev.ahmed.spotter.engine

import dev.ahmed.spotter.engine.model.DownloadRecord
import dev.ahmed.spotter.engine.model.DownloadState
import dev.ahmed.spotter.engine.store.DownloadStore
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

fun randomBytes(size: Int, seed: Int): ByteArray {
    val bytes = ByteArray(size)
    Random(seed).nextBytes(bytes)
    return bytes
}

fun sha256(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data)
    return md.digest().toHex()
}

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }

suspend fun DownloadStore.waitFor(
    id: Long,
    timeoutMs: Long = 90_000,
    predicate: (DownloadRecord) -> Boolean,
): DownloadRecord {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: DownloadRecord? = null
    while (System.currentTimeMillis() < deadline) {
        last = get(id)
        if (last != null && predicate(last)) return last
        delay(20)
    }
    error("timeout waiting for download $id; last=$last")
}

suspend fun DownloadStore.waitTerminal(id: Long, timeoutMs: Long = 90_000): DownloadRecord =
    waitFor(id, timeoutMs) {
        it.state == DownloadState.DONE ||
            it.state == DownloadState.ERROR ||
            it.state == DownloadState.NEEDS_REFRESH
    }
