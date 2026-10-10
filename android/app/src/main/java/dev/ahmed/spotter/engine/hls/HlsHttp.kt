package dev.ahmed.spotter.engine.hls

import dev.ahmed.spotter.engine.http.HttpStatusException
import dev.ahmed.spotter.engine.http.NeedsRefreshException
import dev.ahmed.spotter.engine.net.HeaderUtils
import dev.ahmed.spotter.engine.net.TokenBucket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Semaphore

class HlsHttp(
    private val client: OkHttpClient,
    private val tokenBucket: TokenBucket = TokenBucket(),
    private val connectionLimiter: AtomicReference<Semaphore> = AtomicReference(Semaphore(16)),
) {
    suspend fun fetchText(url: String, captured: Map<String, String>, maxBytes: Int = MAX_PLAYLIST): String {
        val bytes = fetchBounded(url, captured, maxBytes, range = null)
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun fetchKey(url: String, captured: Map<String, String>): ByteArray {
        val bytes = fetchBounded(url, captured, MAX_KEY, range = null)
        if (bytes.size != 16) throw IOException("AES-128 key must be 16 bytes (got ${bytes.size})")
        return bytes
    }

    suspend fun fetchToFile(
        url: String,
        captured: Map<String, String>,
        dest: File,
        range: HlsByteRange? = null,
        decrypt: ((InputStream) -> InputStream)? = null,
    ) {
        dest.parentFile?.mkdirs()
        var last: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val limiter = connectionLimiter.get()
            limiter.acquire()
            try {
                val extra = rangeExtra(range)
                val req = request(url, captured, extra)
                client.newCall(req).execute().use { resp ->
                    throwIfBad(resp.code)
                    if (range != null && resp.code != 206) {
                        throw IOException("Range ignored for byterange request")
                    }
                    val raw = resp.body?.byteStream() ?: throw IOException("empty body")
                    val stream = decrypt?.invoke(raw) ?: raw
                    stream.use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                tokenBucket.take(n)
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
                return
            } catch (e: NeedsRefreshException) {
                throw e
            } catch (e: HttpStatusException) {
                last = IOException("HTTP ${e.code}: ${e.message}")
                delay(backoffMs(attempt + 1))
            } catch (e: IOException) {
                last = e
                client.connectionPool.evictAll()
                dest.delete()
                delay(backoffMs(attempt + 1))
            } finally {
                limiter.release()
            }
        }
        throw last ?: IOException("fetch failed $url")
    }

    private suspend fun fetchBounded(
        url: String,
        captured: Map<String, String>,
        maxBytes: Int,
        range: HlsByteRange?,
    ): ByteArray {
        var last: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val limiter = connectionLimiter.get()
            limiter.acquire()
            try {
                val req = request(url, captured, rangeExtra(range))
                client.newCall(req).execute().use { resp ->
                    throwIfBad(resp.code)
                    val stream = resp.body?.byteStream() ?: throw IOException("empty body")
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(BUFFER)
                    var total = 0
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) throw IOException("response too large (>$maxBytes bytes)")
                        tokenBucket.take(n)
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            } catch (e: NeedsRefreshException) {
                throw e
            } catch (e: HttpStatusException) {
                last = IOException("HTTP ${e.code}: ${e.message}")
                delay(backoffMs(attempt + 1))
            } catch (e: IOException) {
                last = e
                client.connectionPool.evictAll()
                delay(backoffMs(attempt + 1))
            } finally {
                limiter.release()
            }
        }
        throw last ?: IOException("fetch failed $url")
    }

    private fun request(url: String, captured: Map<String, String>, extra: Map<String, String>): Request {
        val headers = HeaderUtils.merge(captured, extra)
        return Request.Builder().url(url).get().apply {
            for ((k, v) in headers) header(k, v)
        }.build()
    }

    private fun rangeExtra(range: HlsByteRange?): Map<String, String> {
        if (range == null) return emptyMap()
        val start = range.offset ?: 0L
        val end = start + range.length - 1
        return mapOf("Range" to "bytes=$start-$end")
    }

    private fun throwIfBad(code: Int) {
        when (code) {
            403, 404, 410 -> throw NeedsRefreshException(code, "HTTP $code")
            200, 206 -> Unit
            else -> throw HttpStatusException(code, "HTTP $code")
        }
    }

    companion object {
        const val BUFFER = 64 * 1024
        const val MAX_PLAYLIST = 2 * 1024 * 1024
        const val MAX_KEY = 64
        const val MAX_ATTEMPTS = 3

        fun backoffMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 8)
            return minOf(8_000L, 250L shl shift)
        }
    }
}
