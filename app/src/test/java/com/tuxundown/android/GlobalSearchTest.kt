package com.tuxundown.android

import com.tuxundown.android.core.GlobalSearch
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {

    private fun task(
        url: String,
        name: String = "",
        note: String = "",
        status: TaskStatus = TaskStatus.QUEUED,
        savedUri: String = "",
        savedName: String = "",
        archived: Boolean = false,
    ): TaskRecord {
        val t = TaskRecord(url = url, fileName = name)
        t.userNote = note
        t.status = status
        t.savedUri = savedUri
        t.savedName = savedName
        t.archived = archived
        return t
    }

    @Test
    fun taskMatchesNameUrlNoteAndHost() {
        val t = task(
            "https://Media.Example.com/files/movie.mp4",
            name = "movie.mp4",
            note = "给娃下的那批",
        )
        assertTrue(GlobalSearch.taskMatches(t, "MOVIE"))
        assertTrue(GlobalSearch.taskMatches(t, "example.com"))
        assertTrue(GlobalSearch.taskMatches(t, "给娃"))
        assertTrue(GlobalSearch.taskMatches(t, "media.example"))
    }

    @Test
    fun blankQueryMatchesNothing() {
        val r = GlobalSearch.search(
            listOf(task("https://a.com/x.zip", name = "x.zip")),
            listOf("https://a.com/page"),
            "   ",
        )
        assertTrue(r.isEmpty)
    }

    @Test
    fun archivedTasksAreSearchable() {
        // Global search deliberately ignores tab/archived
        // placement: finding a folded-away record is the point.
        val t = task("https://a.com/old.zip", name = "old.zip", archived = true)
        val r = GlobalSearch.search(listOf(t), emptyList(), "old")
        assertEquals(listOf(t), r.tasks)
    }

    @Test
    fun fileHitsComeOnlyFromDoneTasksWithSavedFiles() {
        val done = task(
            "https://a.com/report.pdf", name = "report.pdf",
            status = TaskStatus.DONE, savedUri = "content://x/1",
            savedName = "年终报告.pdf",
        )
        val running = task(
            "https://a.com/report2.pdf", name = "report2.pdf",
            status = TaskStatus.RUNNING, savedUri = "content://x/2",
        )
        val doneNoFile = task(
            "https://a.com/report3.pdf", name = "report3.pdf",
            status = TaskStatus.DONE,
        )
        val r = GlobalSearch.search(listOf(done, running, doneNoFile), emptyList(), "报告")
        assertEquals(1, r.files.size)
        assertEquals("年终报告.pdf", r.files[0].name)
        assertEquals(done.id, r.files[0].taskId)
        // The saved display name is what matches — the task's
        // planned name was different.
        val r2 = GlobalSearch.search(listOf(done), emptyList(), "report.pdf")
        assertTrue(r2.files.isEmpty())
    }

    @Test
    fun historyMatchesUrlAndHost() {
        val history = listOf(
            "https://pan.example.net/share/abc",
            "https://other.org/page",
        )
        assertEquals(
            listOf("https://pan.example.net/share/abc"),
            GlobalSearch.search(emptyList(), history, "example").history,
        )
        assertEquals(
            listOf("https://other.org/page"),
            GlobalSearch.search(emptyList(), history, "page").history,
        )
        assertTrue(GlobalSearch.search(emptyList(), history, "zzz").isEmpty)
    }
}
