package com.tuxundown.android.core

/**
 * Global connection budget (v3.9): the user's 全局连接上限 is a
 * TOTAL ceiling on how many connections ALL running tasks may
 * hold at once — the connection-count counterpart of the
 * bandwidth budget (see BandwidthBudget). When the running
 * tasks' combined appetite fits under the ceiling, everyone
 * gets exactly what they asked for; when it does not, the
 * ceiling is split in proportion to priority weight (the same
 * tiers as the bandwidth split), never above a task's own
 * desire, and every task keeps at least one connection while
 * the ceiling can afford it.
 *
 * Pure math — unit-tested. The engine re-asks for a fresh
 * split on every scaler tick, so a task starting or finishing
 * rebalances everyone within seconds.
 */
object ConnectionBudget {

    const val MIN_CAP = 8
    const val MAX_CAP = 128
    const val DEFAULT_CAP = 32

    fun clampCap(v: Int): Int = v.coerceIn(MIN_CAP, MAX_CAP)

    /**
     * One running task's claim: [weight] is its priority
     * weight (see BandwidthBudget.weightOf), [desire] the
     * connection count it could use without the global cap.
     */
    data class Demand(val id: String, val weight: Int, val desire: Int)

    /**
     * Split [cap] across [demands]. The result sums to
     * min(cap, sum of desires); nobody exceeds their desire;
     * while cap >= demands.size everyone gets at least 1.
     * When cap cannot even give everyone 1, the heaviest
     * weights are served first (list order breaks ties) and
     * the rest get 0 — with the shipped ranges (cap >= 8, at
     * most 5 concurrent tasks) that branch is unreachable in
     * the app, but the rule is pinned here rather than left
     * to chance.
     */
    fun allocate(cap: Int, demands: List<Demand>): Map<String, Int> {
        if (demands.isEmpty()) return emptyMap()
        val budget = cap.coerceAtLeast(0)
        val wants = demands.map { it.desire.coerceAtLeast(0) }
        if (wants.sum() <= budget) {
            return demands.mapIndexed { i, d -> d.id to wants[i] }.toMap()
        }
        val alloc = IntArray(demands.size) { 0 }
        // Deterministic service order: heavier weight first,
        // list order breaks ties.
        val order = demands.indices.sortedWith(
            compareByDescending<Int> { demands[it].weight.coerceAtLeast(1) }
                .thenBy { it }
        )
        var remaining = budget
        // Round 1: the one-connection floor, in service order.
        for (i in order) {
            if (remaining <= 0) break
            if (wants[i] > 0) {
                alloc[i] = 1
                remaining--
            }
        }
        // Round 2: hand out the rest one at a time to the
        // below-desire task whose weight-per-connection ratio
        // is highest (smooth weighted sharing); ties go to the
        // earlier task in service order.
        while (remaining > 0) {
            var best = -1
            var bestRatio = -1.0
            for (i in order) {
                if (wants[i] - alloc[i] <= 0) continue
                val ratio = demands[i].weight.coerceAtLeast(1).toDouble() / (alloc[i] + 1)
                if (ratio > bestRatio) {
                    best = i
                    bestRatio = ratio
                }
            }
            if (best < 0) break
            alloc[best]++
            remaining--
        }
        return demands.mapIndexed { i, d -> d.id to alloc[i] }.toMap()
    }
}
