package com.tuxundown.android.core

/**
 * Queue link pre-check (v2.8): while a task waits its turn, the
 * service gives its link one light HEAD request (the same shape
 * as [com.tuxundown.android.sniff.LinkRouter]'s probe) so a link
 * that is ALREADY dead can be flagged on the card — "预检提示：
 * 可能已失效" — instead of surprising the user when its turn
 * finally comes.
 *
 * The discipline that keeps this honest:
 * - Only 404 / 410 convict. Those are the two codes that mean
 *   "this URL is gone"; every other answer (200, a redirect, a
 *   401/403 refusal, a 5xx, a timeout, no network at all) proves
 *   nothing either way and never raises the flag.
 * - A flag is a WARNING, never a verdict: when the task's turn
 *   comes it starts exactly as before and the engine's real
 *   probe decides. The card copy says so.
 * - Manners: at most [MAX_CANDIDATES] tasks per pass (the front
 *   of the queue — list order IS queue order), and a checked
 *   task is not re-checked for [CACHE_TTL_MS], so a long queue
 *   costs a handful of tiny requests, not a crawl.
 *
 * Pure selection + verdict logic — unit-tested. The HTTP itself
 * lives in DownloadService (it needs the proxy state and the
 * task's session headers).
 */
object LinkPrecheck {

    /** Tasks checked per pass — the front of the queue only. */
    const val MAX_CANDIDATES = 3

    /** A checked task rests this long before it may be re-checked. */
    const val CACHE_TTL_MS = 10 * 60_000L

    /** The only two answers that mean "gone". */
    fun isDeadCode(code: Int): Boolean = code == 404 || code == 410

    /**
     * May this task be pre-checked now? QUEUED (not parked by an
     * outage — those already know their problem), due (not
     * waiting for a future schedule slot), fetchable by HEAD
     * (http/https only; FTP has no HEAD and HLS playlists are
     * covered too — a 404 on the playlist URL is the same
     * conviction), and not checked within the cache TTL.
     */
    fun isCandidate(t: TaskRecord, nowMs: Long): Boolean {
        if (t.status != TaskStatus.QUEUED) return false
        if (t.netDeferred) return false
        if (t.portalDeferred) return false // v4.4: a portal park knows its problem too
        if (t.scheduledAt > nowMs) return false
        if (FtpClient.isFtpUrl(t.url)) return false
        val scheme = LinkSchemes.schemeOf(t.url)
        if (scheme != "http" && scheme != "https") return false
        return t.precheckAtMs <= 0 || nowMs - t.precheckAtMs > CACHE_TTL_MS
    }

    /** The front of the queue that is due a check, in queue order. */
    fun pickCandidates(
        tasks: List<TaskRecord>,
        nowMs: Long,
        limit: Int = MAX_CANDIDATES,
    ): List<TaskRecord> = tasks.filter { isCandidate(it, nowMs) }.take(limit)
}
