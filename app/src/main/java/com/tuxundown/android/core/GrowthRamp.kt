package com.tuxundown.android.core

/**
 * Transient connection-growth ramp (v5.3): the pure rule that
 * smooths how fast a running task may OPEN new connections when
 * its desired count jumps — without ever delaying a decrease.
 *
 * Why this exists (audit round 37, v5.2): the global connection
 * allowance is a hard bound in steady state, but across
 * start/join transients it is only a tick-corrected target.
 * When tasks join one after another, each newcomer spawns its
 * full freshly-read share at once while the incumbents still
 * hold their old, larger counts — the staggered-join worst case
 * peaked at ~229 live connections against a cap of 128 (~1.8x).
 * The surplus does useful work and sheds at chunk boundaries,
 * so the fix is not a hard cap (that would mean killing workers
 * mid-chunk); it is to make the SPAWN side climb in steps, so a
 * late joiner's share arrives over a few adjudications while
 * the incumbents' shed catches up. The peak becomes roughly
 * cap + one step instead of cap x 1.8.
 *
 * The rule, per adjudication (the engine calls [allow] exactly
 * once per scaler evaluation — on the segmented path every 1 s
 * while the opening ladder is probing and every 2 s in steady
 * state since v7.7 Step B3, every 1 s on the HLS path):
 *
 * - The initial grant is min(initialDesired, baseGrant), where
 *   baseGrant defaults to [BASE_GRANT]. [BASE_GRANT] is
 *   StartPlan.BURST_START (32 since v7.7 Step B3): the burst
 *   opening is NEVER smoothed — a fresh task still bursts in
 *   at the full burst width exactly as before, and an HLS
 *   start width (<= 16) passes through whole.
 * - v7.7 (Step B3) full-width exception: when the start count
 *   IS a remembered per-host optimum (StartPlan's hint branch,
 *   probing off), the engine passes baseGrant = the start
 *   target itself, so the initial grant is the whole learned
 *   count — a known server no longer re-climbs from the burst
 *   width to a count it already proved. The global allowance
 *   and the rate-limit cap have already pressed that target
 *   before the ramp sees it, so the exception can never widen
 *   a start past either bound.
 * - desired <= granted: granted follows desired DOWN (or holds)
 *   immediately and is returned as-is. Decreases are never
 *   smoothed: a falling global cap or a learned host cap takes
 *   effect on the very next adjudication, in one step, with no
 *   intermediate values.
 * - desired > granted: granted climbs by [stepFor] —
 *   max([STEP_FLOOR], desired / [STEP_DIVISOR]) — and the new
 *   granted is returned.
 *
 * Slope rationale: at least +8 per adjudication, so small
 * targets are reached in one or two steps; at most about +25%
 * of the target per adjudication means the climb completes in
 * a bounded number of adjudications. v7.7 history: with the
 * burst at 16 the climb to 64 took at most 4 adjudications and
 * to 128 exactly 4 (16 -> 48 -> 80 -> 112 -> 128). Step B3
 * raised the burst to 32 (StartPlan.BURST_START; the step
 * constants did NOT change — the step is defined from the
 * target, so it scales on its own, exactly as Step B2 found):
 * the grant now walks 32 -> 64 -> 96 -> 128 (3 adjudications)
 * and 32 -> 48 -> 64 (2). A few near-top targets (e.g. 127)
 * still take 4, because [stepFor] uses integer division — the
 * true counts are pinned by GrowthRampTest.
 *
 * Spawn-side ONLY: [allow] decides how many workers may be
 * spawned, never how many must die. Workers above the granted
 * count are never killed by this class; they exit through the
 * existing workerLoop effTarget check at chunk boundaries,
 * exactly as before.
 *
 * FTP is deliberately NOT wired through this ramp:
 * FtpSegmented's worker count is fixed for the whole run
 * (<= 8, decided once at start, no dynamic growth), so there
 * is no transient growth to smooth.
 *
 * One instance per task run. Pure and deterministic — no
 * clock, no threads, no I/O.
 */
class GrowthRamp(
    initialDesired: Int,
    baseGrant: Int = BASE_GRANT,
) {

    /** The count currently granted: what the spawn side may
     *  have open as a result of adjudications so far. Starts at
     *  min(initialDesired, baseGrant); a negative initial
     *  desire (or base grant) is treated as 0. [baseGrant] is
     *  [BASE_GRANT] for every start except a remembered-optimum
     *  start, where the engine passes the start target itself
     *  so the learned count opens full width (see class doc). */
    var granted: Int = minOf(
        initialDesired.coerceAtLeast(0), baseGrant.coerceAtLeast(0)
    )
        private set

    /**
     * One adjudication: given the count the task wants RIGHT
     * NOW (scaler target pressed by the freshly-read global
     * allowance), return the count the spawn side may grow to.
     * Decreases apply instantly; increases climb by [stepFor].
     * A negative [desired] is treated as 0; the result is never
     * negative.
     */
    fun allow(desired: Int): Int {
        val want = desired.coerceAtLeast(0)
        granted = if (want <= granted) {
            want
        } else {
            minOf(want, granted + stepFor(want))
        }
        return granted
    }

    companion object {
        /** The unsmoothed opening grant: the v4.2 burst width.
         *  Anything at or below this is granted immediately. */
        const val BASE_GRANT = StartPlan.BURST_START

        /** Smallest climb per adjudication, so small targets
         *  are reached in one or two steps. */
        const val STEP_FLOOR = 8

        /** The climb is also at least desired / STEP_DIVISOR,
         *  so large targets are reached in a bounded number of
         *  adjudications (~3 from the baseline to 64). */
        const val STEP_DIVISOR = 4

        fun stepFor(desired: Int): Int =
            maxOf(STEP_FLOOR, desired.coerceAtLeast(0) / STEP_DIVISOR)
    }
}
