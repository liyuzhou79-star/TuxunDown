package com.tuxundown.android.core

/**
 * v5.2: the home task list's 「异常」 filter — a pure predicate,
 * unit-tested (see AbnormalFilterTest). ONE implementation is
 * shared by the tab's count and the tab's list filter, so the
 * number on the tab and the tasks behind it can never disagree.
 *
 * What counts as abnormal (needs the user's attention):
 * - FAILED tasks (judged by status alone);
 * - tasks parked by the network-outage deferral ([TaskRecord.netDeferred]);
 * - tasks parked by a captive-portal hit ([TaskRecord.portalDeferred]);
 * - tasks held by the WiFi gate or the charge gate (see below).
 * Archived records never count, exactly like every other
 * non-archive tab.
 *
 * Why the WiFi / charge half does NOT read per-task flags:
 * the drain (DownloadService.drainQueue) judges the WiFi gate
 * without setting any flag at all (it only stamps a note on the
 * ONE eligible head task and breaks), and it stamps
 * [TaskRecord.chargeDeferred] only on that same head task.
 * Every QUEUED task BEHIND the head is just as held, yet carries
 * no flag — a flag-based predicate would systematically miss
 * them. So this predicate takes the gates' CURRENT state as an
 * input ([GateContext], read fresh by the UI on every
 * recomposition from DownloadCenter's live reads): a QUEUED
 * task whose scheduled time has arrived is held whenever either
 * gate is closed. The charge flag is deliberately NOT an input
 * either: it can be stale-true after power returns (the drain
 * clears it only when the task actually starts), the same
 * reason QueuePreheat refuses to read it. The gate context is
 * the truth; the flags are not.
 *
 * The outage / portal parks are the opposite shape: they are
 * per-task verdicts (QueueOrder.nextEligible skips exactly the
 * flagged tasks), so those two ARE judged by their flags alone,
 * with no extra assumption about the task's status.
 *
 * This file touches no Android API: the UI side packs the two
 * gate booleans (wifiOnly && !isOnWifi, and
 * ChargeGuard.shouldPark(chargeOnly, isCharging)) plus the
 * current time into [GateContext].
 */
object AbnormalFilter {

    /** The gates' current state, read once per recomposition
     *  by the UI side. Pure data — no Android calls in here. */
    data class GateContext(
        /** 仅 WiFi is on AND the current network is not WiFi. */
        val wifiGateClosed: Boolean,
        /** ChargeGuard.shouldPark(chargeOnly, charging). */
        val chargeGateClosed: Boolean,
        /** Wall-clock now, for the scheduled-time due check. */
        val nowMs: Long,
    )

    /** No gate closed, at time [nowMs] — the context tests use
     *  for "nothing is holding the queue". */
    fun openGates(nowMs: Long): GateContext =
        GateContext(wifiGateClosed = false, chargeGateClosed = false, nowMs = nowMs)

    /** True when [task]'s scheduled start has arrived. */
    private fun isDue(task: TaskRecord, nowMs: Long): Boolean =
        !(task.scheduledAt > 0 && task.scheduledAt > nowMs)

    /** The one abnormal predicate: count and filter both use it. */
    fun isAbnormal(task: TaskRecord, gates: GateContext): Boolean {
        // Archived records live only under the 已归档 tab —
        // an archived FAILED task is not an abnormal-tab item.
        if (task.archived) return false
        // FAILED is judged by status alone.
        if (task.status == TaskStatus.FAILED) return true
        // Outage / portal parks are per-task verdicts: the flag
        // IS the judgement (see the file doc), so no status
        // assumption is layered on top of it.
        if (task.netDeferred || task.portalDeferred) return true
        // WiFi / charge holds: a due QUEUED task behind a closed
        // gate, whether or not it is the queue head the drain
        // happened to stamp. A not-yet-due (scheduled) task is
        // waiting for its clock, not for a gate — not abnormal.
        if (task.status == TaskStatus.QUEUED && isDue(task, gates.nowMs) &&
            (gates.wifiGateClosed || gates.chargeGateClosed)
        ) {
            return true
        }
        return false
    }

    /** The tasks the 异常 tab shows, in list order. */
    fun filter(tasks: List<TaskRecord>, gates: GateContext): List<TaskRecord> =
        tasks.filter { isAbnormal(it, gates) }

    /** The count on the 异常 tab — literally the filter's size,
     *  so the two can never drift apart. */
    fun count(tasks: List<TaskRecord>, gates: GateContext): Int =
        filter(tasks, gates).size
}
