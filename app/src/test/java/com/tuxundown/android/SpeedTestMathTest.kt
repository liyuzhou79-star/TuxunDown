package com.tuxundown.android

import com.tuxundown.android.core.SpeedTestMath
import org.junit.Assert.assertEquals
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
}
