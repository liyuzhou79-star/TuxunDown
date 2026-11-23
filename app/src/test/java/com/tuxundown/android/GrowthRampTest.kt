package com.tuxundown.android

import com.tuxundown.android.core.GrowthRamp
import com.tuxundown.android.core.HlsPlan
import com.tuxundown.android.core.HlsScaler
import com.tuxundown.android.core.StartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.3: the transient connection-growth ramp (see GrowthRamp).
 * Pins the whole rule table: the unsmoothed burst opening,
 * the growth slope, instant decreases, and the proof that the
 * HLS path — wired through the same ramp as depth defence —
 * can never actually be bound by it with current constants.
 */
class GrowthRampTest {

    // ---- initial grant: the v4.2 burst is never smoothed ----

    @Test
    fun initialGrantPassesTheBurstWidthThrough() {
        assertEquals(16, GrowthRamp(16).granted)
        assertEquals(StartPlan.BURST_START, GrowthRamp(16).granted)
        assertEquals(GrowthRamp.BASE_GRANT, GrowthRamp(16).granted)
    }

    @Test
    fun initialGrantPassesSmallerStartsThrough() {
        assertEquals(8, GrowthRamp(8).granted)
        assertEquals(1, GrowthRamp(1).granted)
        assertEquals(0, GrowthRamp(0).granted)
    }

    @Test
    fun initialGrantCapsALargeDesireAtTheBurstWidth() {
        assertEquals(16, GrowthRamp(64).granted)
        assertEquals(16, GrowthRamp(17).granted)
    }

    @Test
    fun negativeInitialDesireGrantsZero() {
        assertEquals(0, GrowthRamp(-5).granted)
    }

    // ---- growth slope ----

    @Test
    fun growthTo64ClimbsSixteenPerAdjudication() {
        // stepFor(64) = max(8, 64 / 4) = 16.
        val ramp = GrowthRamp(64)
        assertEquals(16, ramp.granted)
        assertEquals(32, ramp.allow(64))
        assertEquals(48, ramp.allow(64))
        assertEquals(64, ramp.allow(64))
        assertEquals(64, ramp.allow(64)) // settled: no overshoot
    }

    @Test
    fun smallTargetIsReachedInOneStep() {
        // stepFor(20) = max(8, 20 / 4) = 8: 16 + 8 >= 20.
        val ramp = GrowthRamp(20)
        assertEquals(16, ramp.granted)
        assertEquals(20, ramp.allow(20))
    }

    @Test
    fun stepFloorReachesSmallTargetsFromZero() {
        val ramp = GrowthRamp(0)
        assertEquals(8, ramp.allow(12)) // step max(8, 3) = 8
        assertEquals(12, ramp.allow(12))
    }

    @Test
    fun stepForMatchesTheRuleTable() {
        assertEquals(8, GrowthRamp.stepFor(0))
        assertEquals(8, GrowthRamp.stepFor(20))
        assertEquals(8, GrowthRamp.stepFor(32))
        assertEquals(9, GrowthRamp.stepFor(36))
        assertEquals(16, GrowthRamp.stepFor(64))
    }

    // ---- decreases are instant, never smoothed ----

    @Test
    fun decreaseAppliesImmediatelyInOneStep() {
        val ramp = GrowthRamp(64)
        ramp.allow(64)
        ramp.allow(64)
        assertEquals(48, ramp.granted)
        assertEquals(10, ramp.allow(10))
    }

    @Test
    fun regrowthAfterADecreaseClimbsFromTheActualValue() {
        val ramp = GrowthRamp(64)
        ramp.allow(64)
        ramp.allow(64)
        assertEquals(48, ramp.granted)
        assertEquals(10, ramp.allow(10))
        // stepFor(48) = max(8, 12) = 12, applied to the ACTUAL
        // granted (10), not the pre-decrease peak.
        assertEquals(22, ramp.allow(48))
    }

    @Test
    fun capCollapseShapeLandsImmediately() {
        // The global cap collapses mid-climb (more tasks joined):
        // the desired count falls from 64 to 12 and the grant
        // must land on 12 at once, with no intermediate value.
        val ramp = GrowthRamp(64)
        assertEquals(32, ramp.allow(64))
        assertEquals(12, ramp.allow(12))
        assertEquals(12, ramp.granted)
    }

    @Test
    fun desiredZeroOrNegativePullsTheGrantToZero() {
        val ramp = GrowthRamp(64)
        ramp.allow(64)
        assertEquals(0, ramp.allow(0))
        val ramp2 = GrowthRamp(64)
        ramp2.allow(64)
        assertEquals(0, ramp2.allow(-3))
        assertTrue(ramp2.granted >= 0)
    }

    @Test
    fun holdingAtTheDesiredCountIsStable() {
        val ramp = GrowthRamp(24)
        assertEquals(24, ramp.allow(24)) // 16 + step 8 reaches it
        repeat(5) { assertEquals(24, ramp.allow(24)) }
    }

    // ---- HLS shape: with current constants the ramp never binds ----

    @Test
    fun hlsGrowthShapeNeverBindsTheRamp() {
        // Feed the ramp exactly what runHls feeds it: once per
        // 1 s tick, HlsPlan.gate(scalerWidth, allowance) with an
        // uncapped allowance, the scaler growing at its FASTEST
        // (writer starved every tick: +2 per GROW_STREAK_TICKS).
        // The scaler's step (2) is far below the ramp's minimum
        // step (8), so every adjudication must return the gated
        // width unchanged — the wiring is depth defence only.
        val plan = HlsPlan.plan(hint = 0, cap = 0, totalSegments = 100)
        assertEquals(8, plan.startWidth)
        val scaler = HlsScaler(plan.startWidth, plan.maxWidth)
        val ramp = GrowthRamp(plan.startWidth)
        assertEquals(plan.startWidth, ramp.granted)
        repeat(40) {
            // Speed rises every tick so each grow passes its
            // verdict (a flat speed would have the scaler hand
            // the grow back — also fine for the ramp, but then
            // the width would never reach the max).
            scaler.onTick(
                speedBps = 1_000_000L + it * 1_000_000L,
                writerStarved = true,
                retryFailures = 0,
            )
            val gated = HlsPlan.gate(scaler.width, Int.MAX_VALUE)
            assertEquals("tick $it, scaler width ${scaler.width}", gated, ramp.allow(gated))
        }
        assertEquals(plan.maxWidth, scaler.width)
    }

    @Test
    fun hlsAtMaxStartWidthIsIdentity() {
        val scaler = HlsScaler(16, 16)
        val ramp = GrowthRamp(16)
        repeat(10) {
            scaler.onTick(
                speedBps = 10_000_000,
                writerStarved = true,
                retryFailures = 0,
            )
            val gated = HlsPlan.gate(scaler.width, Int.MAX_VALUE)
            assertEquals(16, gated)
            assertEquals(gated, ramp.allow(gated))
        }
    }

    // ---- v5.9 step B: the audit-44 reallocation bound, pinned ----

    @Test
    fun rampConstantsMatchTheAuditedBound() {
        // The numbers step A's bound arithmetic rests on: the
        // unsmoothed base grant and the climb slope.
        assertEquals(16, GrowthRamp.BASE_GRANT)
        assertEquals(8, GrowthRamp.STEP_FLOOR)
        assertEquals(4, GrowthRamp.STEP_DIVISOR)
    }

    @Test
    fun climbAdjudicationCountsMatchTheAuditedBound() {
        // From the base grant (16): 16 -> 32 takes exactly 2
        // adjudications and 16 -> 64 exactly 3 — the freed-
        // share cases step A quantified (the segmented path
        // adjudicates once per 2 s scaler tick, so ~2 s
        // re-read wait + ~6 s climb = the ~8 s bound). Every
        // other target up to 64 lands within 4; the three
        // 4-step targets are the integer-division edge where
        // 16 + 3 * (target / 4) falls one short of the target,
        // so the absolute worst climb is 4 adjudications
        // (~10 s with the re-read wait) — a refinement of the
        // class doc's "at most 3", recorded in CHECKPOINT.
        fun adjudicationsTo(target: Int): Int {
            val ramp = GrowthRamp(target)
            var n = 0
            while (ramp.granted < target) {
                ramp.allow(target)
                n++
                assertTrue("climb to $target must terminate", n <= 10)
            }
            return n
        }
        assertEquals(2, adjudicationsTo(32))
        assertEquals(3, adjudicationsTo(64))
        assertEquals(4, adjudicationsTo(59))
        assertEquals(4, adjudicationsTo(62))
        assertEquals(4, adjudicationsTo(63))
        for (target in 17..64) {
            val n = adjudicationsTo(target)
            assertTrue("target $target took $n adjudications", n <= 4)
        }
    }

    @Test
    fun hlsShrinkShapeAlsoPassesThrough() {
        // Failures shrink the scaler by 2 per tick; decreases
        // are instant in the ramp, so those pass through too.
        val scaler = HlsScaler(16, 16)
        val ramp = GrowthRamp(16)
        repeat(8) {
            scaler.onTick(
                speedBps = 1_000_000,
                writerStarved = false,
                retryFailures = 1,
            )
            val gated = HlsPlan.gate(scaler.width, Int.MAX_VALUE)
            assertEquals(gated, ramp.allow(gated))
        }
        assertEquals(1, scaler.width)
    }
}
