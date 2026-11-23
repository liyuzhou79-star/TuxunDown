package com.tuxundown.android

import com.tuxundown.android.core.ScheduleTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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

    /** An explicit local date (month is 0-based, like Calendar). */
    private fun on(
        year: Int, month0: Int, day: Int, hour: Int, minute: Int,
    ): Long {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month0, day, hour, minute, 0)
        }
        return cal.timeInMillis
    }

    // ---- v4.6 (audit round 31): calendar boundaries. The
    // arithmetic is Calendar DAY_OF_YEAR adds, never +24 h in
    // milliseconds, so month / year / leap edges must land on
    // the same wall-clock time of the correct next day. ----

    @Test
    fun advanceDailyCrossesMonthEnd() {
        val slot = on(2025, 0, 31, 23, 30) // Jan 31
        assertEquals(
            on(2025, 1, 1, 23, 30), // Feb 1
            ScheduleTime.advanceDaily(slot, on(2025, 1, 1, 0, 30)),
        )
    }

    @Test
    fun advanceDailyCrossesYearEnd() {
        val slot = on(2025, 11, 31, 8, 0) // Dec 31 2025
        // "Now" is past Jan 1 2026's slot too, so the next
        // future slot is Jan 2 — never a past one.
        assertEquals(
            on(2026, 0, 2, 8, 0),
            ScheduleTime.advanceDaily(slot, on(2026, 0, 1, 9, 0)),
        )
    }

    @Test
    fun advanceDailyLeapFebruaryHas29Days() {
        val slot = on(2024, 1, 28, 23, 30) // Feb 28 2024 (leap)
        assertEquals(
            on(2024, 1, 29, 23, 30), // Feb 29 exists in 2024
            ScheduleTime.advanceDaily(slot, on(2024, 1, 29, 0, 30)),
        )
    }

    @Test
    fun advanceDailyNonLeapFebruaryRollsToMarch() {
        val slot = on(2025, 1, 28, 23, 30) // Feb 28 2025
        assertEquals(
            on(2025, 2, 1, 23, 30), // Mar 1 — no Feb 29 in 2025
            ScheduleTime.advanceDaily(slot, on(2025, 2, 1, 0, 30)),
        )
    }

    @Test
    fun nextOccurrenceCrossesYearEnd() {
        val now = on(2025, 11, 31, 23, 0) // Dec 31 2025, 23:00
        assertEquals(
            on(2026, 0, 1, 7, 0),
            ScheduleTime.nextOccurrence(7, 0, now),
        )
    }

    @Test
    fun nextOccurrenceAtExactSlotCrossesMonthEnd() {
        // Exactly at the slot is not strictly after it, so it
        // rolls — across the month boundary here.
        val now = on(2025, 0, 31, 10, 0) // Jan 31, 10:00
        assertEquals(
            on(2025, 1, 1, 10, 0), // Feb 1
            ScheduleTime.nextOccurrence(10, 0, now),
        )
    }

    // ---- v6.4 (audit round 49): time-zone injection, the
    // daily re-anchor, and DST edges. Epoch constants below
    // were computed independently (zone offsets noted per
    // line) so the assertions do not just mirror Calendar. ----

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val taipei: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    @Test
    fun nextDailyAtReanchorsPickedTimeAfterZoneMove() {
        // The slot epoch a Taipei user got for "23:30 daily":
        // 2026-11-22 23:30 +08:00.
        val taipeiSlot = 1_795_361_400_000L
        assertEquals("23:30", ScheduleTime.formatHhMm(taipeiSlot, taipei))
        // The phone is now in a UTC zone; it is 2026-11-23 12:00 UTC.
        val now = 1_795_435_200_000L
        // Re-anchoring from the stored pick lands on 23:30 LOCAL
        // in the new zone (2026-11-23 23:30 UTC), not on the old
        // epoch's 15:30 reading there.
        val next = ScheduleTime.nextDailyAt(23, 30, now, utc)
        assertEquals(1_795_476_600_000L, next)
        assertEquals("23:30", ScheduleTime.formatHhMm(next, utc))
    }

    @Test
    fun advanceDailyOnLegacyEpochDriftsAfterZoneMove() {
        // The pre-v6.4 behaviour, pinned on purpose: with no
        // stored pick, advancing the stored epoch in the NEW
        // zone keeps the epoch's new-zone reading — Taipei's
        // 23:30 slot (2026-11-22 15:30 UTC) advances to
        // 2026-11-23 15:30 UTC. This silent drift is exactly
        // what nextDailyAt fixes for records that carry a pick;
        // legacy records cannot be re-anchored (see the
        // advanceDaily doc).
        val taipeiSlot = 1_795_361_400_000L
        val now = 1_795_435_200_000L // 2026-11-23 12:00 UTC
        val next = ScheduleTime.advanceDaily(taipeiSlot, now, utc)
        assertEquals(1_795_447_800_000L, next)
        assertEquals("15:30", ScheduleTime.formatHhMm(next, utc))
    }

    @Test
    fun nextDailyAtSpringForwardNonexistentTimeResolvesBackward() {
        // America/New_York springs forward on 2026-03-08
        // (02:00 -> 03:00): 02:30 never happens that day.
        // java.util.Calendar's lenient resolution moves it
        // BACKWARD by the gap, landing at 01:30 EST (06:30
        // UTC) — the slot fires an hour EARLY that one day,
        // and the next reschedule (again from the stored pick)
        // is back at 02:30, so the drift never persists.
        // Pinned from CI evidence (v6.4 step A probe run): the
        // forward candidates 03:30 EDT and next-day both fail,
        // this one passes.
        val now = 1_772_902_800_000L // 2026-03-07 12:00 EST
        val next = ScheduleTime.nextDailyAt(2, 30, now, newYork)
        assertEquals(1_772_951_400_000L, next) // 2026-03-08 01:30 EST
        assertEquals("01:30", ScheduleTime.formatHhMm(next, newYork))
    }

    @Test
    fun nextDailyAtFallBackRepeatedTimeResolvesToFirstOccurrence() {
        // America/New_York falls back on 2026-11-01: 01:30
        // happens twice (EDT, then EST). Calendar resolves the
        // ambiguity to the FIRST occurrence — 01:30 EDT
        // (05:30 UTC), not the 06:30 UTC repeat (verified by
        // the first v6.4 CI run, where this assertion passed).
        val now = 1_793_462_400_000L // 2026-10-31 12:00 EDT
        val next = ScheduleTime.nextDailyAt(1, 30, now, newYork)
        assertEquals(1_793_511_000_000L, next) // 2026-11-01 01:30 EDT
        assertEquals("01:30", ScheduleTime.formatHhMm(next, newYork))
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
