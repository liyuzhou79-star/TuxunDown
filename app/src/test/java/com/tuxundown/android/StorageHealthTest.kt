package com.tuxundown.android

import com.tuxundown.android.core.StorageHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.3 (audit round 58, Step C): the diagnostics
 * 「存储健康」 assembly. Fixed fake inputs only — the point
 * is the absence caliber: what cannot be read is absent,
 * never replaced by a hard-coded figure, and the
 * sub-store dropped-line figure is always worded as the
 * cross-store 合计 it is.
 */
class StorageHealthTest {

    private fun snapshot(
        taskCount: Int? = null,
        taskFileBytes: Long? = null,
        taskFileModifiedMs: Long? = null,
        taskHealth: StorageHealth.TaskHealth? = null,
        taskRecoveryText: String? = null,
        hostMemoryCount: Int? = null,
        statsDaysWithData: Int? = null,
        statsDoneCount: Int? = null,
        statsTotalBytes: Long? = null,
        sniffHistoryCount: Int? = null,
        prefFallbackKeys: Set<String>? = null,
        subStoreDroppedLines: Int? = null,
    ) = StorageHealth.Snapshot(
        taskCount, taskFileBytes, taskFileModifiedMs,
        taskHealth, taskRecoveryText, hostMemoryCount,
        statsDaysWithData, statsDoneCount, statsTotalBytes,
        sniffHistoryCount, prefFallbackKeys, subStoreDroppedLines,
    )

    @Test
    fun allReadsFailedMeansTheWholeSectionIsAbsent() {
        assertTrue(StorageHealth.lines(snapshot()).isEmpty())
    }

    @Test
    fun partialReadsShowOnlyTheRowsThatCouldBeRead() {
        val rows = StorageHealth.lines(snapshot(hostMemoryCount = 3))
        assertEquals(listOf("站点学习"), rows.map { it.first })
        assertEquals("共 3 条站点记录", rows[0].second)
    }

    @Test
    fun taskRowShowsNormalHealthAndRealFileFacts() {
        val rows = StorageHealth.lines(
            snapshot(
                taskCount = 12,
                taskFileBytes = 2048,
                taskFileModifiedMs = 1_700_000_000_000L,
                taskHealth = StorageHealth.TaskHealth.NORMAL,
            )
        )
        val row = rows.first { it.first == "任务存储" }
        assertTrue(row.second.contains("共 12 条任务"))
        assertTrue(row.second.contains("文件大小 2.0 KB"))
        assertTrue(row.second.contains("最近写入 "))
        assertTrue(row.second.contains("状态正常"))
        assertFalse(row.second.contains("已自愈过"))
    }

    @Test
    fun taskRowShowsRecoveredHealthWithTheEventText() {
        val rows = StorageHealth.lines(
            snapshot(
                taskCount = 5,
                taskHealth = StorageHealth.TaskHealth.RECOVERED,
                taskRecoveryText = "任务列表文件已损坏，已从备份恢复全部任务。",
            )
        )
        val row = rows.first { it.first == "任务存储" }
        assertTrue(row.second.contains("已自愈过"))
        assertTrue(row.second.contains("已从备份恢复全部任务"))
        assertFalse(row.second.contains("状态正常"))
    }

    @Test
    fun brokenFragmentsDropIndividuallyButRealZerosStay() {
        // Negative count/bytes and a non-positive timestamp
        // are broken reads: those fragments drop, the row
        // survives on its one good fragment.
        val rows = StorageHealth.lines(
            snapshot(
                taskCount = -1,
                taskFileBytes = -5,
                taskFileModifiedMs = 0,
                taskHealth = StorageHealth.TaskHealth.NORMAL,
            )
        )
        assertEquals("状态正常", rows.first { it.first == "任务存储" }.second)

        val zeros = StorageHealth.lines(
            snapshot(taskCount = 0, hostMemoryCount = 0, sniffHistoryCount = 0)
        )
        assertEquals(
            listOf("任务存储", "站点学习", "嗅探历史"),
            zeros.map { it.first },
        )
        assertTrue(zeros[0].second.contains("共 0 条任务"))
    }

    @Test
    fun statsRowCombinesDaysDoneAndTotal() {
        val rows = StorageHealth.lines(
            snapshot(
                statsDaysWithData = 3,
                statsDoneCount = 42,
                statsTotalBytes = 5L * 1024 * 1024,
            )
        )
        val row = rows.first { it.first == "下载统计" }
        assertTrue(row.second.contains("最近 7 天里有下载记录 3 天"))
        assertTrue(row.second.contains("完成任务共 42 个"))
        assertTrue(row.second.contains("累计下载 5.0 MB"))
    }

    @Test
    fun fallbackKeysRowListsSortedNamesOnlyWhenNonEmpty() {
        assertTrue(
            StorageHealth.lines(snapshot(prefFallbackKeys = emptySet())).isEmpty()
        )
        assertTrue(
            StorageHealth.lines(
                snapshot(prefFallbackKeys = setOf("", "  "))
            ).isEmpty()
        )
        val rows = StorageHealth.lines(
            snapshot(prefFallbackKeys = setOf("wifiOnly", "maxConcurrent"))
        )
        val row = rows.first { it.first == "设置回退" }
        assertTrue(row.second.contains("maxConcurrent、wifiOnly"))
        assertTrue(row.second.contains("已先用默认值"))
    }

    @Test
    fun droppedLinesRowIsWordedAsTheCrossStoreTotal() {
        // Zero dropped lines is the healthy case: no row.
        assertTrue(
            StorageHealth.lines(snapshot(subStoreDroppedLines = 0)).isEmpty()
        )
        val rows = StorageHealth.lines(snapshot(subStoreDroppedLines = 4))
        val row = rows.first { it.first == "子存储解析" }
        assertTrue(row.second.contains("合计丢弃了 4 行"))
        assertTrue(row.second.contains("合计"))
    }

    @Test
    fun fullSnapshotShowsEveryRowInOrder() {
        val rows = StorageHealth.lines(
            snapshot(
                taskCount = 1,
                taskHealth = StorageHealth.TaskHealth.NORMAL,
                hostMemoryCount = 2,
                statsDoneCount = 3,
                sniffHistoryCount = 4,
                prefFallbackKeys = setOf("wifiOnly"),
                subStoreDroppedLines = 5,
            )
        )
        assertEquals(
            listOf(
                "任务存储", "站点学习", "下载统计",
                "嗅探历史", "设置回退", "子存储解析",
            ),
            rows.map { it.first },
        )
    }
}
