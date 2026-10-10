package dev.ahmed.spotter.engine.sink

import java.io.Closeable

interface FileSink : Closeable {
    val outputPath: String
    fun writeAt(position: Long, data: ByteArray, offset: Int, length: Int)
    fun setLength(length: Long)
    /** Current size of the backing file; used to detect a missing/truncated partial file on resume. */
    fun length(): Long
    fun force()
    fun complete()
    fun discardPartial()
}

fun interface FileSinkFactory {
    fun open(filename: String, resumePath: String, mimeType: String?): FileSink

    fun nameTaken(filename: String): Boolean = false

    fun deletePartial(resumePath: String, filename: String) {
        if (resumePath.isEmpty() || resumePath.startsWith("content:")) return
        java.io.File("$resumePath.part").takeIf { it.exists() }?.delete()
    }

    fun deleteFinished(resumePath: String) {
        if (resumePath.isEmpty() || resumePath.startsWith("content:")) return
        java.io.File(resumePath).takeIf { it.exists() }?.delete()
    }

    /** Returns the new output path (may be unchanged). */
    fun rename(resumePath: String, newName: String): String {
        if (resumePath.isEmpty() || resumePath.startsWith("content:")) return resumePath
        val src = java.io.File(resumePath)
        val parent = src.parentFile ?: return resumePath
        val dest = java.io.File(parent, newName)
        if (src.exists() && src.renameTo(dest)) return dest.absolutePath
        val part = java.io.File("$resumePath.part")
        val destPart = java.io.File(dest.path + ".part")
        if (part.exists()) part.renameTo(destPart)
        return dest.absolutePath
    }
}
