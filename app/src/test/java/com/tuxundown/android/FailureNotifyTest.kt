package com.tuxundown.android

import com.tuxundown.android.core.FailureNotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v6.8: failure-notification merging + wording verdicts. */
class FailureNotifyTest {

    private fun failed(
        name: String,
        at: Long,
        reason: String = "网络好像断了",
        needsUserAction: Boolean = false,
        advice: String? = null,
    ) = FailureNotify.Failed(name, "id-$name", reason, needsUserAction, advice, at)

    @Test
    fun windowBoundaryIsInclusiveAt15000() {
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        // Exactly 15_000 ms later the first entry is still in.
        list = FailureNotify.add(list, failed("b.zip", 15_000), 15_000)
        assertEquals(2, list.size)
        assertTrue(FailureNotify.isMerged(list))
    }

    @Test
    fun entryExpiresAt15001() {
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        list = FailureNotify.add(list, failed("b.zip", 15_001), 15_001)
        assertEquals(1, list.size)
        assertEquals("b.zip", list[0].name)
        assertFalse(FailureNotify.isMerged(list))
    }

    @Test
    fun steadyStreamKeepsMergingByOwnTimestamps() {
        // Sliding window: each entry expires by its OWN time, so
        // failures each <15 s after the previous keep merging —
        // the same caliber CompletionMerge documents.
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        list = FailureNotify.add(list, failed("b.zip", 14_000), 14_000)
        list = FailureNotify.add(list, failed("c.zip", 28_000), 28_000)
        assertEquals(2, list.size)
        assertEquals(listOf("b.zip", "c.zip"), list.map { it.name })
    }

    @Test
    fun mergedTitleAndSummaryListNames() {
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        list = FailureNotify.add(list, failed("b.zip", 5_000), 5_000)
        list = FailureNotify.add(list, failed("c.zip", 9_000), 9_000)
        assertTrue(FailureNotify.isMerged(list))
        assertEquals("3 个文件下载失败", FailureNotify.title(list))
        assertEquals("a.zip\nb.zip\nc.zip", FailureNotify.summaryText(list))
        assertEquals("a.zip\nb.zip\nc.zip", FailureNotify.lines(list))
    }

    @Test
    fun ordinarySingleWordingAndRetry() {
        val item = failed("movie.mp4", 0, reason = "服务器那边出错了（HTTP 500）")
        assertEquals("下载失败", FailureNotify.singleTitle(item))
        assertEquals(
            "movie.mp4：服务器那边出错了（HTTP 500）",
            FailureNotify.singleBody(item),
        )
        assertTrue(FailureNotify.retryAllowed(item))
    }

    @Test
    fun needsUserActionWordingUsesAdviceAndDropsRetry() {
        val advice = "下载文件夹的授权失效了，光重试没用：去设置里重新选一次下载目录。"
        val item = failed(
            "book.pdf", 0,
            reason = "下载文件夹打不开了",
            needsUserAction = true,
            advice = advice,
        )
        assertEquals("下载失败，需要先处理", FailureNotify.singleTitle(item))
        assertEquals("book.pdf：$advice", FailureNotify.singleBody(item))
        assertFalse(FailureNotify.retryAllowed(item))
    }

    @Test
    fun needsUserActionWithoutAdviceFallsBackToReason() {
        val item = failed(
            "book.pdf", 0,
            reason = "存储设备出问题了",
            needsUserAction = true,
            advice = null,
        )
        assertEquals("book.pdf：存储设备出问题了", FailureNotify.singleBody(item))
        assertFalse(FailureNotify.retryAllowed(item))
    }

    @Test
    fun reasonTruncatesToReadableLength() {
        val long = "错".repeat(200)
        val out = FailureNotify.shortReason(long)
        assertEquals(FailureNotify.MAX_REASON_CHARS, out.length)
        assertTrue(out.endsWith("…"))
        // Exactly at the limit: untouched, no ellipsis.
        val exact = "错".repeat(FailureNotify.MAX_REASON_CHARS)
        assertEquals(exact, FailureNotify.shortReason(exact))
        // Short reasons pass through as-is.
        assertEquals("网络超时", FailureNotify.shortReason("网络超时"))
    }

    @Test
    fun reasonCollapsesWhitespaceAndBlankStaysBlank() {
        assertEquals("第一行 第二行", FailureNotify.shortReason("  第一行\n  第二行  "))
        assertEquals("", FailureNotify.shortReason("   "))
        // Blank reason renders the plain fallback body, never a
        // fabricated cause.
        val item = failed("x.bin", 0, reason = "")
        assertEquals(
            "x.bin 下载失败，点开任务可以查看原因。",
            FailureNotify.singleBody(item),
        )
    }

    // ---- v7.5 (audit round 60, Step C): prune on record deletion ----

    @Test
    fun pruneRemovesOnlyTheDeletedTasksEntry() {
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        list = FailureNotify.add(list, failed("b.zip", 1_000), 1_000)
        list = FailureNotify.add(list, failed("c.zip", 2_000), 2_000)
        val pruned = FailureNotify.prune(list, setOf("id-b.zip"))
        assertEquals(listOf("a.zip", "c.zip"), pruned.map { it.name })
    }

    @Test
    fun pruneCanEmptyTheListSoTheNotificationCanBeCancelled() {
        // The service cancels the failure notification exactly
        // when this prune leaves nothing behind.
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        assertTrue(FailureNotify.prune(list, setOf("id-a.zip")).isEmpty())
    }

    @Test
    fun pruneWithUnknownIdsIsANoOp() {
        var list = emptyList<FailureNotify.Failed>()
        list = FailureNotify.add(list, failed("a.zip", 0), 0)
        assertEquals(list, FailureNotify.prune(list, setOf("id-zzz")))
        assertEquals(list, FailureNotify.prune(list, emptySet()))
    }
}
