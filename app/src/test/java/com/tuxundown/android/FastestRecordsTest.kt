package com.tuxundown.android

import com.tuxundown.android.core.FastestRecords
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v6.2 step C: the fastest-records board — only measured
 * peaks qualify, the order is total and deterministic,
 * and the date is the completion stamp (createdAt only
 * for records that never finished). */
class FastestRecordsTest {

    private fun rec(
        id: String,
        name: String,
        peak: Long,
        finishedAt: Long = 0,
        createdAt: Long = 0,
        savedName: String = "",
    ) = TaskRecord(
        id = id,
        url = "https://a.example.com/$name",
        fileName = name,
        status = if (finishedAt > 0) TaskStatus.DONE else TaskStatus.PAUSED,
        createdAt = createdAt,
        finishedAt = finishedAt,
        savedName = savedName,
    ).apply { peakBps = peak }

    @Test
    fun emptyListGivesEmptyBoard() {
        assertTrue(FastestRecords.top(emptyList()).isEmpty())
    }

    @Test
    fun allZeroPeaksGiveEmptyBoard() {
        val tasks = listOf(
            rec("a", "a.bin", 0, finishedAt = 100),
            rec("b", "b.bin", 0, finishedAt = 200),
        )
        assertTrue(FastestRecords.top(tasks).isEmpty())
    }

    @Test
    fun oldRecordWithDefaultPeakIsExcluded() {
        // A record parsed from pre-peakBps storage never had
        // peakBps assigned: the field keeps its default 0
        // and must not take a place with a fake "0 B/秒".
        val old = TaskRecord(
            id = "old",
            url = "https://a.example.com/old.bin",
            fileName = "old.bin",
            status = TaskStatus.DONE,
            finishedAt = 500,
        )
        assertEquals(0L, old.peakBps)
        assertTrue(FastestRecords.top(listOf(old)).isEmpty())
    }

    @Test
    fun fewerThanThreeShowsWhatExists() {
        val tasks = listOf(
            rec("a", "a.bin", 100, finishedAt = 100),
            rec("b", "b.bin", 0, finishedAt = 200),
            rec("c", "c.bin", 300, finishedAt = 300),
        )
        val top = FastestRecords.top(tasks)
        assertEquals(2, top.size)
        assertEquals("c.bin", top[0].name)
        assertEquals(300L, top[0].peakBps)
        assertEquals("a.bin", top[1].name)
    }

    @Test
    fun exactlyThreeAllShownInPeakOrder() {
        val tasks = listOf(
            rec("a", "a.bin", 200, finishedAt = 100),
            rec("b", "b.bin", 900, finishedAt = 100),
            rec("c", "c.bin", 500, finishedAt = 100),
        )
        val top = FastestRecords.top(tasks)
        assertEquals(listOf("b.bin", "c.bin", "a.bin"), top.map { it.name })
    }

    @Test
    fun moreThanThreeTruncatesToTopThree() {
        val tasks = listOf(
            rec("a", "a.bin", 100, finishedAt = 100),
            rec("b", "b.bin", 500, finishedAt = 100),
            rec("c", "c.bin", 300, finishedAt = 100),
            rec("d", "d.bin", 900, finishedAt = 100),
            rec("e", "e.bin", 200, finishedAt = 100),
        )
        val top = FastestRecords.top(tasks)
        assertEquals(3, top.size)
        assertEquals(listOf("d.bin", "b.bin", "c.bin"), top.map { it.name })
    }

    @Test
    fun peakTieBreaksByNewerDateFirst() {
        val tasks = listOf(
            rec("a", "a.bin", 500, finishedAt = 100),
            rec("b", "b.bin", 500, finishedAt = 300),
            rec("c", "c.bin", 500, finishedAt = 200),
        )
        val top = FastestRecords.top(tasks)
        assertEquals(listOf("b.bin", "c.bin", "a.bin"), top.map { it.name })
        assertEquals(listOf(300L, 200L, 100L), top.map { it.dateMs })
    }

    @Test
    fun fullTieBreaksByIdSoInputOrderDoesNotMatter() {
        val a = rec("id-a", "a.bin", 500, finishedAt = 100)
        val b = rec("id-b", "b.bin", 500, finishedAt = 100)
        val forward = FastestRecords.top(listOf(a, b))
        val reversed = FastestRecords.top(listOf(b, a))
        assertEquals(forward, reversed)
        assertEquals(listOf("a.bin", "b.bin"), forward.map { it.name })
    }

    @Test
    fun dateIsCompletionStampWhenFinished() {
        val t = rec("a", "a.bin", 500, finishedAt = 777, createdAt = 111)
        assertEquals(777L, FastestRecords.top(listOf(t))[0].dateMs)
        assertEquals(777L, FastestRecords.dateOf(t))
    }

    @Test
    fun unfinishedRecordDatesByCreationStamp() {
        // Never finished: finishedAt = 0, so the board must
        // show the creation stamp, never epoch 0.
        val t = rec("a", "a.bin", 500, finishedAt = 0, createdAt = 222)
        assertEquals(222L, FastestRecords.top(listOf(t))[0].dateMs)
    }

    @Test
    fun namePrefersSavedNameThenFileNameThenUrl() {
        val saved = rec("a", "a.bin", 500, finishedAt = 100, savedName = "real.zip")
        assertEquals("real.zip", FastestRecords.top(listOf(saved))[0].name)
        val noFileName = rec("b", "", 500, finishedAt = 100)
        assertEquals(
            "https://a.example.com/",
            FastestRecords.top(listOf(noFileName))[0].name,
        )
    }
}
