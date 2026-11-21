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
        // priority 1 (插队) weighs 2, normal weighs 1 -> 2:1 split.
        val shares = BandwidthBudget.shares(
            900, listOf(BandwidthBudget.weightOf(1), BandwidthBudget.weightOf(0))
        )
        assertEquals(listOf(600L, 300L), shares)
    }

    @Test
    fun zeroBudgetMeansUnlimitedZeros() {
        assertEquals(listOf(0L, 0L), BandwidthBudget.shares(0, listOf(1, 2)))
        assertEquals(emptyList<Long>(), BandwidthBudget.shares(100, emptyList()))
    }
}
