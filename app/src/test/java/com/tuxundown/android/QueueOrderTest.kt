package com.tuxundown.android

import com.tuxundown.android.core.QueueOrder
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4: queue ordering — 插队 and scheduled eligibility. */
class QueueOrderTest {

    private fun task(status: TaskStatus, scheduledAt: Long = 0): TaskRecord =
        TaskRecord(url = "https://x/${System.nanoTime()}", status = status, scheduledAt = scheduledAt)

    @Test
    fun jumpQueueMovesBehindRunning() {
        val running = task(TaskStatus.RUNNING)
        val q1 = task(TaskStatus.QUEUED)
        val q2 = task(TaskStatus.QUEUED)
        val done = task(TaskStatus.DONE)
        val list = mutableListOf(done, q1, running, q2)
        assertTrue(QueueOrder.moveToFrontOfQueue(list, q2))
        // q2 is now the first QUEUED; the running task is untouched.
        assertEquals(q2, QueueOrder.nextEligible(list, Long.MAX_VALUE))
        assertEquals(TaskStatus.RUNNING, list.first { it.status == TaskStatus.RUNNING }.status)
    }

    @Test
    fun jumpQueueOnFirstQueuedIsNoop() {
        val q1 = task(TaskStatus.QUEUED)
        val q2 = task(TaskStatus.QUEUED)
        val list = mutableListOf(q1, q2)
        assertFalse(QueueOrder.moveToFrontOfQueue(list, q1))
        assertEquals(listOf(q1, q2), list)
    }

    @Test
    fun scheduledTasksWaitForTheirTime() {
        val future = task(TaskStatus.QUEUED, scheduledAt = 10_000)
        val now = task(TaskStatus.QUEUED)
        val list = listOf(future, now)
        assertEquals(now, QueueOrder.nextEligible(list, 5_000))
        assertEquals(future, QueueOrder.nextEligible(list, 10_000))
        assertEquals(10_000L, QueueOrder.nextScheduledAt(list, 5_000))
        assertEquals(0L, QueueOrder.nextScheduledAt(list, 20_000))
    }

    @Test
    fun excludedIdsAreSkipped() {
        val q1 = task(TaskStatus.QUEUED)
        val q2 = task(TaskStatus.QUEUED)
        assertEquals(q2, QueueOrder.nextEligible(listOf(q1, q2), 0, setOf(q1.id)))
    }

    // ---- v3.1: three priority tiers ----

    private fun prioTask(priority: Int, scheduledAt: Long = 0): TaskRecord =
        task(TaskStatus.QUEUED, scheduledAt).apply { this.priority = priority }

    @Test
    fun highTierStartsBeforeEarlierNormal() {
        val normal = prioTask(0)
        val high = prioTask(1)
        // The high-tier task was queued LATER (list order loses)
        // but starts first.
        assertEquals(high, QueueOrder.nextEligible(listOf(normal, high), 0))
    }

    @Test
    fun lowTierWaitsBehindNormalAndHigh() {
        val low = prioTask(-1)
        val normal = prioTask(0)
        val high = prioTask(1)
        val list = listOf(low, normal, high)
        assertEquals(high, QueueOrder.nextEligible(list, 0))
        assertEquals(
            normal,
            QueueOrder.nextEligible(list, 0, setOf(high.id)),
        )
        assertEquals(
            low,
            QueueOrder.nextEligible(list, 0, setOf(high.id, normal.id)),
        )
    }

    @Test
    fun withinATierListOrderStillDecides() {
        val first = prioTask(1)
        val second = prioTask(1)
        assertEquals(first, QueueOrder.nextEligible(listOf(first, second), 0))
    }

    @Test
    fun scheduledHighDoesNotBlockDueNormal() {
        // Eligibility is judged before priority: a 高 task whose
        // scheduled time has not come must not starve a due task.
        val futureHigh = prioTask(1, scheduledAt = 10_000)
        val dueNormal = prioTask(0)
        assertEquals(
            dueNormal,
            QueueOrder.nextEligible(listOf(futureHigh, dueNormal), 5_000),
        )
        assertEquals(
            futureHigh,
            QueueOrder.nextEligible(listOf(futureHigh, dueNormal), 10_000),
        )
    }
}
