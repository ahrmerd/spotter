package dev.ahmed.spotter.browser

data class DetectedItem(
    val url: String,
    val kind: MediaKind,
    val type: String,
    val size: Long?,
    val headers: Map<String, String>,
    val pageUrl: String,
    val pageTitle: String,
    val suggestedName: String,
    val detectedAt: Long,
    val fromPlay: Boolean = false,
)
