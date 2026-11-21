package com.tuxundown.android

import com.tuxundown.android.core.ArchivePolicy
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import com.tuxundown.android.core.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.4: auto-archive decisions + the archived flag's round-trip. */
class ArchivePolicyTest {

    private val now = 1_700_000_000_000L
    private val day = ArchivePolicy.DAY_MS

    @Test
    fun oldDoneTaskArchives() {
        assertTrue(
            ArchivePolicy.shouldArchive(
                TaskStatus.DONE, now - 8 * day, now, days = 7
            )
        )
        // Boundary: exactly N days old counts.
        assertTrue(
            ArchivePolicy.shouldArchive(
                TaskStatus.DONE, now - 7 * day, now, days = 7
            )
        )
    }

    @Test
    fun freshUnfinishedOrUnknownNeverArchives() {
        assertFalse(
            ArchivePolicy.shouldArchive(
                TaskStatus.DONE, now - 6 * day, now, days = 7
            )
        )
        assertFalse(
            ArchivePolicy.shouldArchive(
                TaskStatus.PAUSED, now - 90 * day, now, days = 7
            )
        )
        assertFalse(
            ArchivePolicy.shouldArchive(
                TaskStatus.FAILED, now - 90 * day, now, days = 7
            )
        )
        // No finish time recorded (pre-v1.4 state): cannot judge.
        assertFalse(ArchivePolicy.shouldArchive(TaskStatus.DONE, 0, now, days = 7))
        // Setting off.
        assertFalse(
            ArchivePolicy.shouldArchive(
                TaskStatus.DONE, now - 90 * day, now, days = 0
            )
        )
    }

    @Test
    fun sweepMarksOnlyEligibleAndCounts() {
        val old = TaskRecord(url = "https://a/old").apply {
            status = TaskStatus.DONE
            finishedAt = now - 40 * day
        }
        val fresh = TaskRecord(url = "https://a/new").apply {
            status = TaskStatus.DONE
            finishedAt = now - day
        }
        val already = TaskRecord(url = "https://a/arch").apply {
            status = TaskStatus.DONE
            finishedAt = now - 40 * day
            archived = true
        }
        val changed = ArchivePolicy.sweep(listOf(old, fresh, already), now, days = 30)
        assertEquals(1, changed)
        assertTrue(old.archived)
        assertFalse(fresh.archived)
        assertTrue(already.archived)
    }

    @Test
    fun archivedFlagRoundTrips() {
        val t = TaskRecord(url = "https://a/f").apply { archived = true }
        val back = TaskStore.deserialize(TaskStore.serialize(listOf(t)))
        assertEquals(1, back.size)
        assertTrue(back[0].archived)
        // Records saved before v2.4 have no field: default false.
        val legacy = TaskStore.deserialize(
            "[{\"id\":\"x\",\"url\":\"https://a/f\",\"status\":\"DONE\"}]"
        )
        assertEquals(1, legacy.size)
        assertFalse(legacy[0].archived)
    }
}
