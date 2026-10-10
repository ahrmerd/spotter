package dev.ahmed.spotter.engine.sink

import android.content.Context
import android.os.StatFs
import java.io.File

class AndroidFreeSpace(
    private val context: Context,
) : FreeSpace {
    override fun availableBytes(pathHint: String): Long {
        val path = directoryFor(pathHint)
        return try {
            StatFs(path).availableBytes
        } catch (e: IllegalArgumentException) {
            StatFs(context.filesDir.absolutePath).availableBytes
        }
    }

    private fun directoryFor(pathHint: String): String {
        if (pathHint.startsWith("/")) {
            val f = File(pathHint)
            val dir = if (f.isDirectory) f else f.parentFile
            if (dir != null && dir.exists()) return dir.absolutePath
        }
        val ext = context.getExternalFilesDir(null)
        if (ext != null && ext.exists()) return ext.absolutePath
        return context.filesDir.absolutePath
    }
}
