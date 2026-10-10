package dev.ahmed.spotter.engine

object Categories {
    const val ALL = "All"
    const val VIDEO = "Video"
    const val AUDIO = "Audio"
    const val IMAGE = "Image"
    const val DOCUMENT = "Document"
    const val OTHER = "Other"

    val FILTERS = listOf(ALL, VIDEO, AUDIO, IMAGE, DOCUMENT, OTHER)

    fun of(mimeType: String?, filename: String): String {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        when {
            mime.startsWith("video/") -> return VIDEO
            mime.startsWith("audio/") -> return AUDIO
            mime.startsWith("image/") -> return IMAGE
            mime == "application/pdf" || mime.startsWith("text/") ||
                mime.contains("msword") || mime.contains("officedocument") ||
                mime.contains("opendocument") -> return DOCUMENT
        }
        return when (Filenames.extensionOf(filename).lowercase()) {
            ".mp4", ".webm", ".mkv", ".mov", ".avi", ".m3u8", ".ts" -> VIDEO
            ".mp3", ".m4a", ".ogg", ".wav", ".flac", ".weba" -> AUDIO
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp" -> IMAGE
            ".pdf", ".txt", ".doc", ".docx", ".odt", ".rtf" -> DOCUMENT
            else -> OTHER
        }
    }
}
