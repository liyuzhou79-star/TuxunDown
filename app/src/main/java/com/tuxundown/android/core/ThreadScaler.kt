package com.tuxundown.android.core

/**
 * Dynamic thread scaling (v2.3): the mid-download counterpart of
 * the opening thread probe.
 *
 * The three thread-count mechanisms divide the work like this:
 * - PROBING (this class, opening phase) decides the START: the
 *   classic ladder 4 -> 8 -> 16 -> 32, doubling every evaluation
 *   while the speed keeps growing by more than 10%, stopping at
 *   the first plateau. A remembered host hint (HostMemory) skips
 *   this phase entirely and starts in steady state.
 * - SCALING (this class, steady state) manages the MIDDLE: it
 *   keeps asking whether the current count still earns its keep.
 *   Add connections while the speed genuinely responds; take
 *   connections away when the line has been flat for a while
 *   (fewer connections = fewer server complaints, less battery,
 *   same speed) or when the speed is actively declining.
 * - HOST MEMORY stores the END: whatever count the scaler holds
 *   when a segmented download completes is what gets remembered
 *   as that host's optimum for next time.
 *
 * The scaler only ever sets a TARGET. The engine honours a lower
 * target by letting surplus workers exit when they come back for
 * their next chunk — a worker mid-chunk is never killed, so a
 * shrink can never tear a part in half — and honours a higher
 * target by spawning workers up to it.
 *
 * Anti-flap (hysteresis), three layers:
 * - a steady-state change needs its signal on TWO consecutive
 *   evaluations (the probe-down needs THREE flat ones);
 * - after every change the scaler spends up to two evaluations
 *   JUDGING it before any further change: a grow that bought
 *   less than 5% twice in a row is reverted and its count is
 *   remembered as a learned ceiling; a shrink that cost more
 *   than 10% is restored and its count becomes a learned floor;
 * - learned ceilings/floors hold for the rest of the run, so the
 *   count converges instead of oscillating.
 *
 * Pure and deterministic (one [onEval] call per evaluation tick,
 * no clock, no threads) — unit-tested with scripted speed series.
 */
class ThreadScaler(
    initialTarget: Int,
    probing: Boolean,
    private val maxThreads: Int = DownloadEngine.MAX_THREADS,
) {
    /** Current target worker count. The card displays this value
     *  (via TaskRecord.threads), so it is always the live number. */
    var target: Int = initialTarget.coerceIn(MIN_TARGET, maxThreads)
        private set

    /** True during the opening ladder phase. */
    var probing: Boolean = probing
        private set

    /** A count a failed grow proved useless this run (0 = none). */
    var learnedCeiling: Int = 0
        private set

    /** A count a harmful shrink proved necessary this run (0 = none). */
    var learnedFloor: Int = 0
        private set

    private var lastSpeed = 0L
    private var evalsSinceChange = BIG
    private var pendingGrow = 0
    private var pendingShrink = 0
    private var flatEvals = 0

    // Verdict state for the most recent change.
    private var verdictFor = NONE
    private var verdictStrikes = 0
    private var preChangeTarget = 0
    private var preChangeSpeed = 0L

    /**
     * One evaluation. [speedBps] is the sampler's current smoothed
     * speed; [canGrow] is false when no fresh chunks remain (the
     * endgame cannot use more workers on new chunks); [active] is
     * false when no work remains at all. Returns the (possibly
     * unchanged) target.
     */
    fun onEval(speedBps: Long, canGrow: Boolean, active: Boolean): Int {
        if (!active) return target
        evalsSinceChange++
        if (speedBps <= 0L) {
            // No information (start-up, a stall): never act on it.
            pendingGrow = 0
            pendingShrink = 0
            flatEvals = 0
            lastSpeed = speedBps
            return target
        }

        // ---- judge the previous change before making another ----
        if (verdictFor == GROW) {
            if (speedBps * 100 >= preChangeSpeed * VERDICT_GROW_OK_PCT) {
                verdictFor = NONE // the grow earned its keep
            } else if (++verdictStrikes >= VERDICT_STRIKES) {
                // Two disappointing evaluations in a row: the added
                // connections bought nothing. Revert and remember.
                learnedCeiling = target
                target = preChangeTarget
                verdictFor = NONE
                verdictStrikes = 0
                evalsSinceChange = 0
                pendingGrow = 0
                lastSpeed = speedBps
                return target
            } else {
                lastSpeed = speedBps
                return target // still judging
            }
        } else if (verdictFor == SHRINK) {
            if (speedBps * 100 < preChangeSpeed * VERDICT_SHRINK_BAD_PCT) {
                // The shrink cost real speed: restore and remember
                // the floor so we never probe below it again.
                learnedFloor = preChangeTarget
                target = preChangeTarget
                verdictFor = NONE
                evalsSinceChange = 0
                pendingShrink = 0
                flatEvals = 0
                lastSpeed = speedBps
                return target
            }
            if (evalsSinceChange >= VERDICT_EVALS) {
                verdictFor = NONE // shrink accepted, speed held
            } else {
                lastSpeed = speedBps
                return target // still judging
            }
        }

        // ---- opening ladder ----
        if (probing) {
            val grows = canGrow && target < maxThreads &&
                (lastSpeed == 0L || speedBps * 100 > lastSpeed * PROBE_GROW_PCT)
            if (grows) {
                changeTo(minOf(target * 2, maxThreads), GROW, speedBps)
                if (target >= maxThreads) probing = false
            } else {
                // First plateau (or nothing left to grow into):
                // settled — steady state takes over from here.
                probing = false
            }
            lastSpeed = speedBps
            return target
        }

        // ---- steady state ----
        val floorLimit = maxOf(MIN_TARGET, learnedFloor)
        val ceilingLimit = if (learnedCeiling > 0) learnedCeiling else maxThreads

        // Grow: speed responding strongly, twice in a row.
        val growSignal = canGrow && target < ceilingLimit && lastSpeed > 0 &&
            speedBps * 100 > lastSpeed * GROW_SIGNAL_PCT
        pendingGrow = if (growSignal) pendingGrow + 1 else 0
        if (pendingGrow >= CONFIRMATIONS && evalsSinceChange >= MIN_EVALS_BETWEEN_CHANGES) {
            val step = maxOf(target + 2, target * 3 / 2)
            changeTo(minOf(step, ceilingLimit), GROW, speedBps)
            pendingGrow = 0
            lastSpeed = speedBps
            return target
        }

        // Shrink signal A: the speed is actively declining.
        val declineSignal = target > floorLimit && lastSpeed > 0 &&
            speedBps * 100 < lastSpeed * DECLINE_SIGNAL_PCT
        pendingShrink = if (declineSignal) pendingShrink + 1 else 0

        // Shrink signal B: the line has been flat — the current
        // count is more than the server/network rewards.
        val flat = lastSpeed > 0 &&
            speedBps * 100 >= lastSpeed * FLAT_LOW_PCT &&
            speedBps * 100 <= lastSpeed * FLAT_HIGH_PCT
        flatEvals = if (flat) flatEvals + 1 else 0

        if ((pendingShrink >= CONFIRMATIONS || flatEvals >= FLAT_EVALS_TO_SHRINK) &&
            evalsSinceChange >= MIN_EVALS_BETWEEN_CHANGES && target > floorLimit
        ) {
            val step = maxOf(floorLimit, minOf(target * 3 / 4, target - 1))
            if (step < target) {
                changeTo(step, SHRINK, speedBps)
                pendingShrink = 0
                flatEvals = 0
                lastSpeed = speedBps
                return target
            }
        }

        lastSpeed = speedBps
        return target
    }

    private fun changeTo(newTarget: Int, dir: Int, speedBps: Long) {
        preChangeTarget = target
        // The verdict baseline is the speed measured at the moment
        // of the change: did the speed AFTER the change beat the
        // speed we had when we made it?
        preChangeSpeed = speedBps
        target = newTarget
        // The ladder judges itself (first plateau = settled), so
        // verdicts are armed only for STEADY-state changes.
        verdictFor = if (probing) NONE else dir
        verdictStrikes = 0
        evalsSinceChange = 0
    }

    companion object {
        const val MIN_TARGET = 2
        /** "Long ago" — the first change never waits on a cooldown. */
        private const val BIG = 1_000_000
        private const val NONE = 0
        private const val GROW = 1
        private const val SHRINK = 2
        /** Probe ladder: grow while speed rises by >10%. */
        private const val PROBE_GROW_PCT = 110
        /** Steady grow: signal needs >8% rise, twice in a row. */
        private const val GROW_SIGNAL_PCT = 108
        private const val CONFIRMATIONS = 2
        /** A grow is kept only if it bought at least +5% over the
         *  pre-grow speed; two consecutive evaluations below that
         *  bar revert it (same speed on fewer connections is the
         *  better outcome — see VERDICT_STRIKES). */
        private const val VERDICT_GROW_OK_PCT = 105
        private const val VERDICT_STRIKES = 2
        /** A shrink "hurt" if speed fell below 90% of before. */
        private const val VERDICT_SHRINK_BAD_PCT = 90
        private const val VERDICT_EVALS = 2
        /** Decline: speed below 90% of the previous evaluation. */
        private const val DECLINE_SIGNAL_PCT = 90
        /** Flat band: within ±5% of the previous evaluation. */
        private const val FLAT_LOW_PCT = 95
        private const val FLAT_HIGH_PCT = 105
        private const val FLAT_EVALS_TO_SHRINK = 3
        private const val MIN_EVALS_BETWEEN_CHANGES = 2
    }
}
