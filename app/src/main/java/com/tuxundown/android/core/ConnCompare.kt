package com.tuxundown.android.core

/**
 * Connection-count comparison (v4.2, knife 5): the pure plan and
 * verdict behind the diagnostics page's 「连接数对比实测」 section.
 *
 * The question this answers is different from the speed-test
 * page's: that page measures this phone against a speed-test
 * SERVER; this one takes ONE user-supplied file link and measures
 * it with 4 / 16 / 32 / 64 parallel connections in turn, to see
 * how many connections THAT link, on the CURRENT network, can
 * actually use — the more speed grows with connection count, the
 * more that server is limiting per connection.
 *
 * The runner lives in DiagnosticsScreen (MainActivity) and reuses
 * SpeedTest.runPhase per tier, so every tier's speed is the v4.0
 * steady-state口径 (warmup discarded, Δbytes/Δt over the steady
 * window) — the numbers are comparable with the speed-test page's.
 *
 * Traffic honesty: the four tiers share ONE hard byte budget,
 * [TOTAL_CAP_BYTES]. Each tier is started with only the budget
 * that is left ([tierBudget]) as its runPhase total cap, so the
 * whole run can never move more than the cap no matter how fast
 * the network is. [consentText] states the cap to the user BEFORE
 * anything runs.
 *
 * Pure — unit-tested (ConnCompareTest).
 */
object ConnCompare {

    /** Connection counts measured, in run order (ascending). */
    val TIERS: List<Int> = listOf(4, 16, 32, 64)

    /** Longest time one tier may run. */
    const val PHASE_MS: Long = 4_000L

    /** Hard traffic ceiling shared by ALL four tiers together. */
    const val TOTAL_CAP_BYTES: Long = 200L * 1024 * 1024

    /** One tier's outcome: steady-state [bps] over [windowMs],
     *  [bytes] actually moved by that tier. */
    data class TierResult(
        val connections: Int,
        val bps: Long,
        val windowMs: Long,
        val bytes: Long,
    )

    /**
     * The winning tier: highest steady bps. A tie goes to the
     * tier with FEWER connections — same speed for less work is
     * the cheaper answer, and opening connections past the point
     * of no gain is exactly what this tool exists to discourage.
     * Null when there are no results, or every tier moved nothing
     * (bps <= 0 throughout — the link likely does not work at
     * all, and crowning a "winner" would be a lie).
     */
    fun bestTier(results: List<TierResult>): TierResult? {
        var best: TierResult? = null
        for (r in results) {
            if (r.bps <= 0) continue
            val b = best
            if (b == null || r.bps > b.bps ||
                (r.bps == b.bps && r.connections < b.connections)
            ) {
                best = r
            }
        }
        return best
    }

    /**
     * Bytes a tier may still move before it starts, given
     * [spentBytes] already moved by the earlier tiers: whatever
     * of the shared cap is left, never negative (a tier started
     * with 0 budget moves nothing and reports 0 honestly).
     */
    fun tierBudget(spentBytes: Long): Long =
        (TOTAL_CAP_BYTES - spentBytes).coerceAtLeast(0)

    /**
     * The one consent sentence shown before a run starts. The
     * numbers are BUILT FROM THE CONSTANTS (tier list, per-tier
     * seconds, cap in MB) so the text can never drift away from
     * what the code actually enforces.
     */
    /**
     * v4.2 audit round 27: the dynamic companion to the static
     * "建议在没有任务正在下载时测" line in [consentText]. The
     * caller reads the RUNNING-task count ONCE, at the moment
     * the run actually starts, and shows this note alongside
     * the results that run produced — it is a snapshot of the
     * start moment, never a live counter (a live one would go
     * stale mid-run and misdescribe the finished tiers).
     * Empty when nothing was running: no note needed.
     */
    fun runningTasksNote(runningAtStart: Int): String {
        if (runningAtStart <= 0) return ""
        return "开始实测时有 $runningAtStart 个任务正在下载，它们会抢带宽，这次结果会偏低。"
    }

    fun consentText(): String {
        val tiers = TIERS.joinToString("、")
        val seconds = PHASE_MS / 1000
        val capMB = TOTAL_CAP_BYTES / 1024 / 1024
        return "会依次用 $tiers 条连接各测一小段，每档最多约 $seconds 秒；" +
            "四档共用最多约 $capMB MB 流量，下满就停（实际通常远少于这个数）。" +
            "建议在没有任务正在下载时测——正在下的任务会抢带宽，结果会偏低。"
    }
}
