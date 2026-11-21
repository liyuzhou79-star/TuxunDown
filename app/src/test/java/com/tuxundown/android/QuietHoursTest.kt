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
