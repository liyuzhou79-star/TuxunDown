package com.tuxundown.android

import com.tuxundown.android.core.SpeedTestMath
import com.tuxundown.android.core.SpeedVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.8: speed-test math. */
class SpeedTestMathTest {

    @Test
    fun avgBpsBasics() {
        assertEquals(1000, SpeedTestMath.avgBps(1000, 1000))
        assertEquals(2000, SpeedTestMath.avgBps(1000, 500))
        assertEquals(0, SpeedTestMath.avgBps(0, 1000))
        assertEquals(0, SpeedTestMath.avgBps(1000, 0))
        assertEquals(0, SpeedTestMath.avgBps(-5, 1000))
    }

    @Test
    fun emaBlendsThreeToOne() {
        assertEquals(500, SpeedTestMath.ema(0, 500)) // first sample passes through
        assertEquals(250, SpeedTestMath.ema(100, 700)) // (100*3 + 700) / 4
    }

    // ---- v4.0: steady-state window ----

    @Test
    fun steadyBpsDiscardsRampAndReturnsSteadyRate() {
        // Ramp: 200_000 B/s for the first 1.5 s (300_000 bytes).
        // Steady: 1_000_000 B/s afterwards (500_000 per 500 ms).
        val samples = listOf(
            0L to 0L,
            500L to 100_000L,
            1000L to 200_000L,
            1500L to 300_000L,
            2000L to 800_000L,
            2500L to 1_300_000L,
            3000L to 1_800_000L,
            3500L to 2_300_000L,
            4000L to 2_800_000L,
        )
        // Kept window: (1500, 300_000)..(4000, 2_800_000)
        // = 2_500_000 bytes / 2500 ms = 1_000_000 B/s exactly.
        assertEquals(1_000_000L, SpeedTestMath.steadyBps(samples, 1500))
        assertEquals(2500L, SpeedTestMath.steadyWindowMs(samples, 1500))
    }

    @Test
    fun steadyBpsFallsBackToWholeAverageWhenTooShort() {
        // Total 1000 ms < warmup (1500) + 1000: no usable kept
        // window, so the whole range is averaged.
        val samples = listOf(
            0L to 0L,
            500L to 250_000L,
            1000L to 500_000L,
        )
        assertEquals(500_000L, SpeedTestMath.steadyBps(samples, 1500))
        assertEquals(1000L, SpeedTestMath.steadyWindowMs(samples, 1500))
    }

    @Test
    fun steadyBpsEmptyOrSingleSampleIsZero() {
        assertEquals(0L, SpeedTestMath.steadyBps(emptyList(), 1500))
        assertEquals(0L, SpeedTestMath.steadyBps(listOf(0L to 0L), 1500))
        assertEquals(0L, SpeedTestMath.steadyBps(listOf(0L to 1000L), 1500))
        assertEquals(0L, SpeedTestMath.steadyWindowMs(emptyList(), 1500))
        assertEquals(0L, SpeedTestMath.steadyWindowMs(listOf(0L to 0L), 1500))
    }

    @Test
    fun steadyBpsNoByteGrowthIsZero() {
        val samples = listOf(
            0L to 1000L,
            2000L to 1000L,
            4000L to 1000L,
        )
        assertEquals(0L, SpeedTestMath.steadyBps(samples, 1500))
    }

    // ---- v4.0: three-way verdict, boundaries pinned ----

    @Test
    fun verdictBoundaries() {
        val single = 100_000L
        assertEquals(
            SpeedVerdict.Verdict.FASTER,
            SpeedVerdict.verdict(single, 115_000L), // exactly 115/100
        )
        assertEquals(
            SpeedVerdict.Verdict.SIMILAR,
            SpeedVerdict.verdict(single, 114_000L), // 114/100
        )
        assertEquals(
            SpeedVerdict.Verdict.SIMILAR,
            SpeedVerdict.verdict(single, 85_000L), // exactly 85/100
        )
        assertEquals(
            SpeedVerdict.Verdict.SLOWER,
            SpeedVerdict.verdict(single, 84_000L), // 84/100
        )
        assertEquals(
            SpeedVerdict.Verdict.SIMILAR,
            SpeedVerdict.verdict(single, single), // equal
        )
    }

    @Test
    fun verdictNullWhenEitherIsZero() {
        assertNull(SpeedVerdict.verdict(0, 100_000L))
        assertNull(SpeedVerdict.verdict(100_000L, 0))
        assertNull(SpeedVerdict.verdict(0, 0))
    }
}
