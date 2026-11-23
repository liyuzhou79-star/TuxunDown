package com.tuxundown.android

import com.tuxundown.android.core.ShareList
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareListTest {

    private fun task(
        url: String,
        name: String = "",
        status: TaskStatus = TaskStatus.QUEUED,
    ) = TaskRecord(url = url, fileName = name, status = status)

    @Test
    fun buildListsNameThenLinkPerTask() {
        val text = ShareList.build(
            listOf(
                task("https://a.example.com/f.zip", "f.zip"),
                task("https://b.example.com/g.iso"),
            )
        )
        assertTrue(text.contains("共 2 个"))
        // Name line directly above its link line.
        assertTrue(text.contains("f.zip\nhttps://a.example.com/f.zip"))
        // A task without a name contributes just the link line.
        assertTrue(text.contains("\nhttps://b.example.com/g.iso\n"))
        // The how-to line points at BOTH import doors.
        assertTrue(text.contains("批量粘贴"))
        assertTrue(text.contains(".txt"))
    }

    @Test
    fun buildNeverCarriesHeadersOrMirrors() {
        val t = task("https://a.example.com/f.zip", "f.zip")
        t.headers["Cookie"] = "session=secret"
        t.sources.add("https://mirror.example.com/f.zip")
        val text = ShareList.build(listOf(t))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("mirror.example.com"))
    }

    @Test
    fun unfinishedDropsOnlyDone() {
        val all = listOf(
            task("https://a/1", status = TaskStatus.QUEUED),
            task("https://a/2", status = TaskStatus.RUNNING),
            task("https://a/3", status = TaskStatus.PAUSED),
            task("https://a/4", status = TaskStatus.FAILED),
            task("https://a/5", status = TaskStatus.DONE),
        )
        assertEquals(
            listOf("https://a/1", "https://a/2", "https://a/3", "https://a/4"),
            ShareList.unfinished(all).map { it.url },
        )
    }

    @Test
    fun credentialHintAppearsOnlyWhenALinkCarriesQueryOrUserinfo() {
        // v7.2 (audit round 57): one reminder line, only when a
        // link actually carries a query string or userinfo —
        // the links themselves stay whole (re-import needs
        // them) and the rest of the format is unchanged.
        val signed = ShareList.build(
            listOf(task("https://a.example.com/f.zip?sig=fake1", "f.zip"))
        )
        assertTrue(signed.contains(ShareList.CREDENTIAL_HINT_LINE))
        assertTrue(signed.contains("https://a.example.com/f.zip?sig=fake1"))
        val userinfo = ShareList.build(
            listOf(task("https://fakeuser:fakepass@a.example.com/f.zip"))
        )
        assertTrue(userinfo.contains(ShareList.CREDENTIAL_HINT_LINE))
        val plain = ShareList.build(
            listOf(task("https://a.example.com/f.zip", "f.zip"))
        )
        assertFalse(plain.contains(ShareList.CREDENTIAL_HINT_LINE))
        assertTrue(ShareList.credentialHint(listOf("https://a.b/f.zip")) == null)
    }

    @Test
    fun emptyListIsJustTheHeader() {
        val text = ShareList.build(emptyList())
        assertTrue(text.contains("共 0 个"))
    }
}
