package com.tuxundown.android

import com.tuxundown.android.core.IllegalTransitions
import com.tuxundown.android.core.StateEvent
import com.tuxundown.android.core.StatusDistribution
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.4 (audit round 59, Step C): the diagnostics
 * 「任务状态分布」 assembly. Fixed fake inputs only — the
 * point is the counting caliber and the absence rules:
 * what cannot be read is absent, never replaced by a
 * hard-coded figure, and the illegal-transition figure is
 * always worded as this process's count.
 */
class StatusDistributionTest {

    private fun fact(
        status: TaskStatus,
        net: Boolean = false,
        charge: Boolean = false,
        portal: Boolean = false,
    ) = StatusDistribution.RecordFact(status, net, charge, portal)

    private fun snapshot(
        records: List<StatusDistribution.RecordFact>? = null,
        claimedCount: Int? = null,
        illegalCount: Int? = null,
        lastIllegal: IllegalTransitions.LastIllegal? = null,
    ) = StatusDistribution.Snapshot(
        records, claimedCount, illegalCount, lastIllegal,
    )

    @Test
    fun allReadsFailedMeansTheWholeSectionIsAbsent() {
        assertTrue(StatusDistribution.lines(snapshot()).isEmpty())
    }

    @Test
    fun emptyRecordSetShowsRealZeros() {
        val rows = StatusDistribution.lines(
            snapshot(records = emptyList(), claimedCount = 0, illegalCount = 0)
        )
        val status = rows.first { it.first == "状态分布" }
        assertTrue(status.second.contains("排队 0 个"))
        assertTrue(status.second.contains("进行中 0 个"))
        assertTrue(status.second.contains("已暂停 0 个"))
        assertTrue(status.second.contains("已完成 0 个"))
        assertTrue(status.second.contains("失败 0 个"))
        val parks = rows.first { it.first == "停泊中" }
        assertTrue(parks.second.contains("断网停泊 0 个"))
        assertTrue(parks.second.contains("充电停泊 0 个"))
        assertTrue(parks.second.contains("门户停泊 0 个"))
    }

    @Test
    fun perStatusCountsComeFromTheRecordSnapshot() {
        val rows = StatusDistribution.lines(
            snapshot(
                records = listOf(
                    fact(TaskStatus.QUEUED),
                    fact(TaskStatus.QUEUED),
                    fact(TaskStatus.RUNNING),
                    fact(TaskStatus.PAUSED),
                    fact(TaskStatus.DONE),
                    fact(TaskStatus.DONE),
                    fact(TaskStatus.DONE),
                    fact(TaskStatus.FAILED),
                )
            )
        )
        val status = rows.first { it.first == "状态分布" }
        assertTrue(status.second.contains("排队 2 个"))
        assertTrue(status.second.contains("进行中 1 个"))
        assertTrue(status.second.contains("已暂停 1 个"))
        assertTrue(status.second.contains("已完成 3 个"))
        assertTrue(status.second.contains("失败 1 个"))
    }

    @Test
    fun parkFlagsCountIndependentlyPerFlag() {
        // One record can carry several park flags at once;
        // each flag's count is its own tally.
        val rows = StatusDistribution.lines(
            snapshot(
                records = listOf(
                    fact(TaskStatus.QUEUED, net = true, charge = true),
                    fact(TaskStatus.QUEUED, net = true, portal = true),
                    fact(TaskStatus.QUEUED, charge = true),
                    fact(TaskStatus.RUNNING),
                )
            )
        )
        val parks = rows.first { it.first == "停泊中" }
        assertTrue(parks.second.contains("断网停泊 2 个"))
        assertTrue(parks.second.contains("充电停泊 2 个"))
        assertTrue(parks.second.contains("门户停泊 1 个"))
    }

    @Test
    fun unreadableRecordsDropStatusAndParkRowsOnly() {
        val rows = StatusDistribution.lines(
            snapshot(records = null, claimedCount = 2, illegalCount = 1)
        )
        assertEquals(listOf("认领中", "非法迁移"), rows.map { it.first })
    }

    @Test
    fun claimedRowShowsRealZeroAndDropsNegativeOrUnreadable() {
        val zero = StatusDistribution.lines(snapshot(claimedCount = 0))
        assertEquals("共 0 个任务正被引擎认领", zero.first { it.first == "认领中" }.second)
        assertTrue(StatusDistribution.lines(snapshot(claimedCount = -1)).isEmpty())
        val three = StatusDistribution.lines(snapshot(claimedCount = 3))
        assertTrue(three.first { it.first == "认领中" }.second.contains("共 3 个"))
    }

    @Test
    fun illegalRowStatesTheProcessScopeAndZeroHasNoLastDetail() {
        val rows = StatusDistribution.lines(
            snapshot(
                illegalCount = 0,
                lastIllegal = IllegalTransitions.LastIllegal(
                    "task-1", TaskStatus.DONE, StateEvent.RESUME, "enqueue",
                ),
            )
        )
        val row = rows.first { it.first == "非法迁移" }
        assertTrue(row.second.contains("本次运行（本进程内）累计 0 次"))
        assertFalse(row.second.contains("最近一次"))
    }

    @Test
    fun illegalRowAppendsLastWithIdFromEventSourceOnly() {
        val rows = StatusDistribution.lines(
            snapshot(
                illegalCount = 2,
                lastIllegal = IllegalTransitions.LastIllegal(
                    "task-9", TaskStatus.RUNNING, StateEvent.RESUME, "enqueue",
                ),
            )
        )
        val row = rows.first { it.first == "非法迁移" }
        assertTrue(row.second.contains("累计 2 次"))
        assertTrue(row.second.contains("任务 task-9"))
        assertTrue(row.second.contains("RUNNING"))
        assertTrue(row.second.contains("RESUME"))
        assertTrue(row.second.contains("enqueue"))
        assertFalse(row.second.contains("http"))
    }

    @Test
    fun illegalRowDropsNegativeOrUnreadableCount() {
        assertTrue(StatusDistribution.lines(snapshot(illegalCount = -3)).isEmpty())
    }

    @Test
    fun fullSnapshotShowsEveryRowInOrder() {
        val rows = StatusDistribution.lines(
            snapshot(
                records = listOf(fact(TaskStatus.QUEUED, net = true)),
                claimedCount = 1,
                illegalCount = 0,
            )
        )
        assertEquals(
            listOf("状态分布", "停泊中", "认领中", "非法迁移"),
            rows.map { it.first },
        )
    }
}
