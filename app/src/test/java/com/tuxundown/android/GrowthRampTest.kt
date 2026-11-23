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
        // v7.7 (Step B3): the burst width is 32 now (was 16).
        assertEquals(32, GrowthRamp(32).granted)
        assertEquals(StartPlan.BURST_START, GrowthRamp(32).granted)
        assertEquals(GrowthRamp.BASE_GRANT, GrowthRamp(32).granted)
        assertEquals(16, GrowthRamp(16).granted)
    }

    @Test
    fun initialGrantPassesSmallerStartsThrough() {
        assertEquals(8, GrowthRamp(8).granted)
        assertEquals(1, GrowthRamp(1).granted)
        assertEquals(0, GrowthRamp(0).granted)
    }

    @Test
    fun initialGrantCapsALargeDesireAtTheBurstWidth() {
        assertEquals(32, GrowthRamp(64).granted)
        assertEquals(32, GrowthRamp(33).granted)
    }

    @Test
    fun rememberedOptimumOpensFullWidthViaTheBaseGrant() {
        // v7.7 (Step B3): for a remembered-optimum start the
        // engine passes the (already cap- and allowance-pressed)
        // target as the base grant, so the learned count opens
        // whole instead of re-climbing from the burst width.
        assertEquals(64, GrowthRamp(64, baseGrant = 64).granted)
        assertEquals(128, GrowthRamp(128, baseGrant = 128).granted)
        // A base grant below the desire still caps the opening;
        // the rest climbs by the normal slope.
        val ramp = GrowthRamp(128, baseGrant = 100)
        assertEquals(100, ramp.granted)
        assertEquals(128, ramp.allow(128))
        // A negative base grant grants nothing.
        assertEquals(0, GrowthRamp(64, baseGrant = -1).granted)
    }

    @Test
    fun negativeInitialDesireGrantsZero() {
        assertEquals(0, GrowthRamp(-5).granted)
    }

    // ---- growth slope ----

    @Test
    fun growthTo64ClimbsSixteenPerAdjudication() {
        // stepFor(64) = max(8, 64 / 4) = 16; from the v7.7
        // burst grant (32) that is two adjudications.
        val ramp = GrowthRamp(64)
        assertEquals(32, ramp.granted)
        assertEquals(48, ramp.allow(64))
        assertEquals(64, ramp.allow(64))
        assertEquals(64, ramp.allow(64)) // settled: no overshoot
    }

    @Test
    fun growthTo128ClimbsThirtyTwoPerAdjudication() {
        // v7.7: stepFor(128) = max(8, 128 / 4) = 32. Step B2's
        // climb from the old 16 burst took 4 adjudications;
        // Step B3's burst of 32 cuts it to exactly 3.
        assertEquals(32, GrowthRamp.stepFor(128))
        val ramp = GrowthRamp(128)
        assertEquals(32, ramp.granted)
        assertEquals(64, ramp.allow(128))
        assertEquals(96, ramp.allow(128))
        assertEquals(128, ramp.allow(128))
        assertEquals(128, ramp.allow(128)) // settled: no overshoot
    }

    @Test
    fun smallTargetPassesThroughAtTheBurstWidth() {
        // 20 is under the burst width (32): granted whole at
        // construction, no adjudication needed.
        val ramp = GrowthRamp(20)
        assertEquals(20, ramp.granted)
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
        assertEquals(64, ramp.granted)
        assertEquals(10, ramp.allow(10))
    }

    @Test
    fun regrowthAfterADecreaseClimbsFromTheActualValue() {
        val ramp = GrowthRamp(64)
        assertEquals(48, ramp.allow(64))
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
        assertEquals(48, ramp.allow(64))
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
        assertEquals(24, ramp.allow(24)) // under the burst width: whole at once
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
        // unsmoothed base grant and the climb slope. v7.7 Step
        // B3 raised the base grant with the burst (16 -> 32);
        // the slope constants are exactly Step B2's — unchanged.
        assertEquals(32, GrowthRamp.BASE_GRANT)
        assertEquals(8, GrowthRamp.STEP_FLOOR)
        assertEquals(4, GrowthRamp.STEP_DIVISOR)
    }

    @Test
    fun climbAdjudicationCountsMatchTheAuditedBound() {
        // From the base grant (32, v7.7 Step B3): the burst
        // width itself needs no adjudication, 64 takes exactly
        // 2 and the 128 top exactly 3 (Step B2's climb from 16
        // took 4). Every target up to 128 lands within 4; the
        // worst are the integer-division edge near the top
        // (e.g. 127: step 31 walks 63 -> 94 -> 125 -> 127).
        // The segmented path adjudicates once per scaler
        // evaluation — every 1 s while probing (Step B3), every
        // 2 s in steady state — so the opening climb to the top
        // is ~3 s of adjudications once the ladder asks for it.
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
        assertEquals(0, adjudicationsTo(32))
        assertEquals(2, adjudicationsTo(64))
        assertEquals(3, adjudicationsTo(128))
        assertEquals(4, adjudicationsTo(127))
        for (target in 33..128) {
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
