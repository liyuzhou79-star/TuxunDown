package com.tuxundown.android

import com.tuxundown.android.core.ConnectionBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.9: the global connection cap split (see ConnectionBudget).
 * The properties that matter: exact total, desires respected,
 * priority weights steer the split, and the one-connection
 * floor holds while the cap can afford it.
 */
class ConnectionBudgetTest {

    private fun d(id: String, w: Int, want: Int) =
        ConnectionBudget.Demand(id, w, want)

    @Test
    fun everyoneGetsTheirDesireWhenItFits() {
        val out = ConnectionBudget.allocate(
            32, listOf(d("a", 2, 10), d("b", 2, 8))
        )
        assertEquals(10, out["a"])
        assertEquals(8, out["b"])
    }

    @Test
    fun weightedSplitIsProportionalAndExact() {
        // The priority tiers' weights are 4 : 2 : 1; a cap of
        // 28 over three bottomless appetites splits 16 / 8 / 4.
        val out = ConnectionBudget.allocate(
            28, listOf(d("hi", 4, 32), d("mid", 2, 32), d("low", 1, 32))
        )
        assertEquals(28, out.values.sum())
        assertEquals(16, out["hi"])
        assertEquals(8, out["mid"])
        assertEquals(4, out["low"])
    }

    @Test
    fun desireCapsAndTheLeftoverFlowsToTheRest() {
        // hi only wants 4 of a 32 cap; the other task must end
        // up with the remaining 28, not a "fair" 16.
        val out = ConnectionBudget.allocate(
            32, listOf(d("hi", 2, 4), d("low", 2, 32))
        )
        assertEquals(4, out["hi"])
        assertEquals(28, out["low"])
        assertEquals(32, out.values.sum())
    }

    @Test
    fun everyoneKeepsOneConnectionWhileTheCapAffordsIt() {
        val demands = (1..5).map { d("t$it", 2, 32) }
        val out = ConnectionBudget.allocate(8, demands)
        assertEquals(8, out.values.sum())
        assertTrue(out.values.all { it >= 1 })
    }

    @Test
    fun capBelowTaskCountServesHeaviestFirst() {
        // Unreachable with the shipped ranges (cap >= 8, at
        // most 5 concurrent), but the rule is pinned: weight
        // order decides who gets the scarce connections.
        val out = ConnectionBudget.allocate(
            2, listOf(d("a", 1, 10), d("b", 2, 10), d("c", 3, 10))
        )
        assertEquals(1, out["c"])
        assertEquals(1, out["b"])
        assertEquals(0, out["a"])
        assertEquals(2, out.values.sum())
    }

    @Test
    fun zeroCapAndNoDemandsAreWellBehaved() {
        assertTrue(ConnectionBudget.allocate(32, emptyList()).isEmpty())
        val out = ConnectionBudget.allocate(0, listOf(d("a", 2, 10)))
        assertEquals(0, out["a"])
    }

    @Test
    fun clampKeepsTheSettingInRange() {
        assertEquals(ConnectionBudget.MIN_CAP, ConnectionBudget.clampCap(1))
        assertEquals(ConnectionBudget.MAX_CAP, ConnectionBudget.clampCap(999))
        assertEquals(32, ConnectionBudget.clampCap(32))
        assertEquals(32, ConnectionBudget.DEFAULT_CAP)
    }
}
