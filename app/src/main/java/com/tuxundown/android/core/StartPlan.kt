package com.tuxundown.android.core

/**
 * Start-of-run thread plan (v4.2): the pure decision behind the
 * segmented engine's opening move — how many workers to spawn
 * first, and whether the ThreadScaler's opening ladder still
 * runs. Extracted from DownloadEngine.runSegmented so the whole
 * rule table is unit-testable without an engine.
 *
 * The v4.2 policy is "lay it out full, then trim" (先铺满、再修剪）:
 * surplus connections are cheap to shed — the scaler shrinks in
 * steady state and surplus workers exit at chunk boundaries —
 * while starting too low costs real time, because every ladder
 * step up has to wait for its evaluation ticks. So an unknown
 * host no longer tiptoes in from 4 (the pre-v4.2 behaviour, audit
 * A9): it bursts in at [BURST_START] and lets the scaler sort it
 * out. A resume likewise restarts at its persisted count instead
 * of being squeezed back to at most 8 — but probing stays ON for
 * resumes, because yesterday's count says nothing certain about
 * today's network; the ladder/scaler re-judges it from there.
 *
 * The one start that does NOT probe is a remembered optimum
 * (v1.5, unchanged): a fresh start with a valid hint begins AT
 * the hint in steady state — the ramp-up on a known server is
 * pure waste.
 *
 * The remembered rate-limit ceiling (HostMemory cap) bounds
 * EVERY start: [bound] is the cap when it sits in HostMemory's
 * own legal band (MIN_CAP..MAX_THREADS), and MAX_THREADS
 * otherwise, so a corrupt cap can never squeeze the range shut.
 * This object does not decide the scaler's max — the engine
 * still sets that from the validated cap exactly as before.
 *
 * v4.7 verdict — the v4.6 server profile (peakBps / peakConns /
 * per-source bytes) deliberately does NOT feed back into this
 * decision. HostMemory is the ONE per-host learning system, and
 * its settled count already answers the only question asked
 * here — how many connections to open — because the scaler
 * reached it by whole-run marginal-gain verdicts (a grow is
 * kept only when the speed actually responds). The profile's
 * peak-per-connection figure is a weaker signal of a different
 * kind: peakBps and peakConns are independent maxima (of a
 * smoothed speed and of a worker count, accumulated across
 * every run of one task), so their ratio is not even one
 * moment's average, and turning a rate back into a count would
 * need a target speed nothing supplies. The profile is also
 * per-TASK display data, never aggregated or persisted per
 * host; feeding it back would require a second per-host store
 * with its own expiry / forgetting rules, running in parallel
 * to HostMemory's. Argued not done (audit round 32, step C).
 */
object StartPlan {

    /** v4.2: opening burst for a host we know nothing about. */
    const val BURST_START = 16

    data class Decision(val start: Int, val probing: Boolean)

    /**
     * @param hint remembered per-host optimum (0 = none). The
     *   engine passes 0 for resumes today (the v1.5 gate lives
     *   at the call site), but the hint branch below applies
     *   regardless of [resuming] so a future caller that passes
     *   a hint on a resume gets it honoured.
     * @param cap remembered rate-limit ceiling, raw (validated
     *   here into [bound]).
     * @param persistedThreads the count stored on the task
     *   record, used as a resume's restart count.
     */
    fun decide(
        hint: Int,
        cap: Int,
        resuming: Boolean,
        persistedThreads: Int,
    ): Decision {
        val bound = if (cap in HostMemory.MIN_CAP..DownloadEngine.MAX_THREADS) {
            cap
        } else {
            DownloadEngine.MAX_THREADS
        }
        // Remembered optimum: start AT it (pressed by the bound),
        // no probing — the v1.5 behaviour, unchanged.
        if (hint in 2..DownloadEngine.MAX_THREADS) {
            return Decision(start = minOf(hint, bound), probing = false)
        }
        // Resume: restart at the persisted count, bounded, but
        // keep probing on so the scaler re-judges it against
        // today's network.
        if (resuming) {
            val start = if (bound < 2) bound else persistedThreads.coerceIn(2, bound)
            return Decision(start = start, probing = true)
        }
        // Fresh start on an unknown host: burst in, then trim.
        return Decision(start = minOf(BURST_START, bound), probing = true)
    }
}
