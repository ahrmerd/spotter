package dev.ahmed.spotter.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ahmed.spotter.engine.model.DownloadRecord
import java.io.File

object FileIntents {
    fun uriFor(context: Context, rec: DownloadRecord): Uri? {
        val path = rec.outputPath
        if (path.isEmpty()) return null
        return if (path.startsWith("content:")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) return null
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    }

    fun open(context: Context, rec: DownloadRecord) {
        val uri = uriFor(context, rec) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, rec.mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, rec.filename))
    }

    fun share(context: Context, rec: DownloadRecord) {
        val uri = uriFor(context, rec) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(rec.mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, rec.filename)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, rec.filename))
    }
}
