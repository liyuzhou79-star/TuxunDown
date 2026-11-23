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

    // ---- v7.6 (Step B-6): signature coverage + memo ----

    @Test
    fun memoReturnsTheCachedInstanceWhileSignatureIsUnchanged() {
        val tasks = listOf(hostTask("a.example.com"), hostTask("b.example.com"))
        val memo = QueueOrder.QueuePositionsMemo()
        val first = memo.positions(tasks, 1_000)
        assertEquals(QueueOrder.queuePositions(tasks, 1_000), first)
        // An input the simulation never reads changes — the
        // memo must still serve the cached instance.
        tasks[0].downloaded = 123_456
        tasks[0].note = "changed"
        val second = memo.positions(tasks, 1_001)
        assertTrue(first === second)
    }

    @Test
    fun memoRecomputesWhenRotationStateChanges() {
        val a = hostTask("a.example.com")
        val b = hostTask("b.example.com")
        val tasks = listOf(a, b)
        val memo = QueueOrder.QueuePositionsMemo()
        val before = memo.positions(tasks, 0)
        assertEquals(1, before[a.id])
        // The drain serves host A for real: rotation now puts
        // B first, and the memo must NOT serve the stale map.
        QueueOrder.noteServed(a)
        val after = memo.positions(tasks, 0)
        assertEquals(1, after[b.id])
        assertEquals(QueueOrder.queuePositions(tasks, 0), after)
    }

    @Test
    fun memoRecomputesAcrossTheAgingCrossing() {
        val t0 = 200_000_000L
        val normal = agingTask(0, queuedAt = t0)
        val low = agingTask(-1, queuedAt = t0)
        val tasks = listOf(normal, low)
        val memo = QueueOrder.QueuePositionsMemo()
        val before = memo.positions(tasks, t0 + QueueOrder.AGING_PROMOTE_MS - 1)
        assertEquals(2, before[low.id])
        val after = memo.positions(tasks, t0 + QueueOrder.AGING_PROMOTE_MS)
        assertEquals(QueueOrder.queuePositions(tasks, t0 + QueueOrder.AGING_PROMOTE_MS), after)
        // The tier footprint inside the signature moved at
        // the crossing, so this was a recompute, not a hit.
        assertTrue(before !== after)
        assertFalse(
            QueueOrder.queuePositionsSignature(tasks, t0 + QueueOrder.AGING_PROMOTE_MS - 1) ==
                QueueOrder.queuePositionsSignature(tasks, t0 + QueueOrder.AGING_PROMOTE_MS),
        )
    }

    @Test
    fun memoRecomputesWhenScheduledTaskBecomesDueAndWhenExclusionsChange() {
        val due = hostTask("a.example.com")
        val future = hostTask("b.example.com", scheduledAt = 10_000)
        val tasks = listOf(future, due)
        val memo = QueueOrder.QueuePositionsMemo()
        val before = memo.positions(tasks, 5_000)
        assertFalse(before.containsKey(future.id))
        val after = memo.positions(tasks, 10_000)
        assertTrue(after.containsKey(future.id))
        val excluded = memo.positions(tasks, 10_000, setOf(due.id))
        assertFalse(excluded.containsKey(due.id))
        assertEquals(QueueOrder.queuePositions(tasks, 10_000, setOf(due.id)), excluded)
    }

    @Test
    fun signatureCoversEverySimulationInput() {
        val base = listOf(hostTask("a.example.com"), hostTask("b.example.com"))
        val sig = QueueOrder.queuePositionsSignature(base, 0)
        // Status flip changes it.
        val flipped = listOf(
            hostTask("a.example.com").apply { status = TaskStatus.PAUSED },
            base[1],
        )
        assertFalse(sig == QueueOrder.queuePositionsSignature(flipped, 0))
        // A park flag changes it (same task, flag flipped in
        // place — the signature must follow the live record).
        val flagged = hostTask("c.example.com")
        val sigBefore = QueueOrder.queuePositionsSignature(listOf(flagged), 0)
        flagged.netDeferred = true
        assertFalse(sigBefore == QueueOrder.queuePositionsSignature(listOf(flagged), 0))
        flagged.netDeferred = false
        assertEquals(sigBefore, QueueOrder.queuePositionsSignature(listOf(flagged), 0))
        // List order changes it.
        assertFalse(sig == QueueOrder.queuePositionsSignature(base.reversed(), 0))
        // Rotation state changes it.
        QueueOrder.noteServed(base[0])
        assertFalse(sig == QueueOrder.queuePositionsSignature(base, 0))
    }

    // ---- v7.7 (B-1): the O(n + picks) simulation must equal
    // ---- the old per-pick simulation, bit for bit ----

    /** The OLD queuePositions algorithm, reproduced on the
     *  public drain surface: repeatedly take [nextEligible]
     *  (which reads the global rotation memory), noteServed
     *  the pick, remove it. The old implementation ran exactly
     *  this sequence of pickWithServed calls against a LOCAL
     *  copy of the memory; driving the global memory through
     *  the same picks is the same sequence. */
    private fun oldSimulation(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String>,
    ): Map<String, Int> {
        val remaining = tasks.toMutableList()
        val positions = LinkedHashMap<String, Int>()
        var nextPosition = 1
        while (true) {
            val picked = QueueOrder.nextEligible(remaining, now, excludeIds) ?: break
            positions[picked.id] = nextPosition++
            QueueOrder.noteServed(picked)
            remaining.remove(picked)
        }
        return positions
    }

    private fun setServedHost(host: String?) {
        QueueOrder.clearServedMemory()
        if (host != null) {
            QueueOrder.noteServed(
                TaskRecord(url = "https://$host/seed", status = TaskStatus.QUEUED),
            )
        }
    }

    /** One generated task set across every dimension the pick
     *  chain reads: statuses, priorities (incl. out-of-range
     *  values the clamp folds to NORMAL), aging on both sides
     *  of the promote line (queuedAt stamped / unstamped),
     *  jumped, all three park flags, not-yet-due and due
     *  scheduled tasks, and a small host pool so rotation runs
     *  (incl. same-host neighbours around a differing host)
     *  actually form. List order is shuffled — it is an input. */
    private fun generatedSet(
        rnd: java.util.Random,
        count: Int,
        now: Long,
    ): List<TaskRecord> {
        val hosts = arrayOf(
            "a.example.com", "a.example.com", "b.example.com",
            "c.example.com", "d.example.com",
        )
        val statuses = TaskStatus.values()
        val out = ArrayList<TaskRecord>(count)
        for (i in 0 until count) {
            val t = TaskRecord(
                url = "https://${hosts[rnd.nextInt(hosts.size)]}/f$i",
                status = if (rnd.nextInt(10) < 7) {
                    TaskStatus.QUEUED
                } else {
                    statuses[rnd.nextInt(statuses.size)]
                },
                priority = intArrayOf(-1, 0, 1, 1, 0, -1, 2, -3)[rnd.nextInt(8)],
                createdAt = now - rnd.nextInt(4_000_000),
                scheduledAt = when (rnd.nextInt(4)) {
                    0 -> now + rnd.nextInt(100_000)
                    1 -> now - rnd.nextInt(100_000)
                    else -> 0L
                },
            )
            if (rnd.nextInt(4) > 0) t.queuedAt = now - rnd.nextInt(4_000_000)
            if (rnd.nextInt(4) == 0) t.jumpedAt = 1L + rnd.nextInt(100_000)
            if (rnd.nextInt(8) == 0) t.netDeferred = true
            if (rnd.nextInt(8) == 0) t.portalDeferred = true
            if (rnd.nextInt(8) == 0) t.chargeDeferred = true
            out.add(t)
        }
        out.shuffle(rnd)
        return out
    }

    @Test
    fun newSimulationMatchesOldAlgorithmOnGeneratedSets() {
        val now = 300_000_000L
        for (seed in 1L..40L) {
            val rnd = java.util.Random(seed)
            val tasks = generatedSet(rnd, rnd.nextInt(61), now)
            val excludeIds = tasks.filter { rnd.nextInt(5) == 0 }.map { it.id }.toSet()
            val startHost = when (rnd.nextInt(4)) {
                0 -> null
                1 -> "a.example.com"
                2 -> "b.example.com"
                else -> "zzz.example.com"
            }
            setServedHost(startHost)
            val expected = oldSimulation(tasks, now, excludeIds)
            setServedHost(startHost)
            val actual = QueueOrder.queuePositions(tasks, now, excludeIds)
            assertEquals("seed $seed", expected, actual)
        }
    }

    @Test
    fun newSimulationMatchesOldAlgorithmOnALargeSet() {
        val now = 300_000_000L
        val rnd = java.util.Random(987_654_321L)
        val tasks = generatedSet(rnd, 400, now)
        val excludeIds = tasks.filter { rnd.nextInt(7) == 0 }.map { it.id }.toSet()
        setServedHost("c.example.com")
        val expected = oldSimulation(tasks, now, excludeIds)
        setServedHost("c.example.com")
        assertEquals(expected, QueueOrder.queuePositions(tasks, now, excludeIds))
    }

    @Test
    fun rotationMergesSameHostNeighboursAroundAnEmptiedRun() {
        // [A, A, B, A] with A just served: rotation takes B
        // first, then the A tasks in list order — after B's
        // run empties, the two A runs are ONE run for the
        // "first host that differs" scan.
        val a1 = hostTask("a.example.com")
        val a2 = hostTask("a.example.com")
        val b = hostTask("b.example.com")
        val a3 = hostTask("a.example.com")
        val tasks = listOf(a1, a2, b, a3)
        setServedHost("a.example.com")
        val expected = oldSimulation(tasks, 0, emptySet())
        setServedHost("a.example.com")
        val actual = QueueOrder.queuePositions(tasks, 0)
        assertEquals(expected, actual)
        assertEquals(1, actual[b.id])
        assertEquals(2, actual[a1.id])
        assertEquals(3, actual[a2.id])
        assertEquals(4, actual[a3.id])
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
