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
 * - And only when the answer came on a chain that never left
 *   the task's own host (v8.6, Step B of the round-67 review).
 *   Since v8.2 the probe follows redirects manually and a
 *   cross-host hop goes out WITHOUT the task's Cookie
 *   (SessionScope withholds it from any other host), so a
 *   404/410 from another host was answered to a
 *   credential-reduced request — it can be a login wall
 *   wearing a not-found mask, not proof the file is gone.
 *   The flag simply stays down; the real start re-probes
 *   for itself regardless, exactly as with any other
 *   inconclusive answer.
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
     * The flag verdict (v8.6, Step B): [isDeadCode] AND the
     * answer arrived on a chain that never left the task's own
     * host. Once a hop leaves the host, the request that earns
     * the final answer carries no Cookie (SessionScope), so
     * even a 404/410 from out there proves no more than a 403
     * does — nothing either way — and must not convict.
     */
    fun isDeadVerdict(code: Int, chainLeftHost: Boolean): Boolean =
        !chainLeftHost && isDeadCode(code)

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

    // ---- v7.6 (audit round 61, Step B-2): empty-pass backoff
    // + the enqueue wake hook. The pre-check loop used to wake
    // every 30 s for the service's whole lifetime even when a
    // pass found ZERO candidates (pure-park / pure-future-
    // schedule scenes empty-scanned for hours). Now a pass
    // with candidates resets the interval to the base, and
    // each empty pass doubles it up to a stated cap. The
    // pre-check stays a WARNING flag only — this backoff only
    // ever delays a warning, never a drain pick, a park
    // release, or a download.

    /** Pass interval right after a pass that found work. */
    const val BASE_INTERVAL_MS = 30_000L

    /** The empty-pass backoff cap: at most 8 minutes between
     *  passes once fully backed off. WORST-CASE WARNING
     *  LATENCY: a task that becomes a candidate WITHOUT going
     *  through the enqueue funnel (below) while the loop sits
     *  at the cap waits up to MAX_INTERVAL_MS before its first
     *  pre-check pass — a delayed "可能已失效" warning only;
     *  the task itself starts on its turn and re-probes for
     *  real regardless (see the class doc). */
    const val MAX_INTERVAL_MS = 480_000L

    /**
     * The interval to sleep after a pass: [BASE_INTERVAL_MS]
     * when the pass found candidates; after an empty pass,
     * double the previous interval (never below the base),
     * capped at [MAX_INTERVAL_MS].
     */
    fun intervalAfterPass(candidateCount: Int, previousMs: Long): Long {
        if (candidateCount > 0) return BASE_INTERVAL_MS
        val doubled = maxOf(previousMs, BASE_INTERVAL_MS) * 2
        return minOf(doubled, MAX_INTERVAL_MS)
    }

    // The cheap enqueue hook: DownloadCenter.enqueue (the one
    // funnel every manual (re-)queue passes through) bumps
    // this generation and wakes the sleeper, so a freshly
    // queued task's first pre-check is prompt and the backoff
    // resets on the next pass — instead of waiting out the
    // capped interval above. The wait is a monitor wait, not
    // a poll: the sleeping thread wakes only on the notify,
    // on its timeout, or on interrupt (service destroy).
    private val wakeMonitor = Object()

    @Volatile
    private var queueGeneration = 0L

    /** Current queue generation (snapshot before a pass, so
     *  an enqueue DURING the pass also cuts the next sleep). */
    fun queueGeneration(): Long = queueGeneration

    /** Called by the enqueue funnel when a task (re-)queues. */
    fun noteQueueChanged() {
        synchronized(wakeMonitor) {
            queueGeneration++
            wakeMonitor.notifyAll()
        }
    }

    /**
     * Sleep for up to [timeoutMs], returning early the moment
     * the queue generation moves past [sinceGeneration].
     * Throws InterruptedException exactly like Thread.sleep,
     * so the caller's destroy-interrupt handling is unchanged.
     */
    @Throws(InterruptedException::class)
    fun awaitInterval(timeoutMs: Long, sinceGeneration: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(wakeMonitor) {
            while (queueGeneration == sinceGeneration) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) return
                wakeMonitor.wait(left)
            }
        }
    }
}
