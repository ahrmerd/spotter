package dev.ahmed.spotter.engine.net

fun interface NetworkPolicy {
    fun canDownload(): Boolean

    companion object {
        val ALWAYS = NetworkPolicy { true }
    }
}
