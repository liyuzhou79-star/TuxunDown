package com.tuxundown.android.core

/**
 * Speed-aware stall eviction (v4.6, audit round 31 step B) — pure
 * logic, unit-tested.
 *
 * The segmented sampler evicts a part whose position has not
 * advanced for a while while the rest of the task IS moving: the
 * connection is dropped and the worker retries the part from its
 * current offset (bytes already written and the chunk bitmap are
 * untouched — see DownloadEngine.downloadRange). Until v4.5 the
 * patience was a flat 12 s at every speed. That is the wrong shape
 * at both ends:
 * - At high aggregate speed a dead connection wastes ABSOLUTE
 *   bandwidth: a task moving at 4 MB/s loses ~48 MB of would-be
 *   progress to one connection stalled for the full 12 s.
 * - At low aggregate speed every connection looks slow; churning
 *   them costs a fresh TCP+TLS handshake each time and buys
 *   nothing, so patience there must never be WORSE than the old
 *   flat 12 s.
 * So the threshold FALLS monotonically as the task's aggregate
 * speed rises, from [MAX_THRESHOLD_MS] down to a floor of
 * [MIN_THRESHOLD_MS].
 *
 * Which speed? The caller passes task.speedBps — the sampler's
 * EMA ((3*old + instant)/4, updated on the same 1 s tick just
 * before the eviction block, and the same number ThreadScaler
 * judges). The tick's INSTANTANEOUS speed was rejected: it is a
 * single 1 s byte delta, so one bursty tick (a big buffer flush
 * from a sibling connection) would slash the threshold for that
 * tick and evict a connection that is merely slow-starting,
 * while one quiet tick would spare a genuinely dead one. The
 * EMA needs ~4 ticks to swing fully, which is exactly the
 * stability a kill decision wants, and it costs no freshness
 * that matters: a stall is by definition several seconds old
 * before it can be judged at all.
 *
 * "Stalled" needs no new definition: the caller measures
 * now - ActiveChunk.lastAdvanceMs, and lastAdvanceMs advances
 * ONLY when bytes are written to the file (downloadRange's read
 * loop) — plus the sampler's own anti-spam stamp after an
 * eviction, which grants the retried part a fresh full window.
 */
object StallPolicy {

    /** Patience at (and below) [LOW_SPEED_BPS]: the pre-v4.6 flat
     *  value — low-speed behaviour never gets worse. */
    const val MAX_THRESHOLD_MS = 12_000L

    /**
     * The floor, at (and above) [HIGH_SPEED_BPS]. 4 s is four
     * sampler ticks: a verdict needs more than one tick of
     * evidence by construction (the stall age itself must exceed
     * this), and 4 s sits safely above the jitter it must not
     * punish — a fresh connection's TCP+TLS handshake is a few
     * round trips (well under 1 s even on a 300 ms RTT link),
     * TCP slow start ramps over a similar span, and the 1 s tick
     * quantises the measured stall age by up to a full second.
     * Below ~4 s a slow-but-alive connection could be killed
     * before its first bytes land, and its replacement would face
     * the same clock — a thrash loop. (A connection that is
     * truly dead is still bounded independently: every engine
     * connection carries a 30 s read timeout, set in
     * openFollowingRedirects.)
     */
    const val MIN_THRESHOLD_MS = 4_000L

    /** At or below this aggregate speed the threshold is the max:
     *  256 KiB/s. Below it, per-connection shares are so small
     *  that a few seconds of one connection's silence is within
     *  normal variance (server throttling, shared bottleneck). */
    const val LOW_SPEED_BPS = 256L * 1024

    /** At or above this aggregate speed the threshold is the
     *  floor: 4 MiB/s. Above it, each extra second of patience
     *  costs megabytes of would-be progress, so only the
     *  anti-thrash floor remains. */
    const val HIGH_SPEED_BPS = 4L * 1024 * 1024

    /**
     * Patience for a task whose aggregate (EMA) speed is
     * [taskSpeedBps]: [MAX_THRESHOLD_MS] at/below
     * [LOW_SPEED_BPS], [MIN_THRESHOLD_MS] at/above
     * [HIGH_SPEED_BPS], linearly interpolated between. Monotonic
     * non-increasing in speed; always within
     * [MIN_THRESHOLD_MS, MAX_THRESHOLD_MS]. Non-positive speeds
     * clamp to the max (see [shouldEvict] for why they never
     * evict anyway).
     */
    fun thresholdMs(taskSpeedBps: Long): Long {
        if (taskSpeedBps <= LOW_SPEED_BPS) return MAX_THRESHOLD_MS
        if (taskSpeedBps >= HIGH_SPEED_BPS) return MIN_THRESHOLD_MS
        val span = HIGH_SPEED_BPS - LOW_SPEED_BPS
        val drop = (taskSpeedBps - LOW_SPEED_BPS) *
            (MAX_THRESHOLD_MS - MIN_THRESHOLD_MS) / span
        return MAX_THRESHOLD_MS - drop
    }

    /**
     * The eviction verdict for one active part.
     *
     * - [activeConnections] <= 1: NEVER evict. The single
     *   connection IS the task's whole speed — dropping it has
     *   nowhere better to go and would only pay a reconnect for
     *   the same server path; a truly dead connection is still
     *   bounded by the 30 s read timeout. (The pre-v4.6
     *   `speed > 0` gate implied this; it is explicit now.)
     * - [taskSpeedBps] <= 0: never evict. If NOTHING is moving,
     *   the cause is systemic (network down, server down) and
     *   disconnecting the whole active set would churn every
     *   connection at once for no gain.
     * - Otherwise evict iff the stall is STRICTLY longer than
     *   [thresholdMs]: a stall exactly equal to the threshold
     *   does NOT evict (same `>` the flat rule used), so the
     *   boundary tick always grants one more second.
     */
    fun shouldEvict(taskSpeedBps: Long, stalledMs: Long, activeConnections: Int): Boolean {
        if (activeConnections <= 1) return false
        if (taskSpeedBps <= 0L) return false
        return stalledMs > thresholdMs(taskSpeedBps)
    }
}
