package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.StartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.2: the pure start-of-run plan — hint starts unchanged from
 * v1.5, unknown hosts burst in (16 originally; v7.7 Step B3
 * raised the burst to 32), resumes restart at the persisted
 * count (v7.7 Step B3: with the remembered hint as a floor),
 * and the remembered rate-limit cap bounds every branch.
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
    fun resumeUsesTheHintAsAFloorAndKeepsProbing() {
        // v7.7 (Step B3): the engine now passes the hint for
        // resumes too. A hint ABOVE the persisted count lifts
        // the restart to the hint — but probing stays ON (a
        // resume never takes the fresh hint branch: today's
        // network is re-judged).
        val d = StartPlan.decide(hint = 20, cap = 0, resuming = true, persistedThreads = 8)
        assertEquals(20, d.start)
        assertTrue(d.probing)
    }

    @Test
    fun resumeHintBelowThePersistedCountChangesNothing() {
        // The hint is a floor, not a ceiling: a task that was
        // itself running wider than the host's settled optimum
        // restarts at its own persisted count.
        val d = StartPlan.decide(hint = 12, cap = 0, resuming = true, persistedThreads = 48)
        assertEquals(48, d.start)
        assertTrue(d.probing)
        // No hint at all: exactly the pre-B3 resume rule.
        val noHint = StartPlan.decide(hint = 0, cap = 0, resuming = true, persistedThreads = 48)
        assertEquals(48, noHint.start)
        assertTrue(noHint.probing)
        // An out-of-band hint (1) is no hint either.
        assertEquals(
            48,
            StartPlan.decide(hint = 1, cap = 0, resuming = true, persistedThreads = 48).start,
        )
    }

    @Test
    fun resumeHintFloorIsStillPressedByTheCap() {
        // The rate-limit cap presses the resume floor exactly
        // like every other branch — the hint never lifts a
        // start past it.
        val d = StartPlan.decide(hint = 64, cap = 16, resuming = true, persistedThreads = 8)
        assertEquals(16, d.start)
        assertTrue(d.probing)
    }

    @Test
    fun invalidCapIsIgnored() {
        // 0, negative, below MIN_CAP and above MAX_THREADS all
        // fall back to the engine max as the bound.
        for (badCap in listOf(0, -5, 2, 999)) {
            val fresh = StartPlan.decide(hint = 0, cap = badCap, resuming = false, persistedThreads = 0)
            assertEquals("cap=$badCap", StartPlan.BURST_START, fresh.start)
            val hinted = StartPlan.decide(hint = DownloadEngine.MAX_THREADS, cap = badCap, resuming = false, persistedThreads = 0)
            assertEquals("cap=$badCap", DownloadEngine.MAX_THREADS, hinted.start)
        }
    }

    @Test
    fun freshUnknownHostBurstsAt32() {
        // v7.7 (Step B3): the burst was 16 from v4.2 through
        // Step B2 (which kept it while only raising the cap);
        // B3 raised it to 32 on the user's speed-first ruling.
        val d = StartPlan.decide(hint = 0, cap = 0, resuming = false, persistedThreads = 0)
        assertEquals(32, d.start)
        assertEquals(StartPlan.BURST_START, d.start)
        assertTrue(d.probing)
        // Out-of-range hints are no hint at all (v7.7: the legal
        // band tops out at 128 now, so the over-range example is
        // 200 — 100 is a legal hint since the raise).
        assertEquals(32, StartPlan.decide(1, 0, false, 0).start)
        assertEquals(32, StartPlan.decide(200, 0, false, 0).start)
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
        // v7.7: the bound is MAX_THREADS (128) now, so a persisted
        // 100 restarts at 100; only past 128 is pressed down.
        assertEquals(100, StartPlan.decide(0, 0, true, 100).start)
        assertEquals(128, StartPlan.decide(0, 0, true, 200).start)
    }

    @Test
    fun burstStartAndHintBandUnderThe128Top() {
        // v7.7 Step B2 adjudication (superseded in part by B3):
        // B2 kept the burst at 16 while only raising the cap.
        // Step B3, on the user's explicit speed-first ruling,
        // raised the burst to 32 — from there the ladder needs
        // two doublings (32 -> 64 -> 128) to the new top instead
        // of three, and the ramp three adjudications instead of
        // four. The hint band and the cap's press are unchanged.
        val fresh = StartPlan.decide(0, 0, false, 0)
        assertEquals(32, fresh.start)
        assertEquals(StartPlan.BURST_START, fresh.start)
        assertTrue(fresh.probing)
        // A remembered optimum of 128 is inside the legal band
        // now: a fresh start begins AT it, no probing.
        val hinted = StartPlan.decide(128, 0, false, 0)
        assertEquals(128, hinted.start)
        assertFalse(hinted.probing)
        // A remembered rate-limit cap in the widened band
        // (e.g. 100) still bounds that start.
        assertEquals(100, StartPlan.decide(128, 100, false, 0).start)
    }

    @Test
    fun fullWidthOpenSwitchDefaultsToTheShippedV77Shape() {
        // v7.8 (audit 63 Step B2): the A/B switch prepared for
        // the user's same-link regression A/B ships in the
        // v7.7 position — flipping it is a one-constant change
        // once the real-device data lands (see
        // StartPlan.FULL_WIDTH_OPEN).
        assertTrue(StartPlan.FULL_WIDTH_OPEN)
        assertEquals(16, StartPlan.BURST_START_LEGACY)
    }

    @Test
    fun conservativeFormBurstsAtTheLegacy16() {
        // Switch off: a fresh unknown host bursts at the
        // v4.2-v7.6 width of 16 instead of 32; the cap still
        // presses it, and every other branch is unchanged —
        // a remembered optimum still STARTS at its count
        // (only the grant pace changes, in GrowthRamp).
        val d = StartPlan.decide(
            hint = 0, cap = 0, resuming = false, persistedThreads = 0,
            fullWidthOpen = false,
        )
        assertEquals(16, d.start)
        assertTrue(d.probing)
        assertEquals(
            4,
            StartPlan.decide(0, 4, false, 0, fullWidthOpen = false).start,
        )
        val hinted = StartPlan.decide(
            hint = 64, cap = 0, resuming = false, persistedThreads = 0,
            fullWidthOpen = false,
        )
        assertEquals(64, hinted.start)
        assertFalse(hinted.probing)
        // And the on position is exactly the shipped shape.
        assertEquals(
            32,
            StartPlan.decide(0, 0, false, 0, fullWidthOpen = true).start,
        )
    }
}
