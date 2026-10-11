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
    override val outputName: String,
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
        const val RELATIVE_DIR = "Download/Spotter/"

        @RequiresApi(29)
        fun open(
            resolver: ContentResolver,
            displayName: String,
            resumePath: String,
            mimeType: String?,
        ): MediaStoreSink {
            if (resumePath.startsWith("content:")) {
                val existing = openExisting(resolver, Uri.parse(resumePath))
                if (existing != null) return existing
            }
            return create(resolver, displayName, mimeType)
        }

        @RequiresApi(29)
        fun openExisting(resolver: ContentResolver, uri: Uri): MediaStoreSink? {
            return try {
                val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
                MediaStoreSink(resolver, uri, pfd, queryDisplayName(resolver, uri).orEmpty())
            } catch (e: SecurityException) {
                null
            } catch (e: java.io.FileNotFoundException) {
                null
            }
        }

        @RequiresApi(29)
        fun create(resolver: ContentResolver, displayName: String, mimeType: String?): MediaStoreSink {
            val unique = uniqueDisplayName(resolver, displayName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, unique)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Spotter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed for $displayName")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("MediaStore open failed for $uri")
            val actual = queryDisplayName(resolver, uri) ?: unique
            return MediaStoreSink(resolver, uri, pfd, actual)
        }

        @RequiresApi(29)
        fun uniqueDisplayName(resolver: ContentResolver, desired: String): String {
            val taken = takenNames(resolver)
            return dev.ahmed.spotter.engine.Filenames.unique(desired) { it in taken }
        }

        @RequiresApi(29)
        fun takenNames(resolver: ContentResolver): Set<String> {
            val taken = hashSetOf<String>()
            val dir = Environment.DIRECTORY_DOWNLOADS + "/Spotter"
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} IN (?, ?)",
                arrayOf(dir, "$dir/"),
                null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(idx) ?: continue
                    taken.add(name)
                }
            }
            return taken
        }

        @RequiresApi(29)
        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
            return resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }

        fun deleteUri(resolver: ContentResolver, resumePath: String) {
            if (!resumePath.startsWith("content:")) return
            resolver.delete(Uri.parse(resumePath), null, null)
        }

        @RequiresApi(29)
        fun renameUri(resolver: ContentResolver, resumePath: String, newName: String): String {
            if (!resumePath.startsWith("content:")) return resumePath
            val uri = Uri.parse(resumePath)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, newName)
            }
            resolver.update(uri, values, null, null)
            return resumePath
        }
    }
}
