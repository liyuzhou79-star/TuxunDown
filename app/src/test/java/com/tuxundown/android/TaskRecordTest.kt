package com.tuxundown.android

import com.tuxundown.android.core.ConnInfo
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.3 (audit round 38): TaskRecord.displayConnections — the
 * one count a surface may print as a present-tense
 * "N 条连接". threads is the scaler TARGET (the connCapHeld
 * card line calls it 想要 N 条）; while RUNNING the sampler's
 * live snapshot (connInfos) is the truth.
 */
class TaskRecordTest {

    private fun conn(): ConnInfo = ConnInfo(chunkIndex = 0, downloaded = 0, total = 0, speedBps = 0)

    @Test
    fun runningTaskReportsTheLiveSnapshotNotTheTarget() {
        // The round-38 shape: target 16, cap (or the GrowthRamp
        // climb) holding the live count at 5 — the card's 分片
        // row must say 5, agreeing with the cap line's
        // 现在 5 条在跑 on the same card.
        val t = TaskRecord(
            url = "https://a.com/f.bin", status = TaskStatus.RUNNING,
            threads = 16, connInfos = List(5) { conn() },
        )
        assertEquals(5, t.displayConnections)
    }

    @Test
    fun runningTaskWithoutASnapshotYetFallsBackToTheTarget() {
        // The sampler's first tick has not landed: no live
        // count exists, so the target is the only number.
        val t = TaskRecord(
            url = "https://a.com/f.bin", status = TaskStatus.RUNNING,
            threads = 16, connInfos = emptyList(),
        )
        assertEquals(16, t.displayConnections)
    }

    @Test
    fun stoppedTasksReportTheTarget() {
        // connInfos is cleared when a run ends; a stale snapshot
        // must never pose as a live count for a task that is
        // not running anyway.
        for (status in listOf(
            TaskStatus.PAUSED, TaskStatus.QUEUED,
            TaskStatus.FAILED, TaskStatus.DONE,
        )) {
            val t = TaskRecord(
                url = "https://a.com/f.bin", status = status,
                threads = 8, connInfos = List(3) { conn() },
            )
            assertEquals(status.name, 8, t.displayConnections)
        }
    }
}
