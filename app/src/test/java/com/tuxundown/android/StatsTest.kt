package com.tuxundown.android

import com.tuxundown.android.core.HostStats
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

    // ---- v2.3: per-host ranking ----

    @Test
    fun hostStatsAccumulateAndRankByBytes() {
        var entries = emptyList<HostStats.Entry>()
        entries = HostStats.record(entries, "a.com", 100)
        entries = HostStats.record(entries, "b.com", 500)
        entries = HostStats.record(entries, "a.com", 250)
        val top = HostStats.top(entries)
        assertEquals(2, top.size)
        assertEquals("b.com", top[0].host)
        assertEquals(500L, top[0].bytes)
        assertEquals("a.com", top[1].host)
        assertEquals(350L, top[1].bytes)
        assertEquals(2, top[1].count)
    }

    @Test
    fun hostStatsIgnoreBlankHostAndZeroBytes() {
        val entries = listOf(HostStats.Entry("a.com", 10, 1))
        assertEquals(entries, HostStats.record(entries, "", 100))
        assertEquals(entries, HostStats.record(entries, "b.com", 0))
    }

    @Test
    fun hostStatsRoundTrip() {
        val entries = listOf(
            HostStats.Entry("media.example.com", 123456, 3),
            HostStats.Entry("ftp.example.org", 42, 1),
        )
        assertEquals(entries, HostStats.parse(HostStats.serialize(entries)))
        assertEquals(emptyList<HostStats.Entry>(), HostStats.parse(""))
        assertEquals(emptyList<HostStats.Entry>(), HostStats.parse("garbage line"))
    }
}
