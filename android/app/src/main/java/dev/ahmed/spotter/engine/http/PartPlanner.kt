package dev.ahmed.spotter.engine.http

data class PartPlan(
    val start: Long,
    val current: Long,
    val end: Long,
) {
    val remaining: Long
        get() = if (end < 0) Long.MAX_VALUE else (end - current + 1).coerceAtLeast(0)

    val complete: Boolean
        get() = end >= 0 && current > end
}

data class StealPlan(
    val fromIndex: Int,
    val newEndForOriginal: Long,
    val stolen: PartPlan,
)

object PartPlanner {
    const val MIN_SPLIT = 1L * 1024 * 1024

    fun initialParts(size: Long?, ranged: Boolean, connections: Int): List<PartPlan> {
        val n = connections.coerceIn(1, 16)
        if (!ranged || size == null || size <= 0L) {
            val end = if (size != null && size > 0L) size - 1 else -1L
            return listOf(PartPlan(0, 0, end))
        }
        if (size < MIN_SPLIT) {
            return listOf(PartPlan(0, 0, size - 1))
        }
        val count = minOf(n, (size / MIN_SPLIT).toInt().coerceAtLeast(1))
        val step = size / count
        return (0 until count).map { i ->
            val start = i * step
            val end = if (i == count - 1) size - 1 else (i + 1) * step - 1
            PartPlan(start, start, end)
        }
    }

    fun steal(parts: List<PartPlan>, minSplit: Long = MIN_SPLIT): StealPlan? {
        val idx = parts.indices
            .filter { !parts[it].complete && parts[it].end >= 0 }
            .maxByOrNull { parts[it].remaining } ?: return null
        val target = parts[idx]
        if (target.remaining < minSplit) return null
        val stolenStart = target.current + target.remaining / 2
        if (stolenStart > target.end || stolenStart <= target.current) return null
        return StealPlan(
            fromIndex = idx,
            newEndForOriginal = stolenStart - 1,
            stolen = PartPlan(stolenStart, stolenStart, target.end),
        )
    }
}
