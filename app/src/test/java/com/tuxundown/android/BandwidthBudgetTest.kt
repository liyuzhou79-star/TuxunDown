package com.tuxundown.android

import com.tuxundown.android.core.BandwidthBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6: the global speed limit is one budget shared by weight. */
class BandwidthBudgetTest {

    @Test
    fun singleTaskGetsWholeBudget() {
        assertEquals(listOf(1000L), BandwidthBudget.shares(1000, listOf(1)))
    }

    @Test
    fun equalWeightsSplitEvenlyAndSumExactly() {
        val shares = BandwidthBudget.shares(1000, listOf(1, 1, 1))
        assertEquals(1000L, shares.sum())
        assertTrue(shares.all { it in 333..334 })
    }

    @Test
    fun boostedTaskWeighsDouble() {
        // priority 1 (插队/高） weighs 2x normal -> 2:1 split.
        // (v3.1: the integer scale is 4 vs 2 — the RATIO is what
        // v1.6 promised, and it is unchanged.)
        val shares = BandwidthBudget.shares(
            900, listOf(BandwidthBudget.weightOf(1), BandwidthBudget.weightOf(0))
        )
        assertEquals(listOf(600L, 300L), shares)
    }

    @Test
    fun threeTiersSplitTwoOneHalf() {
        // v3.1: 高：中：低 = 2 : 1 : 0.5 across a shared budget.
        assertEquals(4, BandwidthBudget.weightOf(1))
        assertEquals(2, BandwidthBudget.weightOf(0))
        assertEquals(1, BandwidthBudget.weightOf(-1))
        val shares = BandwidthBudget.shares(
            700,
            listOf(
                BandwidthBudget.weightOf(1),
                BandwidthBudget.weightOf(0),
                BandwidthBudget.weightOf(-1),
            ),
        )
        assertEquals(listOf(400L, 200L, 100L), shares)
    }

    // ---- v5.9 step B: live-set shares + the positive floor ----

    @Test
    fun lowestShareAtTheAuditedExtremeStaysPositive() {
        // Step A's arithmetic: the smallest total a limit can
        // ship is 1024 B/s, and at a weight sum of 20 the
        // weight-1 slice is 1024 * 1 / 20 = 51 B/s — small,
        // never zero, so no running task is bandwidth-starved
        // to a standstill by the split itself.
        val shares = BandwidthBudget.shares(
            1024, listOf(4, 4, 4, 4, 1, 1, 1, 1)
        )
        assertEquals(1024L, shares.sum())
        assertTrue(shares.all { it >= 51L })
        assertEquals(51L, shares.min())
    }

    @Test
    fun aDepartedTasksShareFlowsToTheSurvivorInTheSameCall() {
        // shares() is recomputed from the CURRENT weight list
        // on every call: when a task leaves, the survivor's
        // very next share is the whole budget — the bandwidth
        // side of "reallocation without lag" (the limiter
        // re-reads this on every buffer write).
        assertEquals(
            listOf(500L, 500L), BandwidthBudget.shares(1000, listOf(1, 1))
        )
        assertEquals(listOf(1000L), BandwidthBudget.shares(1000, listOf(1)))
    }

    @Test
    fun sharesHaveNoMemoryAndFollowTheCurrentWeightsOnly() {
        val before = BandwidthBudget.shares(900, listOf(4, 2))
        assertEquals(listOf(600L, 300L), before)
        // An unrelated call in between changes nothing...
        BandwidthBudget.shares(12345, listOf(1, 1, 1))
        assertEquals(before, BandwidthBudget.shares(900, listOf(4, 2)))
        // ...and swapping the weights swaps the shares at once.
        assertEquals(
            listOf(300L, 600L), BandwidthBudget.shares(900, listOf(2, 4))
        )
    }

    @Test
    fun zeroBudgetMeansUnlimitedZeros() {
        assertEquals(listOf(0L, 0L), BandwidthBudget.shares(0, listOf(1, 2)))
        assertEquals(emptyList<Long>(), BandwidthBudget.shares(100, emptyList()))
    }
}
