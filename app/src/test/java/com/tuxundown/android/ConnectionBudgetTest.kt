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

    // ---- v5.9 step B: reallocation immediacy, as pure logic ----

    @Test
    fun floorHoldsAtTheExactBoundaryCapEqualsTaskCount() {
        // cap >= task count is the floor's precondition; at the
        // exact boundary every running task gets exactly 1.
        val demands = (1..5).map { d("t$it", 2, 32) }
        val out = ConnectionBudget.allocate(5, demands)
        assertEquals(5, out.values.sum())
        assertTrue(out.values.all { it == 1 })
    }

    @Test
    fun aVanishedTasksShareIsEatenByTheRestInTheSameCall() {
        // allocate is a stateless pure function of its inputs:
        // the moment a task leaves the demand list, the very
        // next call hands its share to the survivors — no
        // residue, no remembered old split to lag behind. This
        // is the logical root of "reallocation without lag" on
        // the connection side; the engine's tick only decides
        // WHEN the next call happens, never what it returns.
        val three = ConnectionBudget.allocate(
            32, listOf(d("a", 2, 32), d("b", 2, 32), d("c", 2, 32))
        )
        assertEquals(32, three.values.sum())
        val two = ConnectionBudget.allocate(
            32, listOf(d("a", 2, 32), d("b", 2, 32))
        )
        assertEquals(setOf("a", "b"), two.keys)
        assertEquals(32, two.values.sum())
        assertTrue(two.getValue("a") > three.getValue("a"))
        assertTrue(two.getValue("b") > three.getValue("b"))
    }

    @Test
    fun allocateHasNoMemoryBetweenCalls() {
        val demands = listOf(d("a", 4, 32), d("b", 1, 32))
        val first = ConnectionBudget.allocate(20, demands)
        // An unrelated split in between must not leak into a
        // repeat of the first call.
        ConnectionBudget.allocate(8, listOf(d("x", 2, 8), d("y", 2, 8)))
        assertEquals(first, ConnectionBudget.allocate(20, demands))
    }

    @Test
    fun fourToOneWeightsSplitACapOf32As26And6() {
        // The shares the audit-64 GrowthRamp contention tests
        // rest on: weights 4 : 1, both appetites at the
        // ceiling, cap 32.
        val out = ConnectionBudget.allocate(
            32, listOf(d("hi", 4, 128), d("lo", 1, 128))
        )
        assertEquals(26, out["hi"])
        assertEquals(6, out["lo"])
        assertEquals(32, out.values.sum())
    }

    @Test
    fun liveTargetsLetTheLeftoverFlowToTheHungryTask() {
        // v7.9 (audit round 64): the segmented desire the
        // caller passes is now each task's LIVE scaler target.
        // A task settled at 8 next to one aiming at 64 under a
        // cap of 32: the settled task keeps its whole 8 and
        // the hungry one gets the remaining 24 — with the old
        // static ceiling desires (128 / 128) the split was
        // 16 / 16 and 8 of the cap sat idle for the whole run.
        val out = ConnectionBudget.allocate(
            32, listOf(d("hungry", 2, 64), d("settled", 2, 8))
        )
        assertEquals(24, out["hungry"])
        assertEquals(8, out["settled"])
        assertEquals(32, out.values.sum())
    }

    @Test
    fun clampKeepsTheSettingInRange() {
        assertEquals(ConnectionBudget.MIN_CAP, ConnectionBudget.clampCap(1))
        assertEquals(ConnectionBudget.MAX_CAP, ConnectionBudget.clampCap(999))
        assertEquals(32, ConnectionBudget.clampCap(32))
        assertEquals(32, ConnectionBudget.DEFAULT_CAP)
    }
}
