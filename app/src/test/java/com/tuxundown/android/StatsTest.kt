package com.tuxundown.android

import com.tuxundown.android.core.StatsMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.6: statistics day-rollover math. */
class StatsTest {

    @Test
    fun dayKeyFormatsLocalDate() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 22, 10, 30, 0)
        }
        assertEquals("2026-11-22", StatsMath.dayKey(cal.timeInMillis))
    }

    @Test
    fun todayBytesResetOnANewDay() {
        val day1 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 22, 23, 59, 0)
        }.timeInMillis
        val day2 = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 23, 0, 1, 0)
        }.timeInMillis
        val key = StatsMath.dayKey(day1)
        assertEquals(500L, StatsMath.todayBytes(key, 500, day1))
        assertEquals(0L, StatsMath.todayBytes(key, 500, day2))
        assertEquals(0L, StatsMath.todayBytes("", 500, day1))
    }
}
