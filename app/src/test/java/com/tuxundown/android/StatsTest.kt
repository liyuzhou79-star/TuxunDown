package com.tuxundown.android

import com.tuxundown.android.core.DaySeries
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

    @Test
    fun dayKeyAndTodayBytesReadInInjectedZone() {
        // v6.4 (audit round 49): the day key is a LOCAL-day
        // caliber in the zone in force at read time — one
        // instant, two zones, two different days. A stored
        // "today" counter keyed in the old zone simply stops
        // matching after a move (reads 0); nothing is moved or
        // double-counted.
        val at = 1_795_365_000_000L // 2026-11-22 16:30 UTC
        val taipei = java.util.TimeZone.getTimeZone("Asia/Taipei")
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("2026-11-23", StatsMath.dayKey(at, taipei))
        assertEquals("2026-11-22", StatsMath.dayKey(at, utc))
        assertEquals(500L, StatsMath.todayBytes("2026-11-23", 500, at, taipei))
        assertEquals(0L, StatsMath.todayBytes("2026-11-23", 500, at, utc))
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
    fun parseWithLossCountsDroppedLines() {
        // v7.3 (audit round 58, B5): DaySeries and HostStats gain
        // the same loss-reporting variant as HostMemory — same
        // records as parse, plus the dropped non-blank line count.
        val days = "2026-11-22\t100\n" +
            "garbage line\n" +
            "2026-11-23\tNaN\n" +
            "2026-11-24\t300\n"
        val (dayEntries, dayDropped) = DaySeries.parseWithLoss(days)
        assertEquals(
            listOf(DaySeries.Day("2026-11-22", 100), DaySeries.Day("2026-11-24", 300)),
            dayEntries,
        )
        assertEquals(2, dayDropped)
        assertEquals(dayEntries, DaySeries.parse(days))
        assertEquals(0, DaySeries.parseWithLoss("").second)

        val hosts = "a.com\t100\t2\n" +
            "b.com\tbroken\t1\n" +
            "too\tfew\n" +
            "c.com\t50\t1\n"
        val (hostEntries, hostDropped) = HostStats.parseWithLoss(hosts)
        assertEquals(listOf("a.com", "c.com"), hostEntries.map { it.host })
        assertEquals(2, hostDropped)
        assertEquals(hostEntries, HostStats.parse(hosts))
        assertEquals(0, HostStats.parseWithLoss("").second)
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
