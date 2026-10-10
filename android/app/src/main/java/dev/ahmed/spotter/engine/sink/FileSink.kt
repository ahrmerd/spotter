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
    fun open(filename: String, resumePath: String): FileSink
}
