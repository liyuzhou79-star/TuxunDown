package com.tuxundown.android

import com.tuxundown.android.core.StallPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.6 (audit round 31 step B): the speed-aware stall-eviction
 * boundary table. Patience is 12 s at/below 256 KiB/s, 3 s
 * at/above 4 MiB/s (v7.7 Step B3: the floor was 4 s through
 * v7.6; the low-speed end is unchanged), linear between;
 * equality with the threshold never evicts; a lone connection
 * is immune; a task that is not moving at all never evicts.
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
        // drop = 9000 / 5 = 1800 (v7.7 Step B3: the drop span
        // widened from 8000 to 9000 with the 3 s floor).
        assertEquals(10_200L, StallPolicy.thresholdMs(1024L * 1024))
        // The exact midpoint (2 MiB/s + 128 KiB/s) drops half.
        assertEquals(7_500L, StallPolicy.thresholdMs((low + high) / 2))
    }

    @Test
    fun atAndAboveHighSpeedThresholdIsTheFloor() {
        // One byte/s below the high edge is one step above floor.
        assertEquals(3_001L, StallPolicy.thresholdMs(high - 1))
        assertEquals(3_000L, StallPolicy.thresholdMs(high))
        assertEquals(3_000L, StallPolicy.thresholdMs(high * 4))
        assertEquals(3_000L, StallPolicy.thresholdMs(Long.MAX_VALUE))
        assertEquals(3_000L, StallPolicy.MIN_THRESHOLD_MS)
    }

    @Test
    fun thresholdIsMonotonicNonIncreasingAndBounded() {
        var prev = Long.MAX_VALUE
        var speed = 0L
        while (speed <= 16L * 1024 * 1024) {
            val t = StallPolicy.thresholdMs(speed)
            assertTrue("threshold $t at $speed above max", t <= 12_000L)
            assertTrue("threshold $t at $speed below floor", t >= 3_000L)
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
        // Mid speed: threshold 7_500.
        assertFalse(StallPolicy.shouldEvict((low + high) / 2, 7_500L, 4))
        assertTrue(StallPolicy.shouldEvict((low + high) / 2, 7_501L, 4))
        // Floor: threshold 3_000 (v7.7 Step B3, was 4_000).
        assertFalse(StallPolicy.shouldEvict(high, 3_000L, 4))
        assertTrue(StallPolicy.shouldEvict(high, 3_001L, 4))
    }

    @Test
    fun higherSpeedEvictsSoonerForTheSameStall() {
        // A 6 s stall is tolerated at low speed, fatal at high.
        assertFalse(StallPolicy.shouldEvict(low, 6_000L, 4))
        assertTrue(StallPolicy.shouldEvict(high, 6_000L, 4))
        // A 10 s stall is tolerated only at the low end.
        assertFalse(StallPolicy.shouldEvict(low, 10_000L, 4))
        assertTrue(StallPolicy.shouldEvict(1024L * 1024, 10_201L, 4))
        assertFalse(StallPolicy.shouldEvict(1024L * 1024, 10_200L, 4))
        // v7.7 Step B3: a 3.5 s stall at full speed now evicts
        // (it survived under the old 4 s floor) — the one-second
        // earlier takeover this step bought; at low speed the
        // same stall is still far inside the patience.
        assertTrue(StallPolicy.shouldEvict(high, 3_500L, 4))
        assertFalse(StallPolicy.shouldEvict(low, 3_500L, 4))
    }

    @Test
    fun noStallNoEviction() {
        assertFalse(StallPolicy.shouldEvict(high, 0L, 4))
        assertFalse(StallPolicy.shouldEvict(high, -1_000L, 4))
    }
}
