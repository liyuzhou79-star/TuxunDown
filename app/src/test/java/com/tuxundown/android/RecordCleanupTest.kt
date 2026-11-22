package com.tuxundown.android

import com.tuxundown.android.core.RecordCleanup
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.4: who counts as an "old record" for the settings
 *  cleanup — DONE/FAILED only, dated by the terminal stamp
 *  (with the creation-time fallback for pre-v3.4 failures). */
class RecordCleanupTest {

    private val now = 1_800_000_000_000L
    private val day = RecordCleanup.DAY_MS

    private fun task(
        status: TaskStatus,
        finishedAt: Long = 0,
        createdAt: Long = now - 200 * day,
    ) = TaskRecord(
        url = "https://a.example.com/f",
        status = status,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

    @Test
    fun doneOlderThanWindowIsEligible() {
        val t = task(TaskStatus.DONE, finishedAt = now - 31 * day)
        assertTrue(RecordCleanup.eligible(t, now, 30))
        assertFalse(RecordCleanup.eligible(t, now, 90))
    }

    @Test
    fun boundaryIsInclusive() {
        val t = task(TaskStatus.DONE, finishedAt = now - 30 * day)
        assertTrue(RecordCleanup.eligible(t, now, 30))
        val justInside = task(TaskStatus.DONE, finishedAt = now - 30 * day + 1)
        assertFalse(RecordCleanup.eligible(justInside, now, 30))
    }

    @Test
    fun activeStatusesAreNeverEligible() {
        for (s in listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED)) {
            val t = task(s, finishedAt = now - 999 * day)
            assertFalse("status $s must never be cleaned", RecordCleanup.eligible(t, now, 30))
        }
    }

    @Test
    fun failedWithoutStampFallsBackToCreationTime() {
        // Pre-v3.4 failure: no finishedAt stamp. The creation
        // time decides — an old-created failure is eligible, a
        // recently created one is not (even though we cannot
        // know exactly when it failed).
        val old = task(TaskStatus.FAILED, finishedAt = 0, createdAt = now - 100 * day)
        assertTrue(RecordCleanup.eligible(old, now, 90))
        val recent = task(TaskStatus.FAILED, finishedAt = 0, createdAt = now - 10 * day)
        assertFalse(RecordCleanup.eligible(recent, now, 30))
    }

    @Test
    fun failedWithStampUsesTheStampNotCreation() {
        // Created long ago but failed yesterday: NOT eligible —
        // the failure stamp is the honest terminal time.
        val t = task(
            TaskStatus.FAILED,
            finishedAt = now - day,
            createdAt = now - 300 * day,
        )
        assertFalse(RecordCleanup.eligible(t, now, 30))
        assertEquals(now - day, RecordCleanup.terminalAt(t))
    }

    @Test
    fun selectReturnsOnlyEligibleInOrder() {
        val a = task(TaskStatus.DONE, finishedAt = now - 100 * day)
        val b = task(TaskStatus.PAUSED, finishedAt = now - 100 * day)
        val c = task(TaskStatus.FAILED, finishedAt = now - 95 * day)
        val d = task(TaskStatus.DONE, finishedAt = now - 5 * day)
        val picked = RecordCleanup.select(listOf(a, b, c, d), now, 90)
        assertEquals(listOf(a, c), picked)
    }

    @Test
    fun zeroOrNegativeDaysDisables() {
        val t = task(TaskStatus.DONE, finishedAt = now - 999 * day)
        assertFalse(RecordCleanup.eligible(t, now, 0))
        assertTrue(RecordCleanup.select(listOf(t), now, 0).isEmpty())
    }
}
