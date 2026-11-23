package com.tuxundown.android

import com.tuxundown.android.core.CompletionMerge
import com.tuxundown.android.core.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5: quiet-hours window math + completion merging. */
class QuietHoursTest {

    @Test
    fun wrappingWindowCoversNight() {
        val s = 23 * 60
        val e = 7 * 60
        assertTrue(QuietHours.isQuiet(s, e, 23 * 60))
        assertTrue(QuietHours.isQuiet(s, e, 23 * 60 + 59))
        assertTrue(QuietHours.isQuiet(s, e, 0))
        assertTrue(QuietHours.isQuiet(s, e, 3 * 60))
        assertTrue(QuietHours.isQuiet(s, e, 7 * 60 - 1))
        assertFalse(QuietHours.isQuiet(s, e, 7 * 60))
        assertFalse(QuietHours.isQuiet(s, e, 22 * 60 + 59))
        assertFalse(QuietHours.isQuiet(s, e, 12 * 60))
    }

    @Test
    fun plainWindowAndBoundaries() {
        val s = 60
        val e = 5 * 60
        assertTrue(QuietHours.isQuiet(s, e, 60))
        assertTrue(QuietHours.isQuiet(s, e, 5 * 60 - 1))
        assertFalse(QuietHours.isQuiet(s, e, 5 * 60))
        assertFalse(QuietHours.isQuiet(s, e, 59))
        // start == end is an empty window, never quiet.
        assertFalse(QuietHours.isQuiet(600, 600, 600))
        assertFalse(QuietHours.isQuiet(600, 600, 0))
        assertFalse(QuietHours.isQuiet(0, 0, 0))
    }

    @Test
    fun crossMidnightWindowJudgedInInjectedZone() {
        // v6.4 (audit round 49): the window is judged live from
        // the wall clock in the zone in force — the SAME instant
        // is inside the 23:00 -> 07:00 window in Taipei
        // (2026-11-23 00:30 local) and outside it in UTC
        // (2026-11-22 16:30). No state crosses over: each
        // judgement re-reads minute-of-day in its zone.
        val at = 1_795_365_000_000L // 2026-11-22 16:30 UTC
        val taipei = java.util.TimeZone.getTimeZone("Asia/Taipei")
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals(30, QuietHours.minuteOfDay(at, taipei))
        assertEquals(16 * 60 + 30, QuietHours.minuteOfDay(at, utc))
        assertTrue(
            QuietHours.isQuietAt(
                QuietHours.DEFAULT_START_MIN, QuietHours.DEFAULT_END_MIN,
                at, taipei,
            )
        )
        assertFalse(
            QuietHours.isQuietAt(
                QuietHours.DEFAULT_START_MIN, QuietHours.DEFAULT_END_MIN,
                at, utc,
            )
        )
    }

    @Test
    fun formatAndDefaults() {
        assertEquals("23:00", QuietHours.formatMin(QuietHours.DEFAULT_START_MIN))
        assertEquals("07:00", QuietHours.formatMin(QuietHours.DEFAULT_END_MIN))
        assertEquals("00:05", QuietHours.formatMin(5))
        assertEquals("23:59", QuietHours.formatMin(9999))
    }
}

class CompletionMergeTest {

    private fun done(name: String, at: Long) =
        CompletionMerge.Done(name, "content://x/$name", at)

    @Test
    fun completionsInsideWindowMerge() {
        var list = emptyList<CompletionMerge.Done>()
        list = CompletionMerge.add(list, done("a.zip", 0), 0)
        assertFalse(CompletionMerge.isMerged(list))
        list = CompletionMerge.add(list, done("b.zip", 5_000), 5_000)
        list = CompletionMerge.add(list, done("c.zip", 14_000), 14_000)
        assertTrue(CompletionMerge.isMerged(list))
        assertEquals(3, list.size)
        assertEquals("3 个文件下载完成", CompletionMerge.title(list))
        assertEquals("a.zip\nb.zip\nc.zip", CompletionMerge.lines(list))
    }

    private fun doneSized(name: String, at: Long, sizeBytes: Long) =
        CompletionMerge.Done(name, "content://x/$name", at, sizeBytes)

    @Test
    fun detailLineShowsSizeAndAverageSpeed() {
        // 12.0 MB at exactly 4.0 MB/s average.
        assertEquals(
            "12.0 MB · 平均 4.0 MB/秒",
            CompletionMerge.detailLine(12L * 1024 * 1024, 4L * 1024 * 1024),
        )
    }

    @Test
    fun detailLineOmitsUnknownParts() {
        assertEquals(
            "12.0 MB",
            CompletionMerge.detailLine(12L * 1024 * 1024, 0),
        )
        assertEquals(
            "平均 4.0 MB/秒",
            CompletionMerge.detailLine(-1, 4L * 1024 * 1024),
        )
        // Both unknown: no line at all (the caller then posts
        // the plain notification, never a misleading number).
        assertEquals("", CompletionMerge.detailLine(-1, 0))
        assertEquals("", CompletionMerge.detailLine(0, 0))
    }

    @Test
    fun totalSizeNeedsEverySizeKnown() {
        var list = emptyList<CompletionMerge.Done>()
        list = CompletionMerge.add(list, doneSized("a.zip", 0, 1024), 0)
        list = CompletionMerge.add(list, doneSized("b.zip", 5_000, 2048), 5_000)
        assertEquals(3072L, CompletionMerge.totalSizeBytes(list))
        assertEquals(
            "a.zip\nb.zip\n共 3.0 KB",
            CompletionMerge.summaryText(list),
        )
        // One unknown size suppresses the total entirely —
        // a partial sum would read as the real total.
        list = CompletionMerge.add(list, done("c.zip", 10_000), 10_000)
        assertEquals(-1L, CompletionMerge.totalSizeBytes(list))
        assertEquals("a.zip\nb.zip\nc.zip", CompletionMerge.summaryText(list))
        assertEquals(-1L, CompletionMerge.totalSizeBytes(emptyList()))
    }

    @Test
    fun staleEntriesFallOutOfTheWindow() {
        var list = emptyList<CompletionMerge.Done>()
        list = CompletionMerge.add(list, done("a.zip", 0), 0)
        // 20 s later the first entry is out of the 15 s window:
        // this completion starts a fresh single notification.
        list = CompletionMerge.add(list, done("b.zip", 20_000), 20_000)
        assertEquals(1, list.size)
        assertEquals("b.zip", list[0].name)
        assertFalse(CompletionMerge.isMerged(list))
    }
}
