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

    // ---- v6.9: help group (topics injected for determinism) ----

    private fun topic(title: String, body: String) =
        com.tuxundown.android.core.HelpTopics.Topic(title, body)

    @Test
    fun helpTitleKeywordHits() {
        val topics = listOf(
            topic("单线程是什么意思？", "正文没有那个词。"),
            topic("别的话题", "也无关。"),
        )
        val r = GlobalSearch.search(emptyList(), emptyList(), "单线程", topics)
        assertEquals(listOf("单线程是什么意思？"), r.help.map { it.title })
    }

    @Test
    fun helpBodyKeywordHits() {
        val topics = listOf(
            topic("别的话题", "正文里提到了校验文件这件事。"),
            topic("另一个话题", "完全无关。"),
        )
        val r = GlobalSearch.search(emptyList(), emptyList(), "校验文件", topics)
        assertEquals(listOf("别的话题"), r.help.map { it.title })
    }

    @Test
    fun helpTitleHitsRankBeforeBodyOnlyHits() {
        // The body-only hit comes FIRST in the topic list on
        // purpose: ranking must still put the title hit ahead.
        val topics = listOf(
            topic("先出现的话题", "正文提到了限速。"),
            topic("限速计划是什么？", "正文无关。"),
            topic("后出现的话题", "正文也提到了限速。"),
        )
        val r = GlobalSearch.search(emptyList(), emptyList(), "限速", topics)
        assertEquals(
            listOf("限速计划是什么？", "先出现的话题", "后出现的话题"),
            r.help.map { it.title },
        )
    }

    @Test
    fun helpOnlyHitMakesResultNotEmpty() {
        val topics = listOf(topic("独有话题", "正文。"))
        val r = GlobalSearch.search(emptyList(), emptyList(), "独有话题", topics)
        assertTrue(r.tasks.isEmpty() && r.files.isEmpty() && r.history.isEmpty())
        assertEquals(1, r.help.size)
        assertTrue(!r.isEmpty)
    }

    @Test
    fun blankQueryHasNoHelpHits() {
        val topics = listOf(topic("下载话题", "下载正文。"))
        val r = GlobalSearch.search(emptyList(), emptyList(), "   ", topics)
        assertTrue(r.help.isEmpty())
        assertTrue(r.isEmpty)
    }

    @Test
    fun helpHitsAreCappedAtTen() {
        val topics = (1..15).map { topic("下载话题 $it", "正文 $it") }
        val r = GlobalSearch.search(emptyList(), emptyList(), "下载", topics)
        assertEquals(GlobalSearch.MAX_HELP_HITS, r.help.size)
        assertEquals(10, r.help.size)
        // The cap keeps the earliest title hits, in order.
        assertEquals("下载话题 1", r.help.first().title)
        assertEquals("下载话题 10", r.help.last().title)
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
