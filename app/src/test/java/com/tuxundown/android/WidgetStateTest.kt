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
