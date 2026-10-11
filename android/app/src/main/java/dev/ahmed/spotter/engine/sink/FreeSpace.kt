package dev.ahmed.spotter.engine.sink

import java.io.IOException

fun interface FreeSpace {
    fun availableBytes(pathHint: String): Long

    fun check(needed: Long, pathHint: String) {
        if (needed <= 0L) return
        val available = availableBytes(pathHint)
        if (available == Long.MAX_VALUE) return
        val want = needed + RESERVE
        if (available < want) {
            throw InsufficientSpaceException(needed, available)
        }
    }

    companion object {
        const val RESERVE = 1L * 1024 * 1024
        val UNLIMITED = FreeSpace { Long.MAX_VALUE }
    }
}

class InsufficientSpaceException(
    val needed: Long,
    val available: Long,
) : IOException(
    "Not enough free space (need ${format(needed)}, have ${format(available)})",
) {
    companion object {
        private fun format(n: Long): String {
            val gb = 1024L * 1024 * 1024
            val mb = 1024L * 1024
            val kb = 1024L
            return when {
                n >= gb -> "${n / gb} GB"
                n >= mb -> "${n / mb} MB"
                n >= kb -> "${n / kb} KB"
                else -> "$n B"
            }
        }
    }
}
