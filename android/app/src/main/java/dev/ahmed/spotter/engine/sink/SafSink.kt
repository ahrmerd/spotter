package dev.ahmed.spotter.engine.sink

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Writes into a user-picked SAF tree. Resume reopens the stored document URI.
 */
class SafSink(
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
        fun open(
            resolver: ContentResolver,
            treeUri: String,
            displayName: String,
            resumePath: String,
            mimeType: String?,
        ): SafSink {
            if (resumePath.startsWith("content:")) {
                val existing = openExisting(resolver, Uri.parse(resumePath))
                if (existing != null) return existing
            }
            return create(resolver, Uri.parse(treeUri), displayName, mimeType)
        }

        fun openExisting(resolver: ContentResolver, uri: Uri): SafSink? {
            return try {
                val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
                SafSink(resolver, uri, pfd, displayName(resolver, uri.toString()))
            } catch (e: SecurityException) {
                null
            } catch (e: java.io.FileNotFoundException) {
                null
            }
        }

        fun create(
            resolver: ContentResolver,
            tree: Uri,
            displayName: String,
            mimeType: String?,
        ): SafSink {
            val parent = treeDocumentUri(tree)
            val unique = uniqueDisplayName(resolver, tree, displayName)
            val mime = mimeType?.ifBlank { null } ?: "application/octet-stream"
            val created = DocumentsContract.createDocument(resolver, parent, mime, unique)
                ?: throw IOException("SAF create failed for $displayName")
            val pfd = resolver.openFileDescriptor(created, "rw")
                ?: throw IOException("SAF open failed for $created")
            val actual = displayName(resolver, created.toString()).ifBlank { unique }
            return SafSink(resolver, created, pfd, actual)
        }

        fun nameTaken(resolver: ContentResolver, treeUri: String, filename: String): Boolean {
            if (treeUri.isBlank()) return false
            return try {
                filename in takenNames(resolver, Uri.parse(treeUri))
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        fun takenNames(resolver: ContentResolver, tree: Uri): Set<String> {
            val parent = treeDocumentUri(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent),
            )
            val taken = hashSetOf<String>()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx < 0) return taken
                while (c.moveToNext()) {
                    val name = c.getString(idx) ?: continue
                    taken.add(name)
                }
            }
            return taken
        }

        fun uniqueDisplayName(resolver: ContentResolver, tree: Uri, desired: String): String {
            val taken = takenNames(resolver, tree)
            return dev.ahmed.spotter.engine.Filenames.unique(desired) { it in taken }
        }

        fun deleteUri(resolver: ContentResolver, resumePath: String) {
            if (!resumePath.startsWith("content:")) return
            resolver.delete(Uri.parse(resumePath), null, null)
        }

        fun renameUri(resolver: ContentResolver, resumePath: String, newName: String): String {
            if (!resumePath.startsWith("content:")) return resumePath
            val uri = Uri.parse(resumePath)
            val renamed = DocumentsContract.renameDocument(resolver, uri, newName) ?: uri
            return renamed.toString()
        }

        /**
         * Human name of a document or of a picked folder (tree). A tree URI cannot be queried directly: it has to be
         * turned into the tree's root document URI first, otherwise the provider throws UnsupportedOperationException
         * (which crashed the app at start-up once a folder had been chosen). Never throws; falls back to the last
         * path segment ("primary:Movies" -> "Movies").
         */
        fun displayName(resolver: ContentResolver, uriString: String): String {
            if (uriString.isBlank()) return ""
            val uri = try {
                Uri.parse(uriString)
            } catch (e: RuntimeException) {
                return uriString
            }
            val fallback = uri.lastPathSegment.orEmpty().substringAfterLast(':').substringAfterLast('/')
            return try {
                val isDocument = uri.pathSegments.contains("document")
                val queryUri = if (!isDocument && DocumentsContract.isTreeUri(uri)) treeDocumentUri(uri) else uri
                resolver.query(queryUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0).orEmpty() else ""
                }.orEmpty().ifBlank { fallback }
            } catch (e: RuntimeException) {
                // SecurityException (permission revoked), UnsupportedOperationException, IllegalArgumentException, ...
                fallback
            }
        }

        private fun treeDocumentUri(tree: Uri): Uri {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        }
    }
}
