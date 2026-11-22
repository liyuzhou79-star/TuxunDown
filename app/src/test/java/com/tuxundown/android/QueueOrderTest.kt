package com.tuxundown.android

import com.tuxundown.android.core.QueueOrder
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.4: queue ordering — 插队 and scheduled eligibility. */
class QueueOrderTest {

    // v4.8: the rotation memory is process-global state on the
    // QueueOrder object — isolate every test from it, so the
    // pre-v4.8 cases above keep judging the no-memory behaviour
    // they were written against.
    @Before
    fun clearRotationBefore() = QueueOrder.clearServedMemory()

    @After
    fun clearRotationAfter() = QueueOrder.clearServedMemory()

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

    // ---- v4.8: host rotation + jumpedAt ----

    private fun hostTask(
        host: String,
        priority: Int = 0,
        scheduledAt: Long = 0,
    ): TaskRecord =
        TaskRecord(
            url = "https://$host/f${System.nanoTime()}",
            status = TaskStatus.QUEUED,
            scheduledAt = scheduledAt,
            priority = priority,
        )

    /** Simulate a concurrency-1 drain: repeatedly pick, note the
     *  pick as served (exactly where the real drain does), and
     *  take it out of the queue. */
    private fun drainOrder(tasks: List<TaskRecord>): List<TaskRecord> {
        val remaining = tasks.toMutableList()
        val order = mutableListOf<TaskRecord>()
        while (true) {
            val next = QueueOrder.nextEligible(remaining, 0) ?: break
            QueueOrder.noteServed(next)
            order.add(next)
            remaining.remove(next)
        }
        return order
    }

    @Test
    fun singleHostDrainPicksExactlyAsBefore() {
        // One host only: rotation can never find a different
        // host, so a simulated drain must reproduce the plain
        // list-order picks of the pre-v4.8 implementation.
        val tasks = (1..5).map { hostTask("a.example.com") }
        assertEquals(tasks, drainOrder(tasks))
    }

    @Test
    fun twoHostsInterleaveSoTheLoneTaskIsNotStarved() {
        val a = (1..5).map { hostTask("a.example.com") }
        val b = hostTask("b.example.com")
        val order = drainOrder(a + b)
        // A first (no memory yet), then B jumps the queue of
        // remaining A's, then the A's finish in list order.
        assertEquals(listOf(a[0], b, a[1], a[2], a[3], a[4]), order)
    }

    @Test
    fun tierStillDominatesRotation() {
        // The last served host is A; rotation alone would prefer
        // the different-host B task — but B is a lower tier, so
        // the high-tier A task still goes first.
        QueueOrder.noteServed(hostTask("a.example.com"))
        val highA = hostTask("a.example.com", priority = 1)
        val lowB = hostTask("b.example.com", priority = -1)
        assertEquals(highA, QueueOrder.nextEligible(listOf(lowB, highA), 0))
    }

    @Test
    fun jumpedTaskIsPickedEvenWhenItsHostWasJustServed() {
        QueueOrder.noteServed(hostTask("a.example.com"))
        val otherHost = hostTask("b.example.com")
        val jumped = hostTask("a.example.com").apply { jumpedAt = 123_000L }
        // The different-host task is even listed first; the jump
        // inside the tier beats both list order and rotation.
        assertEquals(jumped, QueueOrder.nextEligible(listOf(otherHost, jumped), 0))
    }

    @Test
    fun eligibilityIsJudgedBeforeRotation() {
        QueueOrder.noteServed(hostTask("a.example.com"))
        val eligibleA = hostTask("a.example.com")
        // A different-host task that is not eligible — scheduled
        // in the future, excluded, or parked — must not take
        // part in the rotation at all.
        val futureB = hostTask("b.example.com", scheduledAt = 10_000)
        assertEquals(eligibleA, QueueOrder.nextEligible(listOf(futureB, eligibleA), 5_000))
        val excludedB = hostTask("b.example.com")
        assertEquals(
            eligibleA,
            QueueOrder.nextEligible(listOf(excludedB, eligibleA), 0, setOf(excludedB.id)),
        )
        val netParkedB = hostTask("b.example.com").apply { netDeferred = true }
        assertEquals(eligibleA, QueueOrder.nextEligible(listOf(netParkedB, eligibleA), 0))
        val portalParkedB = hostTask("b.example.com").apply { portalDeferred = true }
        assertEquals(eligibleA, QueueOrder.nextEligible(listOf(portalParkedB, eligibleA), 0))
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
