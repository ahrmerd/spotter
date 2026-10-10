package dev.ahmed.spotter.engine.model

data class PartRecord(
    val id: Long = 0,
    val downloadId: Long,
    val start: Long,
    val current: Long,
    val end: Long,
)
