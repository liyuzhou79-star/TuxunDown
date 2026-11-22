package com.tuxundown.android.core

/**
 * HLS fetch-width plan (v4.3): the pure decisions behind how many
 * segments DownloadEngine.runHls fetches in parallel — extracted
 * so the whole rule table is unit-testable without an engine.
 *
 * Before v4.3 the HLS fetch pool was a FIXED 4 threads with a
 * prefetch window of 6, and runHls never read the global
 * connection allowance at all: a fast host could not go past 4,
 * and a task the global cap had squeezed to 1 still opened 4.
 *
 * The plan splits the decision into three honest pieces:
 *
 * 1. [plan] — fixed for the run. The MAX width is
 *    [DownloadEngine.HLS_MAX_PARALLEL] (16): an HLS segment is
 *    typically a few seconds of video and not large, so 16
 *    concurrent fetches is already 4x the old fixed width; past
 *    that the writer (a single sequential merge) and the ~48 MB
 *    in-flight byte budget become the binding constraints, so
 *    more width buys little and mostly invites per-IP rate
 *    limiting. A learned rate-limit cap for the host (HostMemory,
 *    raw — any positive value honoured, see below) and the
 *    segment count itself can only lower the max. The START
 *    width is the remembered per-host optimum when one exists,
 *    clamped to [MIN_START]..max; with no memory the start is
 *    [DEFAULT_START] (8 — twice the old fixed width: noticeably
 *    more aggressive, still far from the kind of burst that
 *    earns a 429).
 *
 * 2. [HlsScaler] — the live width between start and max,
 *    re-judged once per sampler tick from evidence the HLS loop
 *    actually has (see its doc for why these signals and not
 *    others).
 *
 * 3. The global ALLOWANCE is deliberately NOT part of [plan]'s
 *    output: it changes while the run is in flight (another task
 *    starting or finishing rebalances everyone), so baking it
 *    into a fixed plan would freeze a stale value. The engine
 *    re-reads it on every sampler tick and applies [gate] —
 *    exactly how the segmented path treats its allowance.
 */
object HlsPlan {

    /** Start width with no host memory: twice the pre-v4.3
     *  fixed width, half the max. */
    const val DEFAULT_START = 8

    /** Floor of the START clamp for a remembered hint. A hint
     *  below this is treated as "barely parallel" and raised to
     *  it — unless the max itself is lower (a tiny playlist or a
     *  tight cap), in which case the max wins. */
    const val MIN_START = 2

    data class Plan(val startWidth: Int, val maxWidth: Int)

    /**
     * @param hint remembered per-host optimum (0 = unknown).
     *   For HLS this is the same HostMemory entry the segmented
     *   path settles — a host-level parallelism memory, reused
     *   here clamped into the much narrower HLS band.
     * @param cap remembered rate-limit ceiling for the host,
     *   raw (0 = none). Any positive value bounds the max —
     *   HostMemory only ever writes caps >= its MIN_CAP, and a
     *   corrupt smaller positive value can only make the run
     *   narrower, never wider, so no band validation is needed
     *   for safety here.
     * @param totalSegments segment count of the playlist; the
     *   width can never usefully exceed it.
     */
    fun plan(hint: Int, cap: Int, totalSegments: Int): Plan {
        val total = totalSegments.coerceAtLeast(1)
        var maxWidth = minOf(DownloadEngine.HLS_MAX_PARALLEL, total)
        if (cap > 0) maxWidth = minOf(maxWidth, cap)
        maxWidth = maxWidth.coerceAtLeast(1)
        val startWidth = when {
            maxWidth <= MIN_START -> maxWidth
            hint > 0 -> hint.coerceIn(MIN_START, maxWidth)
            else -> minOf(DEFAULT_START, maxWidth)
        }
        return Plan(startWidth = startWidth, maxWidth = maxWidth)
    }

    /**
     * The runtime gate: the allowance the global connection cap
     * grants this task RIGHT NOW, applied to the scaler's width.
     * Int.MAX_VALUE (no cap) passes the width through untouched;
     * an allowance of 0 (or less, defensively) still leaves 1 —
     * a running task must always be able to fetch the segment at
     * its write cursor, or it could never finish and free its
     * slot.
     */
    fun gate(scalerWidth: Int, allowance: Int): Int =
        minOf(scalerWidth, allowance).coerceAtLeast(1)
}

/**
 * Steady-state HLS width scaler (v4.3): decides, once per
 * sampler tick (1 s), whether the live fetch width grows, holds
 * or shrinks between the plan's start and max.
 *
 * The signals are the ones this loop can measure HONESTLY:
 *
 * - writerStarved: at least once during the tick the writer
 *   reached a segment whose fetch had NOT finished yet. That is
 *   the definition of fetch-bound — the merge was waiting on
 *   the network, so more width can actually help. (The reverse
 *   signal, "speed went flat", is NOT used on its own: the
 *   speed the sampler sees is WRITE-side, so a flat line usually
 *   means the writer or the byte budget is the bottleneck, and
 *   shrinking the fetch width for that would punish the wrong
 *   side. Flatness only matters as the verdict on a grow — see
 *   below.)
 * - retryFailures: segment fetch attempts that failed during
 *   the tick (the engine counts a retry per re-attempt; a
 *   segment that ultimately fails ends the run, so attempt-level
 *   failures are the only failure signal a live scaler ever
 *   gets). Any failure shrinks the width — a host that is
 *   starting to refuse load gets relief immediately.
 *
 * Rules:
 * - Any retry failure in the tick: width -= [SHRINK_STEP]
 *   (floor 1), grow streak and grow verdict reset.
 * - Otherwise, [GROW_STREAK_TICKS] consecutive starved ticks:
 *   width += [GROW_STEP] (ceiling maxWidth), and the speed at
 *   the grow is remembered.
 * - [VERDICT_TICKS] ticks after a grow, the grow is judged: if
 *   the speed has not beaten the grow-time speed by
 *   [GROW_GAIN], the grow is undone (width -= [GROW_STEP]).
 *   This is the anti-flap: width that did not pay for itself is
 *   handed back instead of lingering.
 * - Every other tick: hold.
 *
 * Pure and deterministic — no clock, no randomness; the engine
 * feeds it one tick at a time.
 */
class HlsScaler(startWidth: Int, val maxWidth: Int) {

    var width: Int = startWidth.coerceIn(1, maxWidth.coerceAtLeast(1))
        private set

    private var starvedStreak = 0
    /** Ticks since the last grow awaiting its verdict; -1 = none. */
    private var verdictTicks = -1
    private var growSpeedBps = 0L

    fun onTick(speedBps: Long, writerStarved: Boolean, retryFailures: Int): Int {
        if (retryFailures > 0) {
            width = (width - SHRINK_STEP).coerceAtLeast(MIN_LIVE_WIDTH)
            starvedStreak = 0
            verdictTicks = -1
            return width
        }
        if (verdictTicks >= 0) {
            verdictTicks++
            if (verdictTicks >= VERDICT_TICKS) {
                if (speedBps < growSpeedBps * GROW_GAIN) {
                    width = (width - SHRINK_STEP).coerceAtLeast(MIN_LIVE_WIDTH)
                }
                verdictTicks = -1
            }
            return width
        }
        if (writerStarved) {
            starvedStreak++
            if (starvedStreak >= GROW_STREAK_TICKS && width < maxWidth) {
                width = minOf(maxWidth, width + GROW_STEP)
                growSpeedBps = speedBps
                verdictTicks = 0
                starvedStreak = 0
            }
        } else {
            starvedStreak = 0
        }
        return width
    }

    companion object {
        const val GROW_STEP = 2
        const val SHRINK_STEP = 2
        const val GROW_STREAK_TICKS = 2
        const val VERDICT_TICKS = 2
        const val GROW_GAIN = 1.10
        const val MIN_LIVE_WIDTH = 1
    }
}
