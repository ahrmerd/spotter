package dev.ahmed.spotter.engine.model

enum class DownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    ERROR,
    NEEDS_REFRESH,
}
