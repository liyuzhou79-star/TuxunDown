package com.tuxundown.android.core

/**
 * v3.7: "retry differently" for a batch of FAILED tasks —
 * change HOW the retry talks to the server before re-queueing:
 *
 * - swap the task's User-Agent for a preset. Some servers
 *   serve (or refuse) content by UA — anti-hotlink rules that
 *   only accept a browser UA, or the opposite, a download-tool
 *   UA. The task header is the highest-precedence UA there is
 *   (see UserAgents.resolve), so writing it here really does
 *   change what the server sees.
 * - force single-thread. Some servers fall apart under many
 *   parallel connections (resets, corruption, bans); one calm
 *   connection is slower but gets through.
 *
 * Pure decisions only — which tasks are eligible, what the
 * new headers are, how the single-thread flag moves. The
 * application (mutating records + enqueue) lives in
 * DownloadCenter.retryWithStrategy. Downloaded progress is
 * never touched: the chunk bitmap survives, like the plain
 * retry button has always promised.
 */
object RetryStrategy {

    /** Only a FAILED task can be strategy-retried. (PAUSED has
     *  its own 继续； RUNNING/QUEUED need nothing; DONE is done.) */
    fun eligible(status: TaskStatus): Boolean = status == TaskStatus.FAILED

    fun countEligible(statuses: List<TaskStatus>): Int = statuses.count { eligible(it) }

    /**
     * The headers after applying a UA choice:
     * - [uaPreset] == null → the user picked 不改： headers are
     *   returned untouched.
     * - [uaPreset] is blank (the 默认 preset) → the task-level
     *   User-Agent header is REMOVED, so resolution falls back
     *   to the global setting / built-in default.
     * - otherwise → headers["User-Agent"] = the preset string.
     * Every other header (Cookie, Referer, …) is preserved.
     */
    fun headersWithUa(old: Map<String, String>, uaPreset: String?): Map<String, String> {
        if (uaPreset == null) return old
        val out = LinkedHashMap(old)
        if (uaPreset.isBlank()) out.remove("User-Agent") else out["User-Agent"] = uaPreset
        return out
    }

    /** Forcing single-thread only ever turns the flag ON in a
     *  strategy retry; turning it back off is the per-task
     *  decision of a later plain retry succeeding, not a batch
     *  side effect. */
    fun singleThreadAfter(old: Boolean, force: Boolean): Boolean = old || force
}
