package com.tuxundown.android

import com.tuxundown.android.core.AbnormalFilter
import com.tuxundown.android.core.TaskRecord
import com.tuxundown.android.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2: the 「异常」 tab predicate (pure) — FAILED + every park. */
class AbnormalFilterTest {

    private val now = 1_700_000_000_000L

    private fun task(
        status: TaskStatus = TaskStatus.QUEUED,
        archived: Boolean = false,
        netDeferred: Boolean = false,
        portalDeferred: Boolean = false,
        chargeDeferred: Boolean = false,
        scheduledAt: Long = 0,
    ): TaskRecord {
        val t = TaskRecord(url = "https://example.com/f.bin", status = status)
        t.archived = archived
        t.netDeferred = netDeferred
        t.portalDeferred = portalDeferred
        t.chargeDeferred = chargeDeferred
        t.scheduledAt = scheduledAt
        return t
    }

    private fun gates(wifiClosed: Boolean = false, chargeClosed: Boolean = false) =
        AbnormalFilter.GateContext(
            wifiGateClosed = wifiClosed,
            chargeGateClosed = chargeClosed,
            nowMs = now,
        )

    // ---- FAILED ----

    @Test
    fun failedIsAbnormal() {
        assertTrue(AbnormalFilter.isAbnormal(task(TaskStatus.FAILED), gates()))
    }

    @Test
    fun archivedFailedIsNotAbnormal() {
        // Archived records live only under 已归档, like every
        // other non-archive tab.
        assertFalse(
            AbnormalFilter.isAbnormal(task(TaskStatus.FAILED, archived = true), gates())
        )
    }

    // ---- per-task parks: the flag is the verdict ----

    @Test
    fun netDeferredIsAbnormal() {
        assertTrue(AbnormalFilter.isAbnormal(task(netDeferred = true), gates()))
    }

    @Test
    fun portalDeferredIsAbnormal() {
        assertTrue(AbnormalFilter.isAbnormal(task(portalDeferred = true), gates()))
    }

    @Test
    fun archivedParkedIsNotAbnormal() {
        assertFalse(
            AbnormalFilter.isAbnormal(
                task(archived = true, netDeferred = true), gates()
            )
        )
    }

    // ---- WiFi / charge holds: the GATE decides, not a flag ----

    @Test
    fun queuedBehindClosedWifiGateIsAbnormal() {
        // No flag exists for WiFi waits at all — the gate
        // context alone must catch a plain QUEUED task.
        assertTrue(AbnormalFilter.isAbnormal(task(), gates(wifiClosed = true)))
    }

    @Test
    fun queuedBehindClosedChargeGateIsAbnormal() {
        // chargeDeferred is stamped only on the queue HEAD by
        // the drain; a task behind it carries no flag and must
        // still count.
        assertTrue(AbnormalFilter.isAbnormal(task(), gates(chargeClosed = true)))
    }

    @Test
    fun wholeQueueBehindClosedGateAllCount() {
        val tasks = listOf(task(), task(), task(chargeDeferred = true))
        assertEquals(3, AbnormalFilter.count(tasks, gates(chargeClosed = true)))
    }

    @Test
    fun queuedWithOpenGatesIsNotAbnormal() {
        assertFalse(AbnormalFilter.isAbnormal(task(), gates()))
    }

    @Test
    fun staleChargeFlagAloneIsNotAbnormal() {
        // The flag can outlive the outage of power (the drain
        // clears it only when the task starts); with the gate
        // OPEN the task is not held, so the flag alone must
        // not count it — the gate context is the truth.
        assertFalse(
            AbnormalFilter.isAbnormal(task(chargeDeferred = true), gates())
        )
    }

    @Test
    fun notYetDueQueuedBehindClosedGateIsNotAbnormal() {
        // A scheduled task is waiting for its clock, not for
        // a gate.
        assertFalse(
            AbnormalFilter.isAbnormal(
                task(scheduledAt = now + 60_000), gates(wifiClosed = true)
            )
        )
    }

    @Test
    fun dueScheduledQueuedBehindClosedGateIsAbnormal() {
        assertTrue(
            AbnormalFilter.isAbnormal(
                task(scheduledAt = now - 1), gates(chargeClosed = true)
            )
        )
    }

    // ---- normal tasks never count ----

    @Test
    fun runningPausedDoneAreNotAbnormal() {
        // Even with both gates closed: gates hold only tasks
        // that have not started, and a user-paused task is the
        // user's own choice, not an abnormality.
        val closed = gates(wifiClosed = true, chargeClosed = true)
        assertFalse(AbnormalFilter.isAbnormal(task(TaskStatus.RUNNING), closed))
        assertFalse(AbnormalFilter.isAbnormal(task(TaskStatus.PAUSED), closed))
        assertFalse(AbnormalFilter.isAbnormal(task(TaskStatus.DONE), closed))
    }

    // ---- count == filter, always (one shared predicate) ----

    @Test
    fun countEqualsFilterSizeOnMixedList() {
        val tasks = listOf(
            task(TaskStatus.FAILED), // in
            task(TaskStatus.FAILED, archived = true), // out: archived
            task(netDeferred = true), // in
            task(portalDeferred = true), // in
            task(), // in: due QUEUED behind the closed WiFi gate
            task(scheduledAt = now + 60_000), // out: not due yet
            task(TaskStatus.PAUSED), // out: user's own pause
            task(TaskStatus.RUNNING), // out
            task(TaskStatus.DONE), // out
        )
        val g = gates(wifiClosed = true)
        val filtered = AbnormalFilter.filter(tasks, g)
        assertEquals(4, filtered.size)
        assertEquals(filtered.size, AbnormalFilter.count(tasks, g))
        // The filter keeps exactly the predicate's picks, in order.
        assertEquals(
            listOf(tasks[0], tasks[2], tasks[3], tasks[4]),
            filtered,
        )
        // And with the gates open the same list drops the
        // gate-held entry from BOTH count and filter together.
        val open = gates()
        assertEquals(3, AbnormalFilter.count(tasks, open))
        assertEquals(
            AbnormalFilter.filter(tasks, open).size,
            AbnormalFilter.count(tasks, open),
        )
    }

    @Test
    fun emptyListCountsZero() {
        assertEquals(0, AbnormalFilter.count(emptyList(), gates(wifiClosed = true)))
        assertTrue(AbnormalFilter.filter(emptyList(), gates()).isEmpty())
    }

    // ---- v5.3: FAILED subset + batch-entry visibility ----

    @Test
    fun showFailedBatchBoundary() {
        assertFalse(AbnormalFilter.showFailedBatch(0))
        assertFalse(AbnormalFilter.showFailedBatch(1))
        assertTrue(AbnormalFilter.showFailedBatch(2))
        assertTrue(AbnormalFilter.showFailedBatch(5))
    }

    @Test
    fun noFailedHidesEntryEvenWithManyParked() {
        // Parks alone never earn the batch entry: they are
        // waiting for a condition, not failures to retry.
        val tasks = listOf(
            task(netDeferred = true),
            task(portalDeferred = true),
            task(), // gate-held below
            task(),
        )
        val subset = AbnormalFilter.failedSubset(tasks, gates(wifiClosed = true))
        assertTrue(subset.isEmpty())
        assertFalse(AbnormalFilter.showFailedBatch(subset.size))
    }

    @Test
    fun oneFailedHidesEntryEvenWithManyParked() {
        val tasks = listOf(
            task(TaskStatus.FAILED),
            task(netDeferred = true),
            task(portalDeferred = true),
            task(), // gate-held below
        )
        val subset = AbnormalFilter.failedSubset(tasks, gates(chargeClosed = true))
        assertEquals(1, subset.size)
        assertFalse(AbnormalFilter.showFailedBatch(subset.size))
    }

    @Test
    fun twoFailedShowEntry() {
        val tasks = listOf(
            task(TaskStatus.FAILED),
            task(TaskStatus.FAILED),
            task(TaskStatus.DONE),
        )
        val subset = AbnormalFilter.failedSubset(tasks, gates())
        assertEquals(2, subset.size)
        assertTrue(AbnormalFilter.showFailedBatch(subset.size))
    }

    @Test
    fun failedSubsetExcludesArchivedFailed() {
        // The subset rides the same isAbnormal predicate, so
        // an archived FAILED task is out — exactly like the tab.
        val tasks = listOf(
            task(TaskStatus.FAILED),
            task(TaskStatus.FAILED, archived = true),
            task(TaskStatus.FAILED),
        )
        val subset = AbnormalFilter.failedSubset(tasks, gates())
        assertEquals(listOf(tasks[0], tasks[2]), subset)
    }

    @Test
    fun failedSubsetExcludesParkedNonFailed() {
        // Every park flavour is abnormal-tab material but NOT
        // batch-retry material: only real failures are in.
        val tasks = listOf(
            task(netDeferred = true),
            task(portalDeferred = true),
            task(), // gate-held below
            task(TaskStatus.FAILED),
        )
        val g = gates(wifiClosed = true)
        // Sanity: all four really are on the abnormal tab…
        assertEquals(4, AbnormalFilter.count(tasks, g))
        // …yet the subset is exactly the one FAILED task.
        assertEquals(listOf(tasks[3]), AbnormalFilter.failedSubset(tasks, g))
    }

    @Test
    fun failedSubsetSizeMatchesFailedInFilter() {
        val tasks = listOf(
            task(TaskStatus.FAILED), // in subset
            task(TaskStatus.FAILED, archived = true), // out: archived
            task(TaskStatus.FAILED), // in subset
            task(netDeferred = true), // abnormal, not FAILED
            task(portalDeferred = true), // abnormal, not FAILED
            task(), // abnormal via the closed gate, not FAILED
            task(TaskStatus.PAUSED), // out entirely
            task(TaskStatus.DONE), // out entirely
        )
        val g = gates(chargeClosed = true)
        val filtered = AbnormalFilter.filter(tasks, g)
        val subset = AbnormalFilter.failedSubset(tasks, g)
        assertEquals(
            filtered.count { it.status == TaskStatus.FAILED },
            subset.size,
        )
        // And the subset is exactly those entries, in list order.
        assertEquals(
            filtered.filter { it.status == TaskStatus.FAILED },
            subset,
        )
    }
}
