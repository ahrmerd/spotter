package dev.ahmed.spotter.engine.sink

import android.content.ContentResolver
import android.os.Build
import java.io.File

/**
 * API 29+: MediaStore `Download/Spotter`, reopening the stored content URI on resume.
 * API 26–28: app-private files under [privateDir] (no storage permission).
 */
class AndroidSinkFactory(
    private val resolver: ContentResolver,
    private val privateDir: File,
) : FileSinkFactory {
    override fun open(filename: String, resumePath: String, mimeType: String?): FileSink {
        return if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreSink.open(resolver, filename, resumePath, mimeType)
        } else {
            PrivateFileSink.forName(privateDir, filename, resumePath)
        }
    }

    override fun nameTaken(filename: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return filename in MediaStoreSink.takenNames(resolver)
        }
        return File(privateDir, filename).exists() || File(privateDir, "$filename.part").exists()
    }

    override fun deletePartial(resumePath: String, filename: String) {
        if (Build.VERSION.SDK_INT >= 29 && resumePath.startsWith("content:")) {
            MediaStoreSink.deleteUri(resolver, resumePath)
            return
        }
        super.deletePartial(resumePath, filename)
        if (filename.isNotEmpty()) {
            File(privateDir, "$filename.part").takeIf { it.exists() }?.delete()
        }
    }

    override fun deleteFinished(resumePath: String) {
        if (Build.VERSION.SDK_INT >= 29 && resumePath.startsWith("content:")) {
            MediaStoreSink.deleteUri(resolver, resumePath)
            return
        }
        super.deleteFinished(resumePath)
    }

    override fun rename(resumePath: String, newName: String): String {
        if (Build.VERSION.SDK_INT >= 29 && resumePath.startsWith("content:")) {
            return MediaStoreSink.renameUri(resolver, resumePath, newName)
        }
        return super.rename(resumePath, newName)
    }
}
