package dev.ahmed.spotter.engine.sink

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class PrivateFileSink(
    private val partFile: File,
    private val finalFile: File,
) : FileSink {
    private val raf = RandomAccessFile(partFile, "rw")
    private val channel = raf.channel
    private var closed = false

    override val outputPath: String get() = finalFile.absolutePath

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
        raf.setLength(length)
    }

    override fun length(): Long = channel.size()

    override fun force() {
        channel.force(true)
    }

    override fun complete() {
        force()
        closeQuiet()
        if (partFile == finalFile) return // already finalised before a crash; parts were persisted as complete
        if (finalFile.exists()) {
            throw IOException("refusing to overwrite existing file ${finalFile.name}")
        }
        if (partFile.renameTo(finalFile)) return
        partFile.copyTo(finalFile, overwrite = false)
        if (!partFile.delete() && partFile.exists()) {
            throw IOException("renamed by copy but could not delete ${partFile.name}")
        }
    }

    override fun discardPartial() {
        closeQuiet()
        if (partFile.exists() && !partFile.delete()) {
            throw IOException("could not delete partial file ${partFile.name}")
        }
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
            raf.close()
        }
    }

    companion object {
        fun forName(directory: File, filename: String, resumePath: String): PrivateFileSink {
            directory.mkdirs()
            val finalFile = if (resumePath.isNotEmpty()) File(resumePath) else File(directory, filename)
            val part = File(finalFile.path + ".part")
            // Crash window: file was renamed to its final name but the DB still says RUNNING. Reopen the final file.
            if (resumePath.isNotEmpty() && finalFile.exists() && !part.exists()) return PrivateFileSink(finalFile, finalFile)
            return PrivateFileSink(part, finalFile)
        }
    }
}
