package com.tuxundown.android

import com.tuxundown.android.core.SpeedSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedScheduleTest {

    private val start = 23 * 60 // 23:00
    private val end = 7 * 60 // 07:00

    @Test
    fun wrappingWindowMembership() {
        assertTrue(SpeedSchedule.inWindow(start, end, 23 * 60))
        assertTrue(SpeedSchedule.inWindow(start, end, 0))
        assertTrue(SpeedSchedule.inWindow(start, end, 6 * 60 + 59))
        assertFalse(SpeedSchedule.inWindow(start, end, 7 * 60))
        assertFalse(SpeedSchedule.inWindow(start, end, 12 * 60))
        assertFalse(SpeedSchedule.inWindow(start, end, 22 * 60 + 59))
    }

    @Test
    fun plainDaytimeWindowMembership() {
        assertTrue(SpeedSchedule.inWindow(9 * 60, 18 * 60, 9 * 60))
        assertTrue(SpeedSchedule.inWindow(9 * 60, 18 * 60, 17 * 60 + 59))
        assertFalse(SpeedSchedule.inWindow(9 * 60, 18 * 60, 18 * 60))
        assertFalse(SpeedSchedule.inWindow(9 * 60, 18 * 60, 8 * 60))
    }

    @Test
    fun zeroLengthWindowIsEmpty() {
        assertFalse(SpeedSchedule.inWindow(600, 600, 600))
        assertFalse(SpeedSchedule.inWindow(600, 600, 0))
    }

    @Test
    fun insideWindowUsesWindowLimit() {
        // Night: unlimited (0), day default: 1024.
        assertEquals(
            0,
            SpeedSchedule.effectiveLimitKBps(
                true, start, end, 0, 1024, 2 * 60
            ),
        )
        assertEquals(
            512,
            SpeedSchedule.effectiveLimitKBps(
                true, start, end, 512, 1024, 2 * 60
            ),
        )
    }

    @Test
    fun outsideWindowUsesDefaultLimit() {
        assertEquals(
            1024,
            SpeedSchedule.effectiveLimitKBps(
                true, start, end, 0, 1024, 12 * 60
            ),
        )
    }

    @Test
    fun disabledPlanAlwaysUsesDefault() {
        assertEquals(
            1024,
            SpeedSchedule.effectiveLimitKBps(
                false, start, end, 0, 1024, 2 * 60
            ),
        )
        assertEquals(
            0,
            SpeedSchedule.effectiveLimitKBps(
                false, start, end, 512, 0, 2 * 60
            ),
        )
    }

    @Test
    fun negativeLimitsAreClampedToUnlimited() {
        assertEquals(
            0,
            SpeedSchedule.effectiveLimitKBps(
                true, start, end, -5, 1024, 2 * 60
            ),
        )
        assertEquals(
            0,
            SpeedSchedule.effectiveLimitKBps(
                false, start, end, 0, -1, 2 * 60
            ),
        )
    }
}
