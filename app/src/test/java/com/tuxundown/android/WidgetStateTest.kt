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
        speed: Long = 0,
    ): TaskRecord {
        val t = TaskRecord(url = "https://a.com/f.bin", status = status, totalSize = total)
        t.downloaded = downloaded
        t.speedBps = speed
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

    // ---- v4.4: global ETA (WidgetState.etaSeconds) ----

    @Test
    fun etaNoTasksIsMinusOne() {
        assertEquals(-1L, WidgetState.etaSeconds(emptyList()))
    }

    @Test
    fun etaOnlyQueuedTasksIsMinusOne() {
        // Queued remaining counts toward the total, but with
        // nothing running there is no speed to divide by.
        assertEquals(
            -1L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.QUEUED, total = 1000, downloaded = 0),
                    task(TaskStatus.QUEUED, total = 500, downloaded = 100),
                )
            ),
        )
    }

    @Test
    fun etaZeroTotalSpeedIsMinusOne() {
        assertEquals(
            -1L,
            WidgetState.etaSeconds(
                listOf(task(TaskStatus.RUNNING, total = 1000, downloaded = 0, speed = 0))
            ),
        )
    }

    @Test
    fun etaUnknownSizeRunningTaskHidesVerdict() {
        // One unknown-size active task makes 「全部」 unprovable,
        // even when every other task is sized and flowing.
        assertEquals(
            -1L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.RUNNING, total = 1000, downloaded = 0, speed = 100),
                    task(TaskStatus.RUNNING, total = -1, downloaded = 0, speed = 100),
                )
            ),
        )
    }

    @Test
    fun etaUnknownSizeQueuedTaskHidesVerdict() {
        assertEquals(
            -1L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.RUNNING, total = 1000, downloaded = 0, speed = 100),
                    task(TaskStatus.QUEUED, total = 0, downloaded = 0),
                )
            ),
        )
    }

    @Test
    fun etaQueuedRemainingCountsTowardTotal() {
        // Running: 1000 left at 100 B/s. Queued: 500 left, not
        // yet flowing — its bytes still have to arrive before
        // everything is done: (1000 + 500) / 100 = 15.
        assertEquals(
            15L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.RUNNING, total = 2000, downloaded = 1000, speed = 100),
                    task(TaskStatus.QUEUED, total = 500, downloaded = 0),
                )
            ),
        )
    }

    @Test
    fun etaRunningSpeedsSumAndDivisionTruncates() {
        // Speeds sum across running tasks (60 + 40 = 100) and
        // the division is the single-task integer division:
        // 1001 / 100 = 10, never rounded up to 11.
        assertEquals(
            10L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.RUNNING, total = 601, downloaded = 0, speed = 60),
                    task(TaskStatus.RUNNING, total = 400, downloaded = 0, speed = 40),
                )
            ),
        )
    }

    @Test
    fun etaNothingRemainingIsMinusOne() {
        assertEquals(
            -1L,
            WidgetState.etaSeconds(
                listOf(task(TaskStatus.RUNNING, total = 100, downloaded = 100, speed = 100))
            ),
        )
    }

    @Test
    fun etaDonePausedAndFailedDoNotEnterTheSet() {
        // DONE / PAUSED / FAILED tasks contribute no remaining
        // bytes and no speed, and their unknown sizes do NOT
        // veto the verdict — only the active set matters.
        // Active alone: (1000 - 0) / 100 = 10.
        assertEquals(
            10L,
            WidgetState.etaSeconds(
                listOf(
                    task(TaskStatus.RUNNING, total = 1000, downloaded = 0, speed = 100),
                    task(TaskStatus.DONE, total = -1, downloaded = 0, speed = 9999),
                    task(TaskStatus.PAUSED, total = -1, downloaded = 0, speed = 9999),
                    task(TaskStatus.PAUSED, total = 9000, downloaded = 0),
                    task(TaskStatus.FAILED, total = -1, downloaded = 0),
                )
            ),
        )
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
