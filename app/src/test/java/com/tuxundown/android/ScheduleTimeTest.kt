package com.tuxundown.android

import com.tuxundown.android.core.ScheduleTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** v1.8: daily-schedule wall-clock math. */
class ScheduleTimeTest {

    private fun at(dayOffset: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun parseHhMmAcceptsAndRejects() {
        assertEquals(23 to 30, ScheduleTime.parseHhMm("23:30"))
        assertEquals(0 to 0, ScheduleTime.parseHhMm(" 0:00 "))
        assertEquals(7 to 5, ScheduleTime.parseHhMm("7:5"))
        assertNull(ScheduleTime.parseHhMm("24:00"))
        assertNull(ScheduleTime.parseHhMm("12:60"))
        assertNull(ScheduleTime.parseHhMm("abc"))
        assertNull(ScheduleTime.parseHhMm("1230"))
        assertNull(ScheduleTime.parseHhMm(""))
    }

    @Test
    fun nextOccurrenceIsLaterTodayWhenAhead() {
        val now = at(0, 10, 0)
        assertEquals(at(0, 23, 30), ScheduleTime.nextOccurrence(23, 30, now))
    }

    @Test
    fun nextOccurrenceRollsToTomorrowWhenPassed() {
        val now = at(0, 10, 0)
        assertEquals(at(1, 9, 0), ScheduleTime.nextOccurrence(9, 0, now))
        // Exactly "now" is not strictly after — it rolls too.
        assertEquals(at(1, 10, 0), ScheduleTime.nextOccurrence(10, 0, now))
    }

    @Test
    fun advanceDailyLandsOnNextFutureSlot() {
        val slot = at(0, 23, 30)
        // Finished an hour after the slot: next is tomorrow's slot.
        assertEquals(at(1, 23, 30), ScheduleTime.advanceDaily(slot, at(1, 0, 30)))
        // Phone off for three days: still the next future slot,
        // keeping the same wall-clock time.
        val landed = ScheduleTime.advanceDaily(slot, at(3, 12, 0))
        assertEquals(at(3, 23, 30), landed)
        assertTrue(landed > at(3, 12, 0))
        assertEquals("23:30", ScheduleTime.formatHhMm(landed))
    }
}
