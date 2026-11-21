package com.tuxundown.android

import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1: widget aggregate math mirrors the notification's. */
class WidgetStateTest {

    private fun task(
        status: TaskStatus,
        total: Long = -1,
        downloaded: Long = 0,
    ): TaskRecord {
        val t = TaskRecord(url = "https://a.com/f.bin", status = status, totalSize = total)
        t.downloaded = downloaded
        return t
    }

    @Test
    fun emptyListIsIdle() {
        val s = WidgetState.summary(emptyList())
        assertEquals(0, s.activeCount)
        assertEquals(0, s.totalPct)
        assertFalse(s.anyRunning)
        assertEquals(0L, s.totalBytes)
        assertEquals(0L, s.doneBytes)
    }

    @Test
    fun byteSumsCoverOnlySizedActiveTasks() {
        // v2.6: the byte fields the home summary card reads —
        // same active set and same sized-only rule as totalPct.
        val s = WidgetState.summary(
            listOf(
                task(TaskStatus.RUNNING, total = 300, downloaded = 100),
                task(TaskStatus.QUEUED, total = 200, downloaded = 50),
                task(TaskStatus.QUEUED, total = -1, downloaded = 999),
                task(TaskStatus.DONE, total = 500, downloaded = 500),
                task(TaskStatus.PAUSED, total = 700, downloaded = 700),
            )
        )
        assertEquals(3, s.activeCount)
        assertEquals(500L, s.totalBytes)
        assertEquals(150L, s.doneBytes)
        assertEquals(30, s.totalPct)
    }

    @Test
    fun largeFilePercentUsesExactLongMath() {
        // The v2.6 round-12 divergence: a Float ratio of a
        // ~271 MB file can truncate one point below the exact
        // integer percent. 268435456 = 2^28; downloaded chosen
        // so the exact percent is 29 — the value all three
        // surfaces must print.
        val total = 268_435_456L
        val done = total * 29 / 100 + 1 // just past 29%
        val s = WidgetState.summary(
            listOf(task(TaskStatus.RUNNING, total = total, downloaded = done))
        )
        assertEquals(29, s.totalPct)
    }

    @Test
    fun doneAndFailedDoNotCountAsActive() {
        val s = WidgetState.summary(
            listOf(
                task(TaskStatus.DONE, total = 100, downloaded = 100),
                task(TaskStatus.FAILED, total = 100, downloaded = 10),
                task(TaskStatus.PAUSED, total = 100, downloaded = 10),
            )
        )
        assertEquals(0, s.activeCount)
        assertFalse(s.anyRunning)
    }

    @Test
    fun progressIsByteWeightedAcrossActiveTasks() {
        val s = WidgetState.summary(
            listOf(
                task(TaskStatus.RUNNING, total = 300, downloaded = 150),
                task(TaskStatus.QUEUED, total = 100, downloaded = 50),
            )
        )
        assertEquals(2, s.activeCount)
        assertEquals(50, s.totalPct) // (150+50) / (300+100)
        assertTrue(s.anyRunning)
    }

    @Test
    fun unknownSizesGiveZeroPctNotAFakeNumber() {
        val s = WidgetState.summary(
            listOf(task(TaskStatus.RUNNING, total = -1, downloaded = 12345))
        )
        assertEquals(1, s.activeCount)
        assertEquals(0, s.totalPct)
        assertTrue(s.anyRunning)
    }
}
