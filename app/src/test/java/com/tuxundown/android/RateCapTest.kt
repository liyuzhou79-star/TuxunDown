package com.tuxundown.android

import com.tuxundown.android.core.RateCap
import org.junit.Assert.assertEquals
import org.junit.Test

/** v2.1: per-task cap x global budget — the smaller set limit wins. */
class RateCapTest {

    @Test
    fun neitherSetMeansUnlimited() {
        assertEquals(0L, RateCap.effectiveBps(0, 0))
    }

    @Test
    fun onlyGlobalShareApplies() {
        assertEquals(2_000_000L, RateCap.effectiveBps(2_000_000, 0))
    }

    @Test
    fun onlyTaskCapAppliesAndConvertsUnits() {
        // 1024 KB/s on the task = 1 MiB/s of budget.
        assertEquals(1024L * 1024, RateCap.effectiveBps(0, 1024))
    }

    @Test
    fun smallerOfTheTwoWins() {
        // Task cap (512 KB/s) below the global share -> task cap.
        assertEquals(512L * 1024, RateCap.effectiveBps(10_000_000, 512))
        // Global share squeezed below the task cap -> global share.
        assertEquals(100_000L, RateCap.effectiveBps(100_000, 5120))
    }

    @Test
    fun negativeInputsReadAsUnset() {
        assertEquals(0L, RateCap.effectiveBps(-5, -1))
        assertEquals(3L * 1024, RateCap.effectiveBps(-5, 3))
    }
}
