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
    private val treeUri: () -> String = { "" },
) : FileSinkFactory {
    override fun open(filename: String, resumePath: String, mimeType: String?): FileSink {
        val tree = treeUri().trim()
        if (tree.isNotEmpty()) {
            try {
                return SafSink.open(resolver, tree, filename, resumePath, mimeType)
            } catch (e: SecurityException) {
                // Permission was revoked; fall through to the default location.
            } catch (e: IllegalArgumentException) {
                // Bad tree URI; fall through.
            }
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreSink.open(resolver, filename, resumePath, mimeType)
        } else {
            PrivateFileSink.forName(privateDir, filename, resumePath)
        }
    }

    override fun nameTaken(filename: String): Boolean {
        val tree = treeUri().trim()
        if (tree.isNotEmpty() && SafSink.nameTaken(resolver, tree, filename)) return true
        if (Build.VERSION.SDK_INT >= 29) {
            return filename in MediaStoreSink.takenNames(resolver)
        }
        return File(privateDir, filename).exists() || File(privateDir, "$filename.part").exists()
    }

    override fun deletePartial(resumePath: String, filename: String) {
        if (resumePath.startsWith("content:")) {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaStoreSink.deleteUri(resolver, resumePath)
            } else {
                SafSink.deleteUri(resolver, resumePath)
            }
            return
        }
        super.deletePartial(resumePath, filename)
        if (filename.isNotEmpty()) {
            File(privateDir, "$filename.part").takeIf { it.exists() }?.delete()
        }
    }

    override fun deleteFinished(resumePath: String) {
        if (resumePath.startsWith("content:")) {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaStoreSink.deleteUri(resolver, resumePath)
            } else {
                SafSink.deleteUri(resolver, resumePath)
            }
            return
        }
        super.deleteFinished(resumePath)
    }

    override fun rename(resumePath: String, newName: String): String {
        if (resumePath.startsWith("content:")) {
            return try {
                SafSink.renameUri(resolver, resumePath, newName)
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= 29) {
                    MediaStoreSink.renameUri(resolver, resumePath, newName)
                } else {
                    resumePath
                }
            }
        }
        return super.rename(resumePath, newName)
    }
}
