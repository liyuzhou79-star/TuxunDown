package com.tuxundown.android

import com.tuxundown.android.core.DaySeries
import com.tuxundown.android.core.StatsMath
import org.junit.Assert.assertEquals
import org.junit.Test

class DaySeriesTest {

    // 2026-11-22 12:00 local — the exact zone does not matter,
    // dayKey and lastDays share the same Calendar.
    private val now = java.util.Calendar.getInstance().apply {
        set(2026, java.util.Calendar.NOVEMBER, 22, 12, 0, 0)
    }.timeInMillis

    @Test
    fun recordUpsertsAndSorts() {
        var days = DaySeries.recordDayTotal(emptyList(), "2026-11-22", 100)
        days = DaySeries.recordDayTotal(days, "2026-11-20", 50)
        days = DaySeries.recordDayTotal(days, "2026-11-22", 260)
        assertEquals(
            listOf(
                DaySeries.Day("2026-11-20", 50),
                DaySeries.Day("2026-11-22", 260),
            ),
            days,
        )
    }

    @Test
    fun pruneKeepsTheNewestDays() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 1, 12, 0, 0)
        }
        var days: List<DaySeries.Day> = emptyList()
        var firstKey = ""
        repeat(40) {
            val key = StatsMath.dayKey(cal.timeInMillis)
            if (firstKey.isEmpty()) firstKey = key
            days = DaySeries.recordDayTotal(days, key, 1)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        assertEquals(DaySeries.MAX_DAYS, days.size)
        // The oldest recorded day was pruned away.
        assertEquals(false, days.any { it.key == firstKey })
    }

    @Test
    fun lastDaysZeroFillsAndEndsToday() {
        val today = StatsMath.dayKey(now)
        val days = DaySeries.recordDayTotal(emptyList(), today, 777)
        val week = DaySeries.lastDays(days, 7, now)
        assertEquals(7, week.size)
        assertEquals(today, week.last().key)
        assertEquals(777L, week.last().bytes)
        assertEquals(0L, week.first().bytes)
        // strictly ascending keys
        assertEquals(week.map { it.key }.sorted(), week.map { it.key })
    }

    @Test
    fun serializeRoundTripAndBadLinesDropped() {
        val days = listOf(
            DaySeries.Day("2026-11-21", 10),
            DaySeries.Day("2026-11-22", 20),
        )
        assertEquals(days, DaySeries.parse(DaySeries.serialize(days)))
        assertEquals(
            listOf(DaySeries.Day("2026-11-22", 20)),
            DaySeries.parse("garbage\n2026-11-22\t20\n\t5\n2026-11-22\tx"),
        )
        assertEquals(emptyList<DaySeries.Day>(), DaySeries.parse(""))
    }
}
