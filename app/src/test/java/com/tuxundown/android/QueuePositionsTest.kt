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

/**
 * v7.1 (audit round 56, Step C): queue positions — the map the
 * task cards render as 「排队中 · 第 N 位」. The positions must
 * come from the SAME pick core as the drain's nextEligible
 * (QueueOrder.pickWithServed), never from a second ordering,
 * so these tests pin the two against each other, pin the
 * whole simulated order, and pin who gets NO position.
 */
class QueuePositionsTest {

    @Before
    fun clearRotationBefore() = QueueOrder.clearServedMemory()

    @After
    fun clearRotationAfter() = QueueOrder.clearServedMemory()

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

    /** The drain's own simulation, written against the public
     *  nextEligible + noteServed exactly as DownloadService
     *  does it — the reference sequence queuePositions must
     *  reproduce without touching the global memory itself. */
    private fun drainSequence(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ): List<TaskRecord> {
        val remaining = tasks.toMutableList()
        val order = mutableListOf<TaskRecord>()
        while (true) {
            val next = QueueOrder.nextEligible(remaining, now, excludeIds) ?: break
            QueueOrder.noteServed(next)
            order.add(next)
            remaining.remove(next)
        }
        return order
    }

    private fun assertPositionOneIsNextEligible(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ) {
        val head = QueueOrder.nextEligible(tasks, now, excludeIds)
        val positions = QueueOrder.queuePositions(tasks, now, excludeIds)
        if (head == null) {
            assertTrue(positions.isEmpty())
        } else {
            assertEquals(head.id, positions.entries.first { it.value == 1 }.key)
        }
    }

    // ---- consistency: position 1 == nextEligible ----

    @Test
    fun positionOneMatchesNextEligibleForTierMix() {
        val low = hostTask("a.example.com", priority = -1)
        val normal = hostTask("b.example.com", priority = 0)
        val high = hostTask("c.example.com", priority = 1)
        assertPositionOneIsNextEligible(listOf(low, normal, high), 0)
        assertPositionOneIsNextEligible(listOf(high, low, normal), 0)
    }

    @Test
    fun positionOneMatchesNextEligibleForJumped() {
        val plain = hostTask("a.example.com")
        val jumped = hostTask("b.example.com").apply { jumpedAt = 42_000L }
        assertPositionOneIsNextEligible(listOf(plain, jumped), 0)
    }

    @Test
    fun positionOneMatchesNextEligibleForRotation() {
        // Memory says host A was just served: rotation must
        // move the different-host task to position 1 in BOTH
        // the single pick and the simulated positions.
        QueueOrder.noteServed(hostTask("a.example.com"))
        val a = hostTask("a.example.com")
        val b = hostTask("b.example.com")
        assertPositionOneIsNextEligible(listOf(a, b), 0)
        assertEquals(1, QueueOrder.queuePositions(listOf(a, b), 0)[b.id])
        assertEquals(2, QueueOrder.queuePositions(listOf(a, b), 0)[a.id])
    }

    @Test
    fun positionOneMatchesNextEligibleForAgingMix() {
        val t0 = 200_000_000L
        val now = t0 + 2 * QueueOrder.AGING_PROMOTE_MS
        val agedLow = agingTask(-1, queuedAt = t0)
        val normal = agingTask(0, queuedAt = now)
        val high = agingTask(1, queuedAt = now)
        assertPositionOneIsNextEligible(listOf(agedLow, normal), now)
        assertPositionOneIsNextEligible(listOf(agedLow, normal, high), now)
        assertPositionOneIsNextEligible(
            listOf(agedLow, normal), t0 + QueueOrder.AGING_PROMOTE_MS - 1,
        )
    }

    // ---- whole order ----

    @Test
    fun positionsReproduceTheSequentialDrainOrder() {
        val tasks = listOf(
            hostTask("hx.example.com", priority = -1),
            hostTask("hy.example.com", priority = 1),
            hostTask("hx.example.com"),
            hostTask("hy.example.com").apply { jumpedAt = 7_000L },
            hostTask("hz.example.com", priority = -1),
        )
        val expected = drainSequence(tasks, 0).map { it.id }
        // drainSequence advanced the global memory; reset it so
        // queuePositions starts from the same memory it did.
        QueueOrder.clearServedMemory()
        val positions = QueueOrder.queuePositions(tasks, 0)
        val byPosition = positions.entries.sortedBy { it.value }.map { it.key }
        assertEquals(expected, byPosition)
        assertEquals((1..tasks.size).toList(), positions.values.sorted())
    }

    @Test
    fun rotationBoundSequencePositionsArePinned() {
        // Round 56 channel (c) bound sequence, as positions:
        // [Z1, Z2(Hx), X(Hx), Y1, Y2(Hy)] drains Z1, Y1, Z2,
        // Y2, X — so the positions are exactly these.
        val z1 = hostTask("hx.example.com")
        val z2 = hostTask("hx.example.com")
        val x = hostTask("hx.example.com")
        val y1 = hostTask("hy.example.com")
        val y2 = hostTask("hy.example.com")
        val positions = QueueOrder.queuePositions(listOf(z1, z2, x, y1, y2), 0)
        assertEquals(1, positions[z1.id])
        assertEquals(2, positions[y1.id])
        assertEquals(3, positions[z2.id])
        assertEquals(4, positions[y2.id])
        assertEquals(5, positions[x.id])
    }

    @Test
    fun agedLowMovesUpWhenItAgesIntoTheNormalTier() {
        val t0 = 200_000_000L
        val normal1 = agingTask(0, queuedAt = t0)
        val low = agingTask(-1, queuedAt = t0)
        val normal2 = agingTask(0, queuedAt = t0)
        val tasks = listOf(normal1, low, normal2)
        // Not yet aged: both NORMAL tasks go first, LOW last.
        val before = QueueOrder.queuePositions(
            tasks, t0 + QueueOrder.AGING_PROMOTE_MS - 1,
        )
        assertEquals(1, before[normal1.id])
        assertEquals(2, before[normal2.id])
        assertEquals(3, before[low.id])
        // Aged to NORMAL: tied with the plain NORMAL tasks,
        // list order decides — the LOW task moves up to 2.
        val after = QueueOrder.queuePositions(
            tasks, t0 + QueueOrder.AGING_PROMOTE_MS,
        )
        assertEquals(1, after[normal1.id])
        assertEquals(2, after[low.id])
        assertEquals(3, after[normal2.id])
    }

    // ---- who gets NO position ----

    @Test
    fun ineligibleQueuedTasksHaveNoPosition() {
        val eligible = hostTask("a.example.com")
        val netParked = hostTask("b.example.com").apply { netDeferred = true }
        val portalParked = hostTask("c.example.com").apply { portalDeferred = true }
        val notDue = hostTask("d.example.com", scheduledAt = 10_000)
        val positions = QueueOrder.queuePositions(
            listOf(netParked, portalParked, notDue, eligible), 5_000,
        )
        assertEquals(mapOf(eligible.id to 1), positions)
    }

    @Test
    fun chargeDeferredTasksDoGetAPosition() {
        // chargeDeferred is a POST-pick gate, not part of the
        // eligibility gate — the simulation picks it normally.
        val charged = hostTask("a.example.com").apply { chargeDeferred = true }
        val plain = hostTask("b.example.com")
        val positions = QueueOrder.queuePositions(listOf(charged, plain), 0)
        assertEquals(1, positions[charged.id])
        assertEquals(2, positions[plain.id])
    }

    @Test
    fun excludedTasksHaveNoPositionAndOccupyNone() {
        val first = hostTask("a.example.com")
        val second = hostTask("b.example.com")
        val positions = QueueOrder.queuePositions(
            listOf(first, second), 0, setOf(first.id),
        )
        assertFalse(positions.containsKey(first.id))
        assertEquals(1, positions[second.id])
    }

    // ---- purity ----

    @Test
    fun simulationHasNoSideEffects() {
        QueueOrder.noteServed(hostTask("served.example.com"))
        val memoryBefore = QueueOrder.lastServedHost
        val a = hostTask("a.example.com")
        val b = hostTask("b.example.com")
        val input = mutableListOf(a, b)
        val snapshot = input.toList()
        QueueOrder.queuePositions(input, 0)
        assertEquals(memoryBefore, QueueOrder.lastServedHost)
        assertEquals(snapshot, input)
    }

    @Test
    fun emptyOrNothingQueuedReturnsEmptyMap() {
        assertTrue(QueueOrder.queuePositions(emptyList(), 0).isEmpty())
        val running = TaskRecord(
            url = "https://a.example.com/f${System.nanoTime()}",
            status = TaskStatus.RUNNING,
        )
        val paused = TaskRecord(
            url = "https://a.example.com/f${System.nanoTime()}",
            status = TaskStatus.PAUSED,
        )
        assertTrue(QueueOrder.queuePositions(listOf(running, paused), 0).isEmpty())
        val onlyParked = hostTask("a.example.com").apply { netDeferred = true }
        assertTrue(QueueOrder.queuePositions(listOf(onlyParked), 0).isEmpty())
    }
}
