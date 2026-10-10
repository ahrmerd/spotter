package dev.ahmed.spotter.engine

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class RecordedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
)

/**
 * Local HTTP/1.1 server with real Range / 206 / If-Range / ETag behaviour.
 * Implemented with [ServerSocket] so it compiles on the Android unit-test classpath
 * (jdk.httpserver is not part of android.jar).
 */
class RangeTestServer : AutoCloseable {
    var body: ByteArray = ByteArray(0)
    var etag: String? = "\"m1-etag\""
    var lastModified: String? = "Wed, 01 Jan 2020 00:00:00 GMT"
    var rangeSupport: Boolean = true
    var contentType: String = "application/octet-stream"
    var contentDisposition: String? = "attachment; filename=\"source.bin\""
    var throttleBytesPerSec: Int = 0
    var disconnectAfterBytes: Int = 0
    var disconnectEveryNthRequest: Int = 0
    var randomDisconnect: Random? = null
    var randomDisconnectProbability: Double = 0.0
    var failAfterRequests: Int = Int.MAX_VALUE
    var failStatus: Int = 403
    var ifRangeHonored: Boolean = true
    /** When true, responses use Connection: keep-alive and the socket stays open. */
    var keepAlive: Boolean = false

    private val bytes = AtomicLong(0)
    private val requests = AtomicInteger(0)
    val recorded = CopyOnWriteArrayList<RecordedHttpRequest>()

    val bytesServed: Long get() = bytes.get()
    val requestCount: Int get() = requests.get()

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val liveSockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var running = true

    val port: Int get() = serverSocket.localPort
    fun url(path: String = "/file.bin"): String = "http://127.0.0.1:$port$path"

    init {
        executor.execute {
            while (running) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handleSocket(socket) }
                } catch (_: SocketException) {
                    if (!running) return@execute
                } catch (_: Exception) {
                    if (!running) return@execute
                }
            }
        }
    }

    fun resetCounters() {
        bytes.set(0)
        requests.set(0)
        recorded.clear()
    }

    /** Close idle keep-alive sockets the way a NAT or server idle-timeout would. */
    fun dropKeepAlives() {
        for (s in liveSockets.toList()) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        liveSockets.clear()
    }

    private fun handleSocket(socket: Socket) {
        socket.tcpNoDelay = true
        liveSockets.add(socket)
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            do {
                handle(input, output)
            } while (keepAlive && running && !socket.isClosed)
        } catch (_: Exception) {
            // client closed or we forced a disconnect
        } finally {
            liveSockets.remove(socket)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(input: InputStream, output: OutputStream) {
        val parsed = readRequest(input) ?: return
        val n = requests.incrementAndGet()
        recorded.add(parsed)

        if (n > failAfterRequests) {
            writeHeaders(output, failStatus, phrase(failStatus), emptyMap(), 0)
            return
        }

        val data = body
        val rangeHeader = parsed.headers["range"]
        val ifRange = parsed.headers["if-range"]
        val ifRangeOk = when {
            ifRange == null -> true
            !ifRangeHonored -> true
            etag != null && ifRange == etag -> true
            lastModified != null && ifRange == lastModified -> true
            else -> false
        }

        var start = 0
        var end = (data.size - 1).coerceAtLeast(-1)
        var status = 200
        if (rangeSupport && rangeHeader != null && ifRangeOk && data.isNotEmpty()) {
            val range = parseRange(rangeHeader, data.size)
            if (range == null) {
                writeHeaders(
                    output,
                    416,
                    "Range Not Satisfiable",
                    mapOf("Content-Range" to "bytes */${data.size}"),
                    0,
                )
                return
            }
            start = range.first
            end = range.second
            status = 206
        }

        val extra = mutableMapOf(
            "Accept-Ranges" to if (rangeSupport) "bytes" else "none",
            "Content-Type" to contentType,
        )
        etag?.let { extra["ETag"] = it }
        lastModified?.let { extra["Last-Modified"] = it }
        contentDisposition?.let { extra["Content-Disposition"] = it }
        if (status == 206) extra["Content-Range"] = "bytes $start-$end/${data.size}"
        val length = if (end >= start) end - start + 1 else 0
        writeHeaders(output, status, if (status == 206) "Partial Content" else "OK", extra, length)
        if (length == 0) return

        var sent = 0
        var pos = start
        val buf = ByteArray(8192)
        val disconnectAt = disconnectAt(n, length)
        while (pos <= end) {
            if (disconnectAt in 1..sent) {
                output.flush()
                return
            }
            var nwrite = minOf(buf.size, end - pos + 1)
            if (disconnectAt > sent) nwrite = minOf(nwrite, disconnectAt - sent)
            if (throttleBytesPerSec > 0) {
                nwrite = minOf(nwrite, 4 * 1024)
                val sleepMs = (nwrite * 1000L) / throttleBytesPerSec
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
            System.arraycopy(data, pos, buf, 0, nwrite)
            output.write(buf, 0, nwrite)
            pos += nwrite
            sent += nwrite
            bytes.addAndGet(nwrite.toLong())
        }
        output.flush()
    }

    private fun readRequest(input: InputStream): RecordedHttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0) {
                headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
            }
        }
        return RecordedHttpRequest(parts[0], parts[1].substringBefore('?'), headers)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        contentLength: Int,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun phrase(status: Int) = when (status) {
        403 -> "Forbidden"
        404 -> "Not Found"
        410 -> "Gone"
        else -> "Error"
    }

    private fun disconnectAt(requestNumber: Int, length: Int): Int {
        if (disconnectAfterBytes > 0 &&
            (disconnectEveryNthRequest <= 0 || requestNumber % disconnectEveryNthRequest == 0)
        ) {
            return disconnectAfterBytes
        }
        val rnd = randomDisconnect
        if (rnd != null && randomDisconnectProbability > 0 && rnd.nextDouble() < randomDisconnectProbability) {
            return rnd.nextInt(1, (length + 1).coerceAtLeast(2))
        }
        return 0
    }

    private fun parseRange(header: String, size: Int): Pair<Int, Int>? {
        val spec = header.removePrefix("bytes=").trim()
        val startStr = spec.substringBefore('-', missingDelimiterValue = "")
        val endStr = spec.substringAfter('-', missingDelimiterValue = "")
        val start = startStr.toIntOrNull() ?: return null
        val end = if (endStr.isEmpty()) size - 1 else endStr.toIntOrNull() ?: return null
        if (start < 0 || start >= size || end < start) return null
        return start to minOf(end, size - 1)
    }

    override fun close() {
        running = false
        dropKeepAlives()
        try {
            serverSocket.close()
        } catch (_: Exception) {
            // already closed
        }
        executor.shutdownNow()
    }
}
