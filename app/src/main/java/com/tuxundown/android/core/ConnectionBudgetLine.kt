package com.tuxundown.android.core

/**
 * Task detail 「连接预算」 line (v5.9, audit round 44 step C) —
 * the pure verdict behind one detail row: how many connections
 * this task is currently granted, and WHAT is holding it there.
 *
 * The data chain, in the order the clamps actually apply:
 *   desire ──(host rate-limit cap)──> effective desire
 *          ──(global connection cap, ConnectionBudget.allocate)──>
 *          allowance
 * so the granted count is min(allowance, effective desire) and
 * the binding reason is the LAST clamp that actually cut the
 * number — when both clamps bite, the global split produced the
 * final figure, so it is named (the host cap is already visible
 * in the server profile's 已知的限流上限 row right above).
 *
 * Only reasons the inputs PROVE are ever named:
 * - HOST_CAP: a remembered HostMemory cap sits below desire;
 * - GLOBAL_CAP: the allowance came back below the effective
 *   desire (the weighted split across RUNNING tasks, or the
 *   cap alone below one task's desire);
 * - TASK_CEILING: desire IS the engine's own per-task ceiling
 *   (segmented MAX_THREADS / HLS width / FTP's 8) and nothing
 *   cut it further;
 * - UNLIMITED: the full desire was granted and the desire is
 *   not itself a ceiling artefact (a single-connection task's
 *   desire of 1 is a natural demand, never a "ceiling").
 * Any contradictory snapshot (negative numbers, desire above
 * the stated ceiling, an allowance above desire with no host
 * cap to explain the clamp) is UNKNOWN: the row shows the
 * granted number and names NO reason rather than inventing one.
 *
 * Pure — unit-tested. The caller (DownloadCenter
 * .connectionBudgetLineFor) gathers the live snapshot; this
 * object never reads settings, the store or the clock.
 * Wording follows the card's standing caliber: counts are
 * always 「N 条连接」 (v2.9), never 路/个线程.
 */
object ConnectionBudgetLine {

    enum class Reason { HOST_CAP, GLOBAL_CAP, TASK_CEILING, UNLIMITED, UNKNOWN }

    data class Verdict(val granted: Int, val reason: Reason, val line: String)

    fun judge(
        allowance: Int,
        desire: Int,
        hostCap: Int,
        taskCeiling: Int,
        globalCap: Int,
        liveConnections: Int,
        othersRunning: Boolean,
    ): Verdict {
        val hostBinds = hostCap > 0 && hostCap < desire
        val effectiveDesire = if (hostBinds) hostCap else desire
        val granted = minOf(allowance, effectiveDesire).coerceAtLeast(0)
        val contradictory = allowance < 0 || desire <= 0 || hostCap < 0 ||
            taskCeiling <= 0 || globalCap <= 0 || desire > taskCeiling ||
            (allowance > desire && !hostBinds)
        val reason = when {
            contradictory -> Reason.UNKNOWN
            allowance < effectiveDesire -> Reason.GLOBAL_CAP
            hostBinds -> Reason.HOST_CAP
            desire == taskCeiling && taskCeiling > 1 -> Reason.TASK_CEILING
            allowance >= desire -> Reason.UNLIMITED
            else -> Reason.UNKNOWN
        }
        val base = "现在分到 $granted 条连接"
        val livePart = if (liveConnections >= 0 && liveConnections != granted) {
            "，实际在跑 $liveConnections 条"
        } else {
            ""
        }
        val line = when (reason) {
            Reason.HOST_CAP ->
                "$base$livePart。这个站点之前多连接时限流过，最多用 $hostCap 条连接。"
            Reason.GLOBAL_CAP ->
                "$base$livePart。被全局连接上限压住：所有任务合计最多 $globalCap 条连接" +
                    if (othersRunning) "，正在与其他任务按优先级分。" else "。"
            Reason.TASK_CEILING ->
                "$base$livePart。已到这个任务自己的上限，最多 $taskCeiling 条连接。"
            Reason.UNLIMITED ->
                "$base$livePart。想要的连接都分到了，没有被压住。"
            Reason.UNKNOWN -> "$base$livePart。"
        }
        return Verdict(granted, reason, line)
    }
}
