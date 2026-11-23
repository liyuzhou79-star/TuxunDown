package com.tuxundown.android

import com.tuxundown.android.core.CardQuickMenu
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v6.2 step B: the long-press quick menu's state -> items
 * table. Every verdict mirrors the card's own controls (see
 * CardQuickMenu's doc): an item the card would not offer in
 * that state must not appear in the menu either.
 */
class CardQuickMenuTest {

    private fun items(
        status: TaskStatus,
        selecting: Boolean = false,
        url: String = "",
    ) = CardQuickMenu.itemsFor(status, selecting, url)

    @Test
    fun running_showsPause_neverResumeOrJump() {
        val it = items(TaskStatus.RUNNING)
        assertTrue(it.contains(CardQuickMenu.Item.PAUSE))
        assertFalse(it.contains(CardQuickMenu.Item.RESUME))
        assertFalse(it.contains(CardQuickMenu.Item.JUMP_QUEUE))
    }

    @Test
    fun queued_showsPauseAndJumpQueue_neverResume() {
        val it = items(TaskStatus.QUEUED)
        assertTrue(it.contains(CardQuickMenu.Item.PAUSE))
        assertTrue(it.contains(CardQuickMenu.Item.JUMP_QUEUE))
        assertFalse(it.contains(CardQuickMenu.Item.RESUME))
    }

    @Test
    fun paused_showsResume_neverPauseOrJump() {
        val it = items(TaskStatus.PAUSED)
        assertTrue(it.contains(CardQuickMenu.Item.RESUME))
        assertFalse(it.contains(CardQuickMenu.Item.PAUSE))
        assertFalse(it.contains(CardQuickMenu.Item.JUMP_QUEUE))
    }

    @Test
    fun failed_showsResume_neverPauseOrJump() {
        val it = items(TaskStatus.FAILED)
        assertTrue(it.contains(CardQuickMenu.Item.RESUME))
        assertFalse(it.contains(CardQuickMenu.Item.PAUSE))
        assertFalse(it.contains(CardQuickMenu.Item.JUMP_QUEUE))
    }

    @Test
    fun done_hidesPauseResumeJumpAndAllPriorities() {
        val it = items(TaskStatus.DONE)
        assertFalse(it.contains(CardQuickMenu.Item.PAUSE))
        assertFalse(it.contains(CardQuickMenu.Item.RESUME))
        assertFalse(it.contains(CardQuickMenu.Item.JUMP_QUEUE))
        assertFalse(it.contains(CardQuickMenu.Item.PRIORITY_HIGH))
        assertFalse(it.contains(CardQuickMenu.Item.PRIORITY_NORMAL))
        assertFalse(it.contains(CardQuickMenu.Item.PRIORITY_LOW))
    }

    @Test
    fun everyNonDoneState_showsAllThreePriorities() {
        for (status in TaskStatus.values()) {
            if (status == TaskStatus.DONE) continue
            val it = items(status)
            assertTrue("$status high", it.contains(CardQuickMenu.Item.PRIORITY_HIGH))
            assertTrue("$status normal", it.contains(CardQuickMenu.Item.PRIORITY_NORMAL))
            assertTrue("$status low", it.contains(CardQuickMenu.Item.PRIORITY_LOW))
        }
    }

    @Test
    fun noteLinkDiagnosticsDelete_showInEveryState() {
        for (status in TaskStatus.values()) {
            val it = items(status)
            assertTrue("$status note", it.contains(CardQuickMenu.Item.EDIT_NOTE))
            assertTrue("$status link", it.contains(CardQuickMenu.Item.COPY_LINK))
            assertTrue("$status diag", it.contains(CardQuickMenu.Item.COPY_DIAGNOSTICS))
            assertTrue("$status delete", it.contains(CardQuickMenu.Item.DELETE))
        }
    }

    @Test
    fun multiSelect_showsOnlyWhenNotSelecting() {
        for (status in TaskStatus.values()) {
            assertTrue(
                "$status not selecting",
                items(status, selecting = false)
                    .contains(CardQuickMenu.Item.MULTI_SELECT),
            )
            assertFalse(
                "$status selecting",
                items(status, selecting = true)
                    .contains(CardQuickMenu.Item.MULTI_SELECT),
            )
        }
    }

    @Test
    fun queued_fullListAndOrder_pinned() {
        assertEquals(
            listOf(
                CardQuickMenu.Item.PAUSE,
                CardQuickMenu.Item.JUMP_QUEUE,
                CardQuickMenu.Item.PRIORITY_HIGH,
                CardQuickMenu.Item.PRIORITY_NORMAL,
                CardQuickMenu.Item.PRIORITY_LOW,
                CardQuickMenu.Item.EDIT_NOTE,
                CardQuickMenu.Item.COPY_LINK,
                CardQuickMenu.Item.COPY_DIAGNOSTICS,
                CardQuickMenu.Item.DELETE,
                CardQuickMenu.Item.MULTI_SELECT,
            ),
            items(TaskStatus.QUEUED),
        )
    }

    @Test
    fun done_fullListAndOrder_pinned() {
        // The helper's default url is "" — an adopted record's
        // shape — so this list is also the no-RE_DOWNLOAD pin.
        assertEquals(
            listOf(
                CardQuickMenu.Item.EDIT_NOTE,
                CardQuickMenu.Item.COPY_LINK,
                CardQuickMenu.Item.COPY_DIAGNOSTICS,
                CardQuickMenu.Item.DELETE,
                CardQuickMenu.Item.MULTI_SELECT,
            ),
            items(TaskStatus.DONE),
        )
    }

    // ---- v8.5: 「再下一次」 (RE_DOWNLOAD) ----

    @Test
    fun canRedownload_doneWithUrl_only() {
        assertTrue(
            CardQuickMenu.canRedownload(
                TaskStatus.DONE, "https://example.com/f.zip",
            ),
        )
        assertFalse(CardQuickMenu.canRedownload(TaskStatus.DONE, ""))
        assertFalse(CardQuickMenu.canRedownload(TaskStatus.DONE, "  "))
        for (status in TaskStatus.values()) {
            if (status == TaskStatus.DONE) continue
            assertFalse(
                "$status",
                CardQuickMenu.canRedownload(
                    status, "https://example.com/f.zip",
                ),
            )
        }
    }

    @Test
    fun redownload_doneWithUrl_shows() {
        assertTrue(
            items(TaskStatus.DONE, url = "https://example.com/f.zip")
                .contains(CardQuickMenu.Item.RE_DOWNLOAD),
        )
    }

    @Test
    fun redownload_doneBlankUrl_hides() {
        assertFalse(
            items(TaskStatus.DONE, url = "")
                .contains(CardQuickMenu.Item.RE_DOWNLOAD),
        )
        assertFalse(
            items(TaskStatus.DONE, url = "  ")
                .contains(CardQuickMenu.Item.RE_DOWNLOAD),
        )
    }

    @Test
    fun redownload_nonDoneStates_hideEvenWithUrl() {
        for (status in TaskStatus.values()) {
            if (status == TaskStatus.DONE) continue
            assertFalse(
                "$status",
                items(status, url = "https://example.com/f.zip")
                    .contains(CardQuickMenu.Item.RE_DOWNLOAD),
            )
        }
    }

    @Test
    fun doneWithUrl_fullListAndOrder_pinned() {
        assertEquals(
            listOf(
                CardQuickMenu.Item.RE_DOWNLOAD,
                CardQuickMenu.Item.EDIT_NOTE,
                CardQuickMenu.Item.COPY_LINK,
                CardQuickMenu.Item.COPY_DIAGNOSTICS,
                CardQuickMenu.Item.DELETE,
                CardQuickMenu.Item.MULTI_SELECT,
            ),
            items(TaskStatus.DONE, url = "https://example.com/f.zip"),
        )
        // While selecting, MULTI_SELECT drops out (as always)
        // and RE_DOWNLOAD keeps its head position.
        assertEquals(
            listOf(
                CardQuickMenu.Item.RE_DOWNLOAD,
                CardQuickMenu.Item.EDIT_NOTE,
                CardQuickMenu.Item.COPY_LINK,
                CardQuickMenu.Item.COPY_DIAGNOSTICS,
                CardQuickMenu.Item.DELETE,
            ),
            items(
                TaskStatus.DONE,
                selecting = true,
                url = "https://example.com/f.zip",
            ),
        )
    }
}
