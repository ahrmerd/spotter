package dev.ahmed.spotter.engine

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only rotating log. Each line is redacted before write so cookies and keys never land on disk.
 */
class RotatingFileLog(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val generations: Int = DEFAULT_GENERATIONS,
) : EngineLog {
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val currentFile: File get() = File(dir, FILE_NAME)

    override fun log(level: String, message: String, error: Throwable?) {
        val ts = synchronized(lock) { stamp.format(Date()) }
        val err = error?.let { " ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val line = ErrorRedact.redact("$ts $level ${message}$err")
        synchronized(lock) {
            try {
                dir.mkdirs()
                val file = currentFile
                file.appendText(line + "\n")
                if (file.length() >= maxBytes) rotateLocked()
            } catch (e: IOException) {
                System.err.println("Spotter/log write failed: ${e.message}")
            }
        }
    }

    fun readAll(): String = synchronized(lock) {
        buildString {
            for (i in (generations - 1) downTo 1) {
                val f = File(dir, "$FILE_NAME.$i")
                if (f.isFile) append(f.readText())
            }
            if (currentFile.isFile) append(currentFile.readText())
        }
    }

    fun exportTo(dest: File) {
        dest.parentFile?.mkdirs()
        dest.writeText(readAll())
    }

    private fun rotateLocked() {
        val oldest = File(dir, "$FILE_NAME.${generations - 1}")
        if (oldest.exists() && !oldest.delete() && oldest.exists()) {
            throw IOException("could not delete ${oldest.name}")
        }
        for (i in (generations - 2) downTo 1) {
            val src = File(dir, "$FILE_NAME.$i")
            if (src.exists()) {
                val dst = File(dir, "$FILE_NAME.${i + 1}")
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
        }
        val cur = currentFile
        if (cur.exists()) {
            val dst = File(dir, "$FILE_NAME.1")
            if (!cur.renameTo(dst)) {
                cur.copyTo(dst, overwrite = true)
                cur.delete()
            }
        }
    }

    companion object {
        const val FILE_NAME = "spotter.log"
        const val DEFAULT_MAX_BYTES = 256L * 1024
        const val DEFAULT_GENERATIONS = 3
    }
}
