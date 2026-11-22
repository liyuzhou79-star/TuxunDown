package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.HlsPlan
import com.tuxundown.android.core.HlsScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.3: the pure HLS width decisions — the fixed plan (start +
 * max from hint / cap / segment count), the runtime allowance
 * gate, and the steady-state scaler's grow / hold / shrink
 * verdicts. The allowance is a runtime gate only: it is not an
 * input to the plan, which these tests pin by construction
 * (plan() has no allowance parameter) and by the gate tests.
 */
class HlsPlanTest {

    // ---- plan: start width ----

    @Test
    fun unknownHostStartsAtTheDefault() {
        val p = HlsPlan.plan(hint = 0, cap = 0, totalSegments = 100)
        assertEquals(HlsPlan.DEFAULT_START, p.startWidth)
        assertEquals(8, p.startWidth)
        assertEquals(DownloadEngine.HLS_MAX_PARALLEL, p.maxWidth)
        assertEquals(16, p.maxWidth)
    }

    @Test
    fun rememberedHintStartsAtTheHint() {
        val p = HlsPlan.plan(hint = 12, cap = 0, totalSegments = 100)
        assertEquals(12, p.startWidth)
        assertEquals(16, p.maxWidth)
    }

    @Test
    fun hintAboveTheMaxIsClampedToTheMax() {
        // The HostMemory optimum is a SEGMENTED count (up to 64);
        // reused as an HLS hint it must land inside the HLS band.
        val p = HlsPlan.plan(hint = 64, cap = 0, totalSegments = 100)
        assertEquals(16, p.startWidth)
        assertEquals(16, p.maxWidth)
    }

    @Test
    fun tinyHintIsRaisedToTheStartFloor() {
        val p = HlsPlan.plan(hint = 1, cap = 0, totalSegments = 100)
        assertEquals(HlsPlan.MIN_START, p.startWidth)
        assertEquals(2, p.startWidth)
    }

    // ---- plan: the cap bounds the max (and therefore the start) ----

    @Test
    fun capBoundsTheMaxWidth() {
        val p = HlsPlan.plan(hint = 0, cap = 6, totalSegments = 100)
        assertEquals(6, p.maxWidth)
        assertEquals(6, p.startWidth) // default 8 pressed by the max
    }

    @Test
    fun capPressesAHintStartToo() {
        val p = HlsPlan.plan(hint = 12, cap = 4, totalSegments = 100)
        assertEquals(4, p.maxWidth)
        assertEquals(4, p.startWidth)
    }

    @Test
    fun capAboveTheMaxChangesNothing() {
        val p = HlsPlan.plan(hint = 0, cap = 64, totalSegments = 100)
        assertEquals(16, p.maxWidth)
        assertEquals(8, p.startWidth)
    }

    // ---- plan: fewer segments than the width ----

    @Test
    fun widthNeverExceedsTheSegmentCount() {
        val p = HlsPlan.plan(hint = 0, cap = 0, totalSegments = 3)
        assertEquals(3, p.maxWidth)
        assertEquals(3, p.startWidth)
    }

    @Test
    fun singleSegmentPlaylistIsWidthOne() {
        val p = HlsPlan.plan(hint = 12, cap = 0, totalSegments = 1)
        assertEquals(1, p.maxWidth)
        assertEquals(1, p.startWidth)
    }

    @Test
    fun emptyPlaylistStillYieldsAUsablePlan() {
        // runHls refuses an empty playlist before planning, but
        // the pure function must not blow up on it either.
        val p = HlsPlan.plan(hint = 0, cap = 0, totalSegments = 0)
        assertEquals(1, p.maxWidth)
        assertEquals(1, p.startWidth)
    }

    @Test
    fun startNeverExceedsMaxOnAnyBranch() {
        for (hint in listOf(0, 1, 2, 8, 16, 64)) {
            for (cap in listOf(0, 1, 4, 16, 64)) {
                for (total in listOf(1, 2, 5, 100)) {
                    val p = HlsPlan.plan(hint, cap, total)
                    assertTrue(
                        "hint=$hint cap=$cap total=$total -> $p",
                        p.startWidth in 1..p.maxWidth &&
                            p.maxWidth <= DownloadEngine.HLS_MAX_PARALLEL,
                    )
                }
            }
        }
    }

    // ---- gate: the allowance is a runtime clamp, never a plan input ----

    @Test
    fun gatePassesTheWidthThroughWhenUncapped() {
        assertEquals(8, HlsPlan.gate(scalerWidth = 8, allowance = Int.MAX_VALUE))
    }

    @Test
    fun gateClampsToTheAllowance() {
        assertEquals(3, HlsPlan.gate(scalerWidth = 8, allowance = 3))
    }

    @Test
    fun gateNeverClosesOnTheWriteCursor() {
        // An allowance of 0 must still leave width 1, or the
        // segment at the write cursor could never be submitted
        // and the run could never finish to free its slot.
        assertEquals(1, HlsPlan.gate(scalerWidth = 8, allowance = 0))
        assertEquals(1, HlsPlan.gate(scalerWidth = 1, allowance = 0))
    }

    // ---- scaler: hold ----

    @Test
    fun scalerHoldsWhenTheWriterNeverStarves() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        repeat(10) {
            assertEquals(8, s.onTick(speedBps = 1_000_000, writerStarved = false, retryFailures = 0))
        }
    }

    @Test
    fun aSingleStarvedTickDoesNotGrow() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        assertEquals(8, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        // A non-starved tick resets the streak, so starved ticks
        // must be CONSECUTIVE to grow.
        assertEquals(8, s.onTick(1_000_000, writerStarved = false, retryFailures = 0))
        assertEquals(8, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
    }

    // ---- scaler: grow + verdict ----

    @Test
    fun consecutiveStarvedTicksGrowByTheStep() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        assertEquals(8, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        assertEquals(10, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
    }

    @Test
    fun growIsKeptWhenTheSpeedPaysForIt() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        s.onTick(1_000_000, writerStarved = true, retryFailures = 0)
        assertEquals(10, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        // Verdict window: two ticks, then speed +20% keeps it.
        assertEquals(10, s.onTick(1_100_000, writerStarved = false, retryFailures = 0))
        assertEquals(10, s.onTick(1_200_000, writerStarved = false, retryFailures = 0))
        assertEquals(10, s.width)
    }

    @Test
    fun growIsUndoneWhenTheSpeedStaysFlat() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        s.onTick(1_000_000, writerStarved = true, retryFailures = 0)
        assertEquals(10, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        assertEquals(10, s.onTick(1_000_000, writerStarved = false, retryFailures = 0))
        // Flat speed at the verdict: the grow is handed back.
        assertEquals(8, s.onTick(1_050_000, writerStarved = false, retryFailures = 0))
    }

    @Test
    fun scalerNeverGrowsPastTheMax() {
        val s = HlsScaler(startWidth = 15, maxWidth = 16)
        s.onTick(1_000_000, writerStarved = true, retryFailures = 0)
        assertEquals(16, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        // Settle the verdict with a paying speed, then keep
        // starving: the width stays at the max.
        s.onTick(2_000_000, writerStarved = true, retryFailures = 0)
        s.onTick(2_000_000, writerStarved = true, retryFailures = 0)
        repeat(4) {
            assertEquals(16, s.onTick(2_000_000, writerStarved = true, retryFailures = 0))
        }
    }

    // ---- scaler: shrink on failures ----

    @Test
    fun retryFailuresShrinkImmediately() {
        val s = HlsScaler(startWidth = 10, maxWidth = 16)
        assertEquals(8, s.onTick(1_000_000, writerStarved = false, retryFailures = 1))
        assertEquals(6, s.onTick(1_000_000, writerStarved = true, retryFailures = 3))
    }

    @Test
    fun shrinkFloorsAtOne() {
        val s = HlsScaler(startWidth = 2, maxWidth = 16)
        assertEquals(1, s.onTick(1_000_000, writerStarved = false, retryFailures = 1))
        assertEquals(1, s.onTick(1_000_000, writerStarved = false, retryFailures = 1))
    }

    @Test
    fun aFailureCancelsAPendingGrowVerdict() {
        val s = HlsScaler(startWidth = 8, maxWidth = 16)
        s.onTick(1_000_000, writerStarved = true, retryFailures = 0)
        assertEquals(10, s.onTick(1_000_000, writerStarved = true, retryFailures = 0))
        // Failure during the verdict window: shrink now, and the
        // cancelled verdict must not shrink a second time later.
        assertEquals(8, s.onTick(1_000_000, writerStarved = false, retryFailures = 1))
        assertEquals(8, s.onTick(1_000_000, writerStarved = false, retryFailures = 0))
        assertEquals(8, s.onTick(1_000_000, writerStarved = false, retryFailures = 0))
    }

    @Test
    fun startWidthIsClampedIntoRange() {
        assertEquals(16, HlsScaler(startWidth = 99, maxWidth = 16).width)
        assertEquals(1, HlsScaler(startWidth = 0, maxWidth = 16).width)
        assertEquals(3, HlsScaler(startWidth = 8, maxWidth = 3).width)
    }
}
