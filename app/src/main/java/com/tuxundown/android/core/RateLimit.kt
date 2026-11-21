package com.tuxundown.android.core

/**
 * Rate-limit evidence (v2.8): did this host refuse us because we
 * came with TOO MANY connections, or for an ordinary reason?
 *
 * The distinction the whole feature stands on:
 * - 429 is the server SAYING "slow down" — unambiguous. A single
 *   429 under parallel load is evidence enough.
 * - 403 usually means something else entirely: no permission, an
 *   expired signed link, a hotlink guard. Treating every 403 as
 *   rate limiting would punish innocent hosts with a thread cap
 *   that fixes nothing. A 403 only reads as rate limiting when
 *   SEVERAL connections are refused TOGETHER — at least
 *   [MIN_403_REJECTIONS] of them inside [WINDOW_MS], each while
 *   at least [MIN_THREADS_FOR_403] connections were running.
 *   One connection being refused (a probe, a single stream) is
 *   a permission story, never a load story.
 *
 * The verdict is the load level to remember — the highest
 * connection count at which the rejections happened. Halving it
 * into a ceiling is [HostMemory.recordRateLimit]'s job.
 *
 * Pure — unit-tested. The engine feeds it the rejections one
 * segmented run actually saw (primary source only).
 */
object RateLimit {

    /** One refused connection: what the server answered, when,
     *  and how many connections were running at that moment. */
    data class Rejection(val code: Int, val atMs: Long, val activeThreads: Int)

    const val WINDOW_MS = 10_000L
    const val MIN_403_REJECTIONS = 3
    const val MIN_THREADS_FOR_403 = 4

    /**
     * The thread count this host was rejecting load at, or 0
     * when the evidence does not look like rate limiting.
     */
    fun assess(events: List<Rejection>): Int {
        if (events.isEmpty()) return 0
        val at429 = events.filter { it.code == 429 }
        if (at429.isNotEmpty()) {
            return at429.maxOf { it.activeThreads }.coerceAtLeast(1)
        }
        val parallel403 = events
            .filter { it.code == 403 && it.activeThreads >= MIN_THREADS_FOR_403 }
            .sortedBy { it.atMs }
        if (parallel403.size < MIN_403_REJECTIONS) return 0
        // Sliding window: any MIN_403_REJECTIONS refusals inside
        // one WINDOW_MS convict; report the highest load among them.
        var i = 0
        for (j in parallel403.indices) {
            while (parallel403[j].atMs - parallel403[i].atMs > WINDOW_MS) i++
            if (j - i + 1 >= MIN_403_REJECTIONS) {
                return (i..j).maxOf { parallel403[it].activeThreads }
            }
        }
        return 0
    }
}
