package com.tuxundown.android

import com.tuxundown.android.core.BatchRename
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.2: batch rename rules — literal replace, prefix/suffix
 *  placement, the never-erase-a-name guard and who is eligible. */
class BatchRenameTest {

    @Test
    fun replaceIsLiteralNotRegex() {
        // As a regex, "a.c" would also match "abc"; literally it
        // must match only the exact characters a . c — so "abc"
        // keeps its name and only a real "a.c" is replaced.
        assertEquals("abc", BatchRename.apply("abc", "a.c", "X", "", ""))
        assertEquals("X", BatchRename.apply("a.c", "a.c", "X", "", ""))
    }

    @Test
    fun replaceHitsEveryOccurrenceIncludingExtension() {
        assertEquals(
            "video-part1.mkv",
            BatchRename.apply("video.part1.mkv", ".part", "-part", "", ""),
        )
        assertEquals(
            "a-b-a.txt",
            BatchRename.apply("a.b.a.txt", ".", "-", "", ""),
        )
        assertEquals(
            "file.rar",
            BatchRename.apply("file.zip", ".zip", ".rar", "", ""),
        )
    }

    @Test
    fun prefixAtStartSuffixBeforeExtension() {
        assertEquals(
            "2026-movie_hd.mp4",
            BatchRename.apply("movie.mp4", "", "", "2026-", "_hd"),
        )
        assertEquals(
            "movie_hd",
            BatchRename.apply("movie", "", "", "", "_hd"),
        )
        // A leading dot is not an extension.
        assertEquals(
            ".zip_hd",
            BatchRename.apply(".zip", "", "", "", "_hd"),
        )
    }

    @Test
    fun ruleThatWouldEraseTheNameIsRefused() {
        assertEquals(
            "abc",
            BatchRename.apply("abc", "abc", "", "", ""),
        )
        assertEquals(
            "abc.txt",
            BatchRename.apply("abc.txt", "abc.txt", "", "", ""),
        )
    }

    @Test
    fun emptyRuleChangesNothing() {
        assertEquals(
            "movie.mp4",
            BatchRename.apply("movie.mp4", "", "", "", ""),
        )
    }

    @Test
    fun eligibilityCoversIdleStatusesOnly() {
        assertTrue(BatchRename.eligible(TaskStatus.QUEUED))
        assertTrue(BatchRename.eligible(TaskStatus.PAUSED))
        assertTrue(BatchRename.eligible(TaskStatus.FAILED))
        assertFalse(BatchRename.eligible(TaskStatus.RUNNING))
        assertFalse(BatchRename.eligible(TaskStatus.DONE))
    }

    @Test
    fun planMarksIneligibleAndKeepsTheirNames() {
        val running = TaskRecord(url = "https://a.b/1.zip", fileName = "1.zip")
        running.status = TaskStatus.RUNNING
        val paused = TaskRecord(url = "https://a.b/2.zip", fileName = "2.zip")
        paused.status = TaskStatus.PAUSED
        val plan = BatchRename.plan(listOf(running, paused), "", "", "x-", "")
        assertEquals(2, plan.size)
        assertFalse(plan[0].eligible)
        assertFalse(plan[0].changed)
        assertTrue(plan[1].eligible)
        assertTrue(plan[1].changed)
        assertEquals("x-2.zip", plan[1].newName)
    }
}
