package com.tuxundown.android

import com.tuxundown.android.core.Finalizing
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.5 (audit round 60, Step B): the finalize-window display
 * derivation. DONE is persisted BEFORE the finalize lane
 * renames the .part, and savedUri lands only when finalize
 * completes — so DONE + blank savedUri is 正在收尾， never
 * 已完成. The predicate is the record-side half of
 * FinalizeRescue.needsRescue (see Finalizing's doc).
 */
class FinalizingTest {

    private fun task(
        status: TaskStatus,
        savedUri: String = "",
    ): TaskRecord {
        val t = TaskRecord(url = "https://a.com/f.bin", status = status)
        t.savedUri = savedUri
        return t
    }

    @Test
    fun doneWithBlankSavedUriIsFinalizing() {
        assertTrue(Finalizing.isFinalizing(task(TaskStatus.DONE, savedUri = "")))
    }

    @Test
    fun doneWithSavedUriIsFinishedNotFinalizing() {
        assertFalse(
            Finalizing.isFinalizing(
                task(TaskStatus.DONE, savedUri = "content://media/download/f.bin")
            )
        )
    }

    @Test
    fun everyOtherStatusIsNotFinalizing() {
        // Even with a blank savedUri (the normal state of an
        // unfinished record), no non-DONE status is finalizing.
        for (status in TaskStatus.values()) {
            if (status == TaskStatus.DONE) continue
            assertFalse(
                "status $status must not read as finalizing",
                Finalizing.isFinalizing(task(status, savedUri = "")),
            )
            assertFalse(
                Finalizing.isFinalizing(
                    task(status, savedUri = "content://media/download/f.bin")
                )
            )
        }
    }

    @Test
    fun whitespaceOnlySavedUriStillCountsAsBlank() {
        // isBlank semantics, matching FinalizeRescue's own
        // savedUri check — a whitespace Uri is no Uri.
        assertTrue(Finalizing.isFinalizing(task(TaskStatus.DONE, savedUri = "  ")))
    }

    @Test
    fun countAndAnyAggregateOverASnapshot() {
        val tasks = listOf(
            task(TaskStatus.DONE, savedUri = ""),
            task(TaskStatus.DONE, savedUri = ""),
            task(TaskStatus.DONE, savedUri = "content://media/download/f.bin"),
            task(TaskStatus.RUNNING),
            task(TaskStatus.QUEUED),
            task(TaskStatus.PAUSED),
            task(TaskStatus.FAILED),
        )
        assertEquals(2, Finalizing.count(tasks))
        assertTrue(Finalizing.anyFinalizing(tasks))
    }

    @Test
    fun emptyAndIdleSnapshotsHaveNoFinalizing() {
        assertEquals(0, Finalizing.count(emptyList()))
        assertFalse(Finalizing.anyFinalizing(emptyList()))
        val finished = listOf(
            task(TaskStatus.DONE, savedUri = "content://media/download/f.bin"),
        )
        assertEquals(0, Finalizing.count(finished))
        assertFalse(Finalizing.anyFinalizing(finished))
    }
}
