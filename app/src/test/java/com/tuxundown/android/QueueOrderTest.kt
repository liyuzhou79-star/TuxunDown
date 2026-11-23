package com.tuxundown.android

import com.tuxundown.android.core.QueueOrder
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- v7.1 (audit round 56): anti-starvation aging ----

    private fun agingTask(
        priority: Int,
        queuedAt: Long,
        createdAt: Long = queuedAt,
        scheduledAt: Long = 0,
    ): TaskRecord = TaskRecord(
        url = "https://aging.example/f${System.nanoTime()}",
        status = TaskStatus.QUEUED,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        priority = priority,
    ).apply { this.queuedAt = queuedAt }

    @Test
    fun agedLowRisesToNormalExactlyAtTheThreshold() {
        val t0 = 200_000_000L
        val low = agingTask(-1, queuedAt = t0)
        val normal = agingTask(0, queuedAt = t0)
        // Below the threshold the plain tier decides: NORMAL
        // wins even though the LOW task is listed first.
        val justBelow = t0 + QueueOrder.AGING_PROMOTE_MS - 1
        assertEquals(-1, QueueOrder.agingTier(low, justBelow))
        assertEquals(-1, QueueOrder.effectiveTier(low, justBelow))
        assertEquals(normal, QueueOrder.nextEligible(listOf(low, normal), justBelow))
        // At exactly T_age the LOW task is NORMAL tier; tied
        // with the plain NORMAL task, list order now serves it.
        val atThreshold = t0 + QueueOrder.AGING_PROMOTE_MS
        assertEquals(0, QueueOrder.agingTier(low, atThreshold))
        assertEquals(0, QueueOrder.effectiveTier(low, atThreshold))
        assertEquals(low, QueueOrder.nextEligible(listOf(low, normal), atThreshold))
    }

    @Test
    fun agingIsCappedAtNormalAndHighStillWins() {
        val t0 = 200_000_000L
        val now = t0 + 10 * QueueOrder.AGING_PROMOTE_MS
        val agedLow = agingTask(-1, queuedAt = t0)
        val high = agingTask(1, queuedAt = now)
        // Even a very long wait never ages past NORMAL…
        assertEquals(0, QueueOrder.agingTier(agedLow, now))
        assertEquals(0, QueueOrder.effectiveTier(agedLow, now))
        assertTrue(QueueOrder.agingTier(high, now) < 1)
        // …so a plain HIGH task still goes first.
        assertEquals(high, QueueOrder.nextEligible(listOf(agedLow, high), now))
    }

    @Test
    fun jumpedBeatsAgedLow() {
        val t0 = 200_000_000L
        val now = t0 + 2 * QueueOrder.AGING_PROMOTE_MS
        val agedLow = agingTask(-1, queuedAt = t0)
        val jumped = agingTask(0, queuedAt = now).apply { jumpedAt = now }
        // Both judge as NORMAL now; inside the tier the
        // explicit jump beats the automatic aging, even listed
        // behind the aged task.
        assertEquals(jumped, QueueOrder.nextEligible(listOf(agedLow, jumped), now))
    }

    @Test
    fun queuedAtZeroFallsBackToCreatedAt() {
        val t0 = 200_000_000L
        val legacy = agingTask(-1, queuedAt = 0, createdAt = t0)
        assertEquals(t0, QueueOrder.waitStart(legacy))
        assertEquals(
            -1,
            QueueOrder.effectiveTier(legacy, t0 + QueueOrder.AGING_PROMOTE_MS - 1),
        )
        assertEquals(
            0,
            QueueOrder.effectiveTier(legacy, t0 + QueueOrder.AGING_PROMOTE_MS),
        )
    }

    @Test
    fun agingNeverResurrectsParkedOrNotYetDueTasks() {
        val t0 = 200_000_000L
        val now = t0 + 10 * QueueOrder.AGING_PROMOTE_MS
        val normal = agingTask(0, queuedAt = now)
        val netParked = agingTask(-1, queuedAt = t0).apply { netDeferred = true }
        assertEquals(normal, QueueOrder.nextEligible(listOf(netParked, normal), now))
        assertNull(QueueOrder.nextEligible(listOf(netParked), now))
        val portalParked = agingTask(-1, queuedAt = t0).apply { portalDeferred = true }
        assertEquals(normal, QueueOrder.nextEligible(listOf(portalParked, normal), now))
        assertNull(QueueOrder.nextEligible(listOf(portalParked), now))
        val notDue = agingTask(-1, queuedAt = t0, scheduledAt = now + 60_000)
        assertEquals(normal, QueueOrder.nextEligible(listOf(notDue, normal), now))
        assertNull(QueueOrder.nextEligible(listOf(notDue), now))
    }

    @Test
    fun scheduledWaitAccruesFromTheDueMomentNotFromQueueing() {
        // Queued long ago, but its slot only arrived 5 minutes
        // ago: the pre-due time was the user's own appointment,
        // so it has NOT aged yet.
        val dueAt = 200_000_000L
        val low = agingTask(
            -1,
            queuedAt = dueAt - 10 * QueueOrder.AGING_PROMOTE_MS,
            scheduledAt = dueAt,
        )
        assertEquals(dueAt, QueueOrder.waitStart(low))
        val normal = agingTask(0, queuedAt = dueAt)
        val fiveMinAfterDue = dueAt + 5 * 60_000L
        assertEquals(-1, QueueOrder.effectiveTier(low, fiveMinAfterDue))
        assertEquals(
            normal,
            QueueOrder.nextEligible(listOf(low, normal), fiveMinAfterDue),
        )
        // A full threshold AFTER the due moment it does age.
        val agedAt = dueAt + QueueOrder.AGING_PROMOTE_MS
        assertEquals(0, QueueOrder.effectiveTier(low, agedAt))
        assertEquals(low, QueueOrder.nextEligible(listOf(low, normal), agedAt))
    }

    @Test
    fun rotationBoundSequenceDrainsInThePinnedOrder() {
        // Round 56 channel (c), the constructive bound check:
        // Z1, Z2 and X share one host, Y1 / Y2 another. X is
        // overtaken exactly twice (the bound min(2, 2)) and the
        // whole drain order is pinned.
        val z1 = hostTask("hx.example.com")
        val z2 = hostTask("hx.example.com")
        val x = hostTask("hx.example.com")
        val y1 = hostTask("hy.example.com")
        val y2 = hostTask("hy.example.com")
        assertEquals(
            listOf(z1, y1, z2, y2, x),
            drainOrder(listOf(z1, z2, x, y1, y2)),
        )
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
