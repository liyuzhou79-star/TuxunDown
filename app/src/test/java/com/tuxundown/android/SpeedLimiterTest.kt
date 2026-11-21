package com.tuxundown.android

import com.tuxundown.android.core.SpeedLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedLimiterTest {

    /** Fake clock that only advances when the limiter sleeps. */
    private class FakeClock {
        var nowNanos = 0L
        var sleptMs = 0L
        val clock: () -> Long = { nowNanos }
        val sleep: (Long) -> Unit = { ms ->
            sleptMs += ms
            nowNanos += ms * 1_000_000L
        }
    }

    @Test
    fun unlimitedNeverSleeps() {
        val fc = FakeClock()
        val limiter = SpeedLimiter(rateBps = { 0L }, clock = fc.clock, sleep = fc.sleep)
        limiter.throttle(1_000_000)
        assertEquals(0L, fc.sleptMs)
    }

    @Test
    fun pacesBytesAtConfiguredRate() {
        val fc = FakeClock()
        val limiter = SpeedLimiter(rateBps = { 1000L }, clock = fc.clock, sleep = fc.sleep)
        limiter.throttle(1000) // 1000 bytes at 1000 B/s = 1 s of pacing
        assertEquals(1000L, fc.sleptMs)
        limiter.throttle(500)
        assertEquals(1500L, fc.sleptMs)
    }

    @Test
    fun idleTimeDoesNotCreateDebt() {
        val fc = FakeClock()
        val limiter = SpeedLimiter(rateBps = { 1000L }, clock = fc.clock, sleep = fc.sleep)
        limiter.throttle(1000)
        // Simulate a long idle gap (task paused for a minute): the
        // next bytes must not be charged for the idle time.
        fc.nowNanos += 60_000_000_000L
        val before = fc.sleptMs
        limiter.throttle(1000)
        assertEquals(1000L, fc.sleptMs - before)
    }
}
