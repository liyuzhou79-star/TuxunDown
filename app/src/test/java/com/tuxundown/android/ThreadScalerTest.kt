package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.GrowthRamp
import com.tuxundown.android.core.SpeedSmoothing
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
    fun ladderReaches128WithTheDefaultCeiling() {
        // v7.7: the ladder gains its 128 rung — with the default
        // max (DownloadEngine.MAX_THREADS, now 128) a speed that
        // keeps rising walks the whole ladder and settles there.
        val s = ThreadScaler(4, probing = true)
        val targets = feed(s, 100, 130, 170, 220, 290, 380, 500)
        assertEquals(listOf(8, 16, 32, 64, 128, 128, 128), targets)
        assertFalse(s.probing) // cap reached: ladder done
        assertEquals(DownloadEngine.MAX_THREADS, s.target)
    }

    @Test
    fun evalCadenceIsEveryTickWhileProbingAndEveryTwoInSteadyState() {
        // v7.7 (Step B3): the opening ladder is judged on every
        // 1 s sampler tick, so each doubling waits ~1 s instead
        // of ~2 s; steady state keeps the tuned 2 s cadence the
        // hysteresis constants were built against.
        for (tick in 1..6) {
            assertTrue("probing tick $tick", ThreadScaler.evalDue(tick, probing = true))
        }
        assertTrue(ThreadScaler.evalDue(2, probing = false))
        assertTrue(ThreadScaler.evalDue(4, probing = false))
        assertFalse(ThreadScaler.evalDue(1, probing = false))
        assertFalse(ThreadScaler.evalDue(3, probing = false))
        // From the v7.7 burst (32) at the probing cadence the
        // ladder reaches the 128 top in two evaluations.
        val s = ThreadScaler(32, probing = true)
        assertEquals(64, s.onEval(100, canGrow = true, active = true))
        assertEquals(128, s.onEval(130, canGrow = true, active = true))
        assertFalse(s.probing)
    }

    // ---------- v7.8 (audit 63 Step B2): the 1 s probe window ----------

    /**
     * An engine-faithful pipeline simulation: a server model
     * gives the raw capacity at the LIVE connection count; the
     * raw rate approaches that capacity by [lag] per 1 s tick
     * (handshake + slow-start mean a fresh connection never
     * carries its full rate in its first second); the sampler
     * smooths through the real [SpeedSmoothing] EMA; the real
     * [GrowthRamp] turns the scaler's target into the live
     * count (at most one step per adjudication — the live
     * count can therefore never double within one tick); the
     * scaler is evaluated either on the v7.7 cadence
     * ([ThreadScaler.evalDue]: every tick while probing) or
     * on the pre-v7.7 cadence (every second tick, always).
     * Returns the target the ladder settled at when probing
     * ended.
     */
    private fun probeExitTarget(
        capacityAt: (Int) -> Double,
        lag: Double,
        oneSecondCadence: Boolean,
        noise: DoubleArray? = null,
        ticks: Int = 40,
    ): Int {
        val scaler = ThreadScaler(32, probing = true)
        val ramp = GrowthRamp(scaler.target)
        var live = ramp.granted
        var ema = 0L
        var rawState = 0.0
        var exit: Int? = null
        for (t in 1..ticks) {
            var cap = capacityAt(live)
            if (noise != null) cap *= noise[t % noise.size]
            rawState += (cap - rawState) * lag
            ema = SpeedSmoothing.next(ema, rawState.toLong())
            val due = if (oneSecondCadence) {
                ThreadScaler.evalDue(t, scaler.probing)
            } else {
                t % 2 == 0
            }
            if (due) {
                val wasProbing = scaler.probing
                scaler.onEval(ema, canGrow = true, active = true)
                if (wasProbing && !scaler.probing && exit == null) {
                    exit = scaler.target
                }
                live = ramp.allow(scaler.target)
            }
        }
        return exit ?: scaler.target
    }

    /** Saturating server: total = smax * N / (N + K). The
     *  per-doubling raw gains shrink as N grows. */
    private fun saturating(k: Double): (Int) -> Double =
        { n -> 100_000.0 * n / (n + k) }

    @Test
    fun probeWindowReachesTheTopOnAResponsiveServer() {
        // Per-connection-limited server, line not yet the
        // binding constraint (the kernel.org shape: ~570 KB/s
        // per connection, total still climbing at 64): every
        // doubling pays, so the ladder must reach 128 on the
        // 1 s cadence — and does.
        val responsive: (Int) -> Double =
            { n -> minOf(n * 1000.0, 48_000.0) }
        assertEquals(128, probeExitTarget(responsive, lag = 0.6, oneSecondCadence = true))
        assertEquals(128, probeExitTarget(responsive, lag = 0.6, oneSecondCadence = false))
    }

    @Test
    fun probeWindowSettlesWhereThe2sWindowSettlesOnSaturatingServers() {
        // The audit question: does judging every 1 s park the
        // ladder BELOW where the pre-v7.7 2 s window parked
        // it? Across the saturating family (K = 8 / 16 / 32:
        // per-doubling gains from +33% down to +6%) and raw
        // response lags from realistic (0.6) to the fastest
        // physically modellable (1.0 = raw rate reaches the
        // new capacity within the same tick), the two
        // cadences settle at the SAME rung in every case.
        // The 1 s cadence can only misjudge if the live count
        // doubles inside one tick — GrowthRamp's per-tick
        // step makes that impossible (see evalDue's doc).
        for (k in listOf(8.0, 16.0, 32.0)) {
            for (lag in listOf(0.6, 1.0)) {
                val one = probeExitTarget(saturating(k), lag, oneSecondCadence = true)
                val two = probeExitTarget(saturating(k), lag, oneSecondCadence = false)
                assertEquals("K=$k lag=$lag", two, one)
            }
        }
        // The absolute rungs, pinned so a future change to
        // either cadence or the EMA has to re-argue them:
        // fast raw response settles one rung below the slow
        // one on BOTH cadences alike (an EMA artifact, not a
        // 1 s artifact).
        assertEquals(64, probeExitTarget(saturating(32.0), 1.0, oneSecondCadence = true))
        assertEquals(128, probeExitTarget(saturating(32.0), 0.6, oneSecondCadence = true))
        assertEquals(64, probeExitTarget(saturating(16.0), 1.0, oneSecondCadence = true))
        assertEquals(128, probeExitTarget(saturating(16.0), 0.6, oneSecondCadence = true))
    }

    @Test
    fun probeWindowSurvivesSamplingNoise() {
        // A ±3% deterministic wobble on the raw rate (the
        // sampler's reality on any real line) must not knock
        // the 1 s ladder off the rung the 2 s ladder reaches.
        val noise = doubleArrayOf(
            1.02, 0.99, 1.01, 0.98, 1.03, 1.00,
            0.99, 1.02, 0.97, 1.01, 1.00, 0.99,
        )
        for (k in listOf(16.0, 32.0)) {
            val one = probeExitTarget(
                saturating(k), lag = 0.6, oneSecondCadence = true, noise = noise,
            )
            val two = probeExitTarget(
                saturating(k), lag = 0.6, oneSecondCadence = false, noise = noise,
            )
            assertEquals("K=$k noisy", two, one)
            assertEquals("K=$k noisy", 128, one)
        }
    }

    @Test
    fun boundsAreRespected() {
        assertEquals(ThreadScaler.MIN_TARGET, ThreadScaler(1, false).target)
        // v7.7: the default ceiling is the raised MAX_THREADS (128).
        assertEquals(128, ThreadScaler(200, false).target)
        assertEquals(99, ThreadScaler(99, false).target)
        val s = ThreadScaler(128, probing = false)
        feed(s, 100, 200, 400, 800) // rising forever: still capped
        assertEquals(128, s.target)
        val low = ThreadScaler(2, probing = false)
        feed(low, 100, 100, 100, 100, 100, 100) // flat at the floor
        assertEquals(2, low.target)
    }
}
