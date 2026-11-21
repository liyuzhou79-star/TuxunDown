package com.tuxundown.android.core

/**
 * Shared bandwidth budget (v1.6): the user's 全局限速 is a TOTAL
 * budget for the whole app, not a per-task allowance. When several
 * tasks run at once the budget is split in proportion to their
 * weights, so one task can never starve the others by grabbing the
 * full cap N times over — and a task the user pushed to the front
 * （插队， priority 1) weighs double. A single running task still
 * receives the entire budget, exactly like the old per-task cap.
 * Pure math — unit-tested.
 */
object BandwidthBudget {

    /** Priority weight: boosted （插队） tasks count double. */
    fun weightOf(priority: Int): Int = if (priority > 0) 2 else 1

    /**
     * Split [totalBps] across [weights] proportionally. The shares
     * sum to exactly totalBps (rounding remainder goes to the
     * heaviest tasks first). totalBps <= 0 yields all-zero shares
     * (callers treat 0 as "no limit").
     */
    fun shares(totalBps: Long, weights: List<Int>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        if (totalBps <= 0) return weights.map { 0L }
        val ws = weights.map { it.coerceAtLeast(1) }
        val sumW = ws.sum().toLong()
        val out = ws.map { totalBps * it / sumW }.toMutableList()
        var remainder = totalBps - out.sum()
        val order = ws.indices.sortedByDescending { ws[it] }
        var i = 0
        while (remainder > 0 && order.isNotEmpty()) {
            out[order[i % order.size]] += 1
            remainder--
            i++
        }
        return out
    }
}
