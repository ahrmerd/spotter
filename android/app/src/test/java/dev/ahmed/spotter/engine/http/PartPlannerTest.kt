package dev.ahmed.spotter.engine.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartPlannerTest {
    @Test
    fun splitsHundredMegIntoFourParts() {
        val size = 100L * 1024 * 1024
        val parts = PartPlanner.initialParts(size, ranged = true, connections = 4)
        assertEquals(4, parts.size)
        assertEquals(0L, parts.first().start)
        assertEquals(size - 1, parts.last().end)
        assertEquals(size, parts.sumOf { it.end - it.start + 1 })
        for (i in 1 until parts.size) {
            assertEquals(parts[i - 1].end + 1, parts[i].start)
        }
    }

    @Test
    fun noRangeIsSingleStream() {
        val parts = PartPlanner.initialParts(50_000, ranged = false, connections = 8)
        assertEquals(1, parts.size)
        assertEquals(0L, parts[0].start)
    }

    @Test
    fun stealTakesUpperHalfOfSlowest() {
        val parts = listOf(
            PartPlan(0, 50, 99),
            PartPlan(100, 100, 10_000_000),
        )
        val steal = PartPlanner.steal(parts)!!
        assertEquals(1, steal.fromIndex)
        assertTrue(steal.stolen.start > parts[1].current)
        assertEquals(parts[1].end, steal.stolen.end)
        assertEquals(steal.stolen.start - 1, steal.newEndForOriginal)
    }

    @Test
    fun noStealWhenRemainingBelowMinSplit() {
        val parts = listOf(PartPlan(0, 0, 1000))
        assertNull(PartPlanner.steal(parts))
    }
}
