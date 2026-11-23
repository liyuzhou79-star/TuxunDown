package com.tuxundown.android

import com.tuxundown.android.core.ConnectionBudgetLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.9 (audit round 44 step C): the task detail's 「连接预算」
 * verdict (see ConnectionBudgetLine). Every reason branch is
 * pinned, multi-cause snapshots name the tightest clamp, and
 * contradictory snapshots name NO reason — the row may show a
 * number it cannot explain, never an invented cause.
 */
class ConnectionBudgetLineTest {

    private fun judge(
        allowance: Int,
        desire: Int,
        hostCap: Int = 0,
        taskCeiling: Int = 64,
        globalCap: Int = 32,
        liveConnections: Int = -1,
        othersRunning: Boolean = false,
    ) = ConnectionBudgetLine.judge(
        allowance, desire, hostCap, taskCeiling, globalCap,
        liveConnections, othersRunning,
    )

    // ---- the four provable reasons, one case each ----

    @Test
    fun hostCapBindsWhenRememberedCapSitsBelowDesire() {
        val v = judge(allowance = 64, desire = 64, hostCap = 16, globalCap = 128)
        assertEquals(16, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.HOST_CAP, v.reason)
        assertTrue(v.line.contains("16 条连接"))
        assertTrue(v.line.contains("限流过"))
    }

    @Test
    fun globalCapBindsWhenAllowanceComesBackBelowDesire() {
        val v = judge(
            allowance = 20, desire = 64, globalCap = 32, othersRunning = true,
        )
        assertEquals(20, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.GLOBAL_CAP, v.reason)
        assertTrue(v.line.contains("全局连接上限"))
        assertTrue(v.line.contains("32 条连接"))
        assertTrue(v.line.contains("按优先级分"))
    }

    @Test
    fun globalCapAloneBelowOneDesireNamesNoPrioritySplit() {
        // One task, desire 64 > cap 32: the cap binds with
        // nobody else running, so the split phrase must not
        // appear.
        val v = judge(allowance = 32, desire = 64, globalCap = 32)
        assertEquals(ConnectionBudgetLine.Reason.GLOBAL_CAP, v.reason)
        assertFalse(v.line.contains("按优先级"))
    }

    @Test
    fun taskCeilingWhenDesireIsTheEngineMaxAndNothingCutIt() {
        val v = judge(allowance = 64, desire = 64, globalCap = 128)
        assertEquals(64, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.TASK_CEILING, v.reason)
        assertTrue(v.line.contains("自己的上限"))
    }

    @Test
    fun unlimitedWhenFullDesireGrantedBelowTheCeiling() {
        val v = judge(allowance = 10, desire = 10, globalCap = 128)
        assertEquals(10, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.UNLIMITED, v.reason)
        assertTrue(v.line.contains("没有被压住"))
    }

    // ---- multi-cause: the tightest (final) clamp wins ----

    @Test
    fun globalWinsOverHostWhenItCutsBelowTheHostCap() {
        // Chain: desire 64 --host cap--> 16 --global split--> 8.
        // The 8 on screen was produced by the global split.
        val v = judge(
            allowance = 8, desire = 64, hostCap = 16,
            globalCap = 32, othersRunning = true,
        )
        assertEquals(8, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.GLOBAL_CAP, v.reason)
    }

    @Test
    fun hostWinsOverGlobalWhenAllowanceStaysAboveTheCap() {
        // Allowance 32 never binds: the host cap's 16 is the
        // number the engine actually stops at.
        val v = judge(allowance = 32, desire = 64, hostCap = 16, globalCap = 128)
        assertEquals(16, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.HOST_CAP, v.reason)
    }

    // ---- unprovable snapshots: number only, no invented reason ----

    @Test
    fun unknownWhenDesireExceedsTheStatedCeiling() {
        val v = judge(allowance = 64, desire = 100, taskCeiling = 64, globalCap = 128)
        assertEquals(ConnectionBudgetLine.Reason.UNKNOWN, v.reason)
        assertEquals("现在分到 64 条连接。", v.line)
    }

    @Test
    fun unknownWhenAllowanceExceedsDesireWithNoHostCap() {
        // allocate() can never hand back more than the desire;
        // with no host cap in play the snapshot contradicts
        // itself, so no reason is named.
        val v = judge(allowance = 50, desire = 10, globalCap = 128)
        assertEquals(ConnectionBudgetLine.Reason.UNKNOWN, v.reason)
        assertFalse(v.line.contains("压住"))
        assertFalse(v.line.contains("上限"))
    }

    @Test
    fun unknownWhenInputsAreNegative() {
        val v = judge(allowance = -1, desire = 64)
        assertEquals(ConnectionBudgetLine.Reason.UNKNOWN, v.reason)
    }

    // ---- HLS / FTP calibers (their own ceilings, not 64) ----

    @Test
    fun hlsDesireAtItsOwnWidthCeilingIsTaskCeiling() {
        val v = judge(allowance = 16, desire = 16, taskCeiling = 16, globalCap = 32)
        assertEquals(16, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.TASK_CEILING, v.reason)
        assertTrue(v.line.contains("16 条连接"))
    }

    @Test
    fun hlsHostCapBindsBelowTheWidth() {
        val v = judge(
            allowance = 16, desire = 16, hostCap = 4,
            taskCeiling = 16, globalCap = 32,
        )
        assertEquals(4, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.HOST_CAP, v.reason)
    }

    @Test
    fun ftpDesireAtEightIsTaskCeiling() {
        val v = judge(allowance = 8, desire = 8, taskCeiling = 8, globalCap = 32)
        assertEquals(8, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.TASK_CEILING, v.reason)
    }

    @Test
    fun ftpSplitByGlobalCapIsGlobal() {
        val v = judge(
            allowance = 3, desire = 8, taskCeiling = 8,
            globalCap = 32, othersRunning = true,
        )
        assertEquals(3, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.GLOBAL_CAP, v.reason)
    }

    // ---- live count + the single-connection natural demand ----

    @Test
    fun liveCountShownOnlyWhenItDiffersFromTheGrant() {
        val climbing = judge(
            allowance = 32, desire = 64, globalCap = 32, liveConnections = 16,
        )
        assertTrue(climbing.line.contains("实际在跑 16 条"))
        val settled = judge(
            allowance = 10, desire = 10, globalCap = 128, liveConnections = 10,
        )
        assertFalse(settled.line.contains("实际在跑"))
    }

    @Test
    fun singleConnectionDesireIsNaturalNotACeiling() {
        val v = judge(allowance = 1, desire = 1, taskCeiling = 1, globalCap = 32)
        assertEquals(1, v.granted)
        assertEquals(ConnectionBudgetLine.Reason.UNLIMITED, v.reason)
    }
}
