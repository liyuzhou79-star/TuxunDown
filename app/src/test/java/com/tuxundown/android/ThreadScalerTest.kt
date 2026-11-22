package com.tuxundown.android

import com.tuxundown.android.core.ThreadScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3: the dynamic thread scaler, driven by scripted speed
 * series (one onEval per 2 s evaluation tick, speeds in
 * arbitrary units — only ratios matter).
 */
class ThreadScalerTest {

    private fun feed(s: ThreadScaler, vararg speeds: Long): List<Int> =
        speeds.map { s.onEval(it, canGrow = true, active = true) }

    @Test
    fun ladderDoublesWhileSpeedRises() {
        // v4.2: the ladder now climbs to the raised 64 ceiling.
        val s = ThreadScaler(4, probing = true, maxThreads = 64)
        val targets = feed(s, 100, 130, 170, 220, 290)
        assertEquals(listOf(8, 16, 32, 64, 64), targets)
        assertFalse(s.probing) // cap reached: ladder done
    }

    @Test
    fun ladderSettlesAtFirstPlateau() {
        val s = ThreadScaler(4, probing = true)
        assertEquals(8, s.onEval(100, canGrow = true, active = true))
        // +5% is not >10%: the ladder stops and keeps the count.
        assertEquals(8, s.onEval(105, canGrow = true, active = true))
        assertFalse(s.probing)
    }

    @Test
    fun ladderNeedsFreshChunksToGrow() {
        val s = ThreadScaler(4, probing = true)
        // Endgame from the start: no fresh chunks, no growth.
        assertEquals(4, s.onEval(100, canGrow = false, active = true))
        assertFalse(s.probing)
    }

    @Test
    fun steadyGrowNeedsTwoConfirmations() {
        val s = ThreadScaler(8, probing = false)
        assertEquals(8, s.onEval(100, true, true)) // baseline only
        assertEquals(8, s.onEval(120, true, true)) // one signal: hold
        assertEquals(12, s.onEval(140, true, true)) // second: grow
    }

    @Test
    fun failedGrowRevertsAndLearnsCeiling() {
        val s = ThreadScaler(8, probing = false)
        feed(s, 100, 130, 160) // grow fires on the third: 8 -> 12
        assertEquals(12, s.target)
        // The added connections bought nothing (speed flat at the
        // level that triggered the grow): two strikes -> revert.
        assertEquals(12, s.onEval(160, true, true)) // strike 1
        assertEquals(8, s.onEval(158, true, true)) // strike 2: revert
        assertEquals(12, s.learnedCeiling)
    }

    @Test
    fun learnedCeilingCapsLaterGrowth() {
        val s = ThreadScaler(8, probing = false)
        feed(s, 100, 130, 160, 160, 158) // grow to 12, fail, revert
        assertEquals(8, s.target)
        // The network improves later: growth is allowed again but
        // never past the learned ceiling of 12.
        feed(s, 200, 230) // two strong signals -> grow to 12
        assertEquals(12, s.target)
        feed(s, 300, 360, 430, 520, 620)
        assertEquals(12, s.target)
    }

    @Test
    fun flatLineProbesDown() {
        val s = ThreadScaler(16, probing = false)
        assertEquals(16, s.onEval(100, true, true)) // baseline
        assertEquals(16, s.onEval(100, true, true)) // flat 1
        assertEquals(16, s.onEval(101, true, true)) // flat 2
        assertEquals(12, s.onEval(99, true, true)) // flat 3: shrink
        // The shrink is judged over the next two evaluations; a
        // held speed accepts it (no snap back).
        assertEquals(12, s.onEval(100, true, true))
        assertEquals(12, s.onEval(100, true, true))
    }

    @Test
    fun harmfulShrinkRestoresAndLearnsFloor() {
        val s = ThreadScaler(16, probing = false)
        feed(s, 100, 100, 101, 99) // probe-down to 12
        assertEquals(12, s.target)
        // Speed falls off a cliff right after: the shrink hurt.
        assertEquals(16, s.onEval(79, true, true))
        assertEquals(16, s.learnedFloor)
        // A flat line afterwards must never shrink below the floor.
        repeat(10) { s.onEval(100, true, true) }
        assertEquals(16, s.target)
    }

    @Test
    fun decliningSpeedShrinks() {
        val s = ThreadScaler(16, probing = false)
        assertEquals(16, s.onEval(200, true, true))
        assertEquals(16, s.onEval(170, true, true)) // -15%: signal 1
        assertEquals(12, s.onEval(145, true, true)) // -15%: shrink
    }

    @Test
    fun singleBlipsChangeNothing() {
        val s = ThreadScaler(8, probing = false)
        // A growth blip and a decline blip, each isolated between
        // flat evaluations so no signal ever confirms twice.
        val targets = feed(s, 100, 130, 100, 100, 80, 100, 100)
        assertTrue(targets.all { it == 8 })
    }

    @Test
    fun zeroSpeedIsNoInformation() {
        val s = ThreadScaler(8, probing = false)
        feed(s, 100, 130)
        assertEquals(8, s.onEval(0, true, true)) // stall sample
        // The earlier single grow signal must not survive the gap.
        assertEquals(8, s.onEval(160, true, true))
    }

    @Test
    fun inactiveHoldsEverything() {
        val s = ThreadScaler(4, probing = true)
        assertEquals(4, s.onEval(100, canGrow = true, active = false))
        assertTrue(s.probing) // untouched while no work remains
    }

    @Test
    fun boundsAreRespected() {
        assertEquals(ThreadScaler.MIN_TARGET, ThreadScaler(1, false).target)
        // v4.2: the default ceiling is the raised MAX_THREADS (64).
        assertEquals(64, ThreadScaler(99, false).target)
        val s = ThreadScaler(64, probing = false)
        feed(s, 100, 200, 400, 800) // rising forever: still capped
        assertEquals(64, s.target)
        val low = ThreadScaler(2, probing = false)
        feed(low, 100, 100, 100, 100, 100, 100) // flat at the floor
        assertEquals(2, low.target)
    }
}
