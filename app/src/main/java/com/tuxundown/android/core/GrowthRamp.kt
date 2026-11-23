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
 * once per scaler tick — every 2 s on the segmented path, every
 * 1 s on the HLS path):
 *
 * - The initial grant is min(initialDesired, [BASE_GRANT]).
 *   [BASE_GRANT] is StartPlan.BURST_START (16): the v4.2 burst
 *   opening is NEVER smoothed — a fresh task still bursts in at
 *   16 exactly as before, and an HLS start width (<= 16) passes
 *   through whole.
 * - desired <= granted: granted follows desired DOWN (or holds)
 *   immediately and is returned as-is. Decreases are never
 *   smoothed: a falling global cap or a learned host cap takes
 *   effect on the very next adjudication, in one step, with no
 *   intermediate values.
 * - desired > granted: granted climbs by [stepFor] —
 *   max([STEP_FLOOR], desired / [STEP_DIVISOR]) — and the new
 *   granted is returned.
 *
 * Slope rationale: at least +8 per adjudication means the
 * segmented path (2 s ticks) gains at least 4 connections per
 * second, so small targets are reached in one or two steps; at
 * most about +25% of the target per adjudication means the
 * climb from the 16 baseline to any target up to 64 completes
 * in at most 4 adjudications (~8 s) — the same overall pace as
 * the old doubling ladder, except it starts at 16 instead of 4.
 * v7.7 (per-task top raised to 128): the same rule reaches 128
 * in exactly 4 adjudications too — stepFor(128) = 32, so the
 * grant walks 16 -> 48 -> 80 -> 112 -> 128 (~8 s on the
 * segmented path). No constant changed: the step is defined
 * from the target, so it scales with the new top on its own.
 * (Most targets take 3 or fewer; 59, 62 and 63 take 4, because
 * [stepFor] uses integer division and falls one short there —
 * the true counts are pinned by GrowthRampTest.)
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
class GrowthRamp(initialDesired: Int) {

    /** The count currently granted: what the spawn side may
     *  have open as a result of adjudications so far. Starts at
     *  min(initialDesired, [BASE_GRANT]); a negative initial
     *  desire is treated as 0. */
    var granted: Int = minOf(initialDesired.coerceAtLeast(0), BASE_GRANT)
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
