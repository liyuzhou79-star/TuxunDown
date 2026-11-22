package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.StartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.2: the pure start-of-run plan — hint starts unchanged from
 * v1.5, unknown hosts burst in at 16, resumes restart at the
 * persisted count, and the remembered rate-limit cap bounds
 * every branch.
 */
class StartPlanTest {

    @Test
    fun hintStartsAtTheHintWithoutProbing() {
        val d = StartPlan.decide(hint = 24, cap = 0, resuming = false, persistedThreads = 0)
        assertEquals(24, d.start)
        assertFalse(d.probing)
    }

    @Test
    fun hintIsPressedByTheCap() {
        val d = StartPlan.decide(hint = 48, cap = 16, resuming = false, persistedThreads = 0)
        assertEquals(16, d.start)
        assertFalse(d.probing)
    }

    @Test
    fun hintBranchAppliesOnResumeToo() {
        // The engine passes hint = 0 for resumes today, but the
        // branch itself does not gate on `resuming`.
        val d = StartPlan.decide(hint = 20, cap = 0, resuming = true, persistedThreads = 8)
        assertEquals(20, d.start)
        assertFalse(d.probing)
    }

    @Test
    fun invalidCapIsIgnored() {
        // 0, negative, below MIN_CAP and above MAX_THREADS all
        // fall back to the engine max as the bound.
        for (badCap in listOf(0, -5, 2, 999)) {
            val fresh = StartPlan.decide(hint = 0, cap = badCap, resuming = false, persistedThreads = 0)
            assertEquals("cap=$badCap", StartPlan.BURST_START, fresh.start)
            val hinted = StartPlan.decide(hint = 64, cap = badCap, resuming = false, persistedThreads = 0)
            assertEquals("cap=$badCap", DownloadEngine.MAX_THREADS, hinted.start)
        }
    }

    @Test
    fun freshUnknownHostBurstsAt16() {
        val d = StartPlan.decide(hint = 0, cap = 0, resuming = false, persistedThreads = 0)
        assertEquals(16, d.start)
        assertEquals(StartPlan.BURST_START, d.start)
        assertTrue(d.probing)
        // Out-of-range hints are no hint at all.
        assertEquals(16, StartPlan.decide(1, 0, false, 0).start)
        assertEquals(16, StartPlan.decide(100, 0, false, 0).start)
    }

    @Test
    fun burstIsPressedByASmallCap() {
        val d = StartPlan.decide(hint = 0, cap = 4, resuming = false, persistedThreads = 0)
        assertEquals(4, d.start)
        assertTrue(d.probing)
    }

    @Test
    fun resumeRestartsAtThePersistedCount() {
        val d = StartPlan.decide(hint = 0, cap = 0, resuming = true, persistedThreads = 32)
        assertEquals(32, d.start)
        assertTrue(d.probing)
    }

    @Test
    fun resumePersistedIsClampedToTheLegalBand() {
        // A persisted 1 (or 0) restarts at the scaler's floor of 2.
        assertEquals(2, StartPlan.decide(0, 0, true, 1).start)
        assertEquals(2, StartPlan.decide(0, 0, true, 0).start)
        // A persisted count above the bound is pressed down to it.
        assertEquals(16, StartPlan.decide(0, 16, true, 48).start)
        assertEquals(64, StartPlan.decide(0, 0, true, 100).start)
    }
}
