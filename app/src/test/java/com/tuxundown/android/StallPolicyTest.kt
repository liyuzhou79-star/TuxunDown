package com.tuxundown.android

import com.tuxundown.android.core.StallPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.6 (audit round 31 step B): the speed-aware stall-eviction
 * boundary table. Patience is 12 s at/below 256 KiB/s, 4 s
 * at/above 4 MiB/s, linear between; equality with the threshold
 * never evicts; a lone connection is immune; a task that is not
 * moving at all never evicts.
 */
class StallPolicyTest {

    private val low = StallPolicy.LOW_SPEED_BPS // 256 KiB/s
    private val high = StallPolicy.HIGH_SPEED_BPS // 4 MiB/s

    // ---------- thresholdMs: the boundary table ----------

    @Test
    fun zeroAndNegativeSpeedGetMaxPatience() {
        assertEquals(12_000L, StallPolicy.thresholdMs(0))
        assertEquals(12_000L, StallPolicy.thresholdMs(-1))
        assertEquals(12_000L, StallPolicy.thresholdMs(Long.MIN_VALUE))
    }

    @Test
    fun atAndBelowLowSpeedThresholdIsCappedAtMax() {
        assertEquals(12_000L, StallPolicy.thresholdMs(1))
        assertEquals(12_000L, StallPolicy.thresholdMs(low - 1))
        assertEquals(12_000L, StallPolicy.thresholdMs(low))
        // Past the low edge the descent has begun (integer
        // interpolation: a full KiB/s past the edge drops 2 ms).
        assertTrue(StallPolicy.thresholdMs(low + 1024) < 12_000L)
    }

    @Test
    fun interpolationIsLinear() {
        // 1 MiB/s is 1/5 of the way from 256 KiB/s to 4 MiB/s:
        // drop = 8000 / 5 = 1600.
        assertEquals(10_400L, StallPolicy.thresholdMs(1024L * 1024))
        // The exact midpoint (2 MiB/s + 128 KiB/s) drops half.
        assertEquals(8_000L, StallPolicy.thresholdMs((low + high) / 2))
    }

    @Test
    fun atAndAboveHighSpeedThresholdIsTheFloor() {
        // One byte/s below the high edge is one step above floor.
        assertEquals(4_001L, StallPolicy.thresholdMs(high - 1))
        assertEquals(4_000L, StallPolicy.thresholdMs(high))
        assertEquals(4_000L, StallPolicy.thresholdMs(high * 4))
        assertEquals(4_000L, StallPolicy.thresholdMs(Long.MAX_VALUE))
    }

    @Test
    fun thresholdIsMonotonicNonIncreasingAndBounded() {
        var prev = Long.MAX_VALUE
        var speed = 0L
        while (speed <= 16L * 1024 * 1024) {
            val t = StallPolicy.thresholdMs(speed)
            assertTrue("threshold $t at $speed above max", t <= 12_000L)
            assertTrue("threshold $t at $speed below floor", t >= 4_000L)
            assertTrue("threshold rose at $speed: $prev -> $t", t <= prev)
            prev = t
            speed += 64L * 1024
        }
    }

    // ---------- shouldEvict: immunity and the equality rule ----------

    @Test
    fun singleConnectionIsNeverEvicted() {
        // However fast the task, however long the stall: the only
        // connection is the whole task (30 s read timeout is the
        // backstop for a truly dead one).
        assertFalse(StallPolicy.shouldEvict(high * 2, 3_600_000L, 1))
        assertFalse(StallPolicy.shouldEvict(low, 3_600_000L, 1))
        assertFalse(StallPolicy.shouldEvict(high * 2, 3_600_000L, 0))
    }

    @Test
    fun aTaskThatIsNotMovingNeverEvicts() {
        // Total stall = systemic cause; churning every connection
        // buys nothing.
        assertFalse(StallPolicy.shouldEvict(0, 3_600_000L, 8))
        assertFalse(StallPolicy.shouldEvict(-5, 3_600_000L, 8))
    }

    @Test
    fun stallExactlyAtThresholdDoesNotEvict() {
        // Low speed: threshold 12_000 — equality holds, +1 evicts.
        assertFalse(StallPolicy.shouldEvict(low, 12_000L, 4))
        assertTrue(StallPolicy.shouldEvict(low, 12_001L, 4))
        // Mid speed: threshold 8_000.
        assertFalse(StallPolicy.shouldEvict((low + high) / 2, 8_000L, 4))
        assertTrue(StallPolicy.shouldEvict((low + high) / 2, 8_001L, 4))
        // Floor: threshold 4_000.
        assertFalse(StallPolicy.shouldEvict(high, 4_000L, 4))
        assertTrue(StallPolicy.shouldEvict(high, 4_001L, 4))
    }

    @Test
    fun higherSpeedEvictsSoonerForTheSameStall() {
        // A 6 s stall is tolerated at low speed, fatal at high.
        assertFalse(StallPolicy.shouldEvict(low, 6_000L, 4))
        assertTrue(StallPolicy.shouldEvict(high, 6_000L, 4))
        // A 10 s stall is tolerated only at the low end.
        assertFalse(StallPolicy.shouldEvict(low, 10_000L, 4))
        assertTrue(StallPolicy.shouldEvict(1024L * 1024, 10_401L, 4))
        assertFalse(StallPolicy.shouldEvict(1024L * 1024, 10_400L, 4))
    }

    @Test
    fun noStallNoEviction() {
        assertFalse(StallPolicy.shouldEvict(high, 0L, 4))
        assertFalse(StallPolicy.shouldEvict(high, -1_000L, 4))
    }
}
