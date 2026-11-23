package com.tuxundown.android

import com.tuxundown.android.core.SpeedSmoothing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.5 (audit round 60, Step C): the one EMA caliber every
 * speed surface reads. These cases pin the EXACT pre-v7.5
 * inline formula — (prev*3+raw)/4, first tick = raw — so the
 * extraction into SpeedSmoothing cannot drift the numbers
 * the card / notification / chart have always shown.
 */
class SpeedSmoothingTest {

    @Test
    fun firstTickTakesTheRawValue() {
        // No previous value (prev == 0): the sample stands
        // as-is, exactly like the old `if (speedBps == 0L)`
        // branch in all five engine sampler paths.
        assertEquals(123_456L, SpeedSmoothing.next(0L, 123_456L))
        assertEquals(0L, SpeedSmoothing.next(0L, 0L))
        // A non-positive prev is "no value yet" by the same
        // rule (speeds are never negative in practice).
        assertEquals(777L, SpeedSmoothing.next(-5L, 777L))
    }

    @Test
    fun formulaIsExactlyThreeQuartersPrevPlusOneQuarterRaw() {
        assertEquals(125L, SpeedSmoothing.next(100L, 200L)) // (300+200)/4
        assertEquals(100L, SpeedSmoothing.next(100L, 100L)) // steady state
        assertEquals(101L, SpeedSmoothing.next(101L, 101L))
        // Integer division truncates, as the inline code did:
        // (3*3 + 0) / 4 = 2, never rounded to 3.
        assertEquals(2L, SpeedSmoothing.next(3L, 0L))
    }

    @Test
    fun repeatedRawConvergesToTheRawValue() {
        var v = SpeedSmoothing.next(0L, 1_000L) // first tick: raw
        assertEquals(1_000L, v)
        // Feeding a constant 2_000: the EMA climbs toward it
        // and (integer arithmetic) settles within 3 of it,
        // never overshooting.
        repeat(40) { v = SpeedSmoothing.next(v, 2_000L) }
        assertTrue(v in 1_997L..2_000L)
    }

    @Test
    fun zeroRawDecaysByThreeQuartersEachTick() {
        // A stalled connection's smoothed speed bleeds off at
        // the documented rate instead of dropping to zero in
        // one tick (or sticking forever).
        var v = 1_000L
        v = SpeedSmoothing.next(v, 0L)
        assertEquals(750L, v)
        v = SpeedSmoothing.next(v, 0L)
        assertEquals(562L, v) // (750*3)/4 = 562.5 -> 562
        repeat(30) { v = SpeedSmoothing.next(v, 0L) }
        assertEquals(0L, v)
    }
}
