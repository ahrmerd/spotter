package dev.ahmed.spotter.engine.sink

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Writes into MediaStore `Downloads/Spotter`. API 29+ only (no storage permission).
 * Callers on API 26–28 should use [PrivateFileSink].
 */
class MediaStoreSink(
    private val resolver: ContentResolver,
    val uri: Uri,
    private val pfd: ParcelFileDescriptor,
) : FileSink {
    private val channel = FileOutputStream(pfd.fileDescriptor).channel
    private var closed = false

    override val outputPath: String get() = uri.toString()

    override fun writeAt(position: Long, data: ByteArray, offset: Int, length: Int) {
        val buf = ByteBuffer.wrap(data, offset, length)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.write(buf, pos)
            if (n <= 0) throw IOException("write returned $n at $pos")
            pos += n
        }
    }

    override fun setLength(length: Long) {
        if (length < channel.size()) {
            channel.truncate(length)
        } else if (length > channel.size()) {
            val pad = ByteBuffer.allocate(1)
            if (length > 0) channel.write(pad, length - 1)
        }
    }

    override fun length(): Long = channel.size()

    override fun force() {
        channel.force(true)
        pfd.fileDescriptor.sync()
    }

    override fun complete() {
        force()
        closeQuiet()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
    }

    override fun discardPartial() {
        closeQuiet()
        resolver.delete(uri, null, null)
    }

    override fun close() {
        closeQuiet()
    }

    private fun closeQuiet() {
        if (closed) return
        closed = true
        try {
            channel.close()
        } finally {
            pfd.close()
        }
    }

    companion object {
        @RequiresApi(29)
        fun create(resolver: ContentResolver, displayName: String, mimeType: String?): MediaStoreSink {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Spotter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed for $displayName")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("MediaStore open failed for $uri")
            return MediaStoreSink(resolver, uri, pfd)
        }
    }
}
