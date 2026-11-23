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
 * v7.7 (Step B3, user ruling "直接进入最好的状态最好，能改就改"):
 * the fresh-host burst rises 16 -> 32. This REVISES the Step B2
 * adjudication, which had kept the burst at 16 — but B2's scope
 * was explicitly "only raise the cap", and its reasoning (the
 * ramp's step is defined by the target, so the slope scales on
 * its own) is untouched: GrowthRamp's STEP_FLOOR / STEP_DIVISOR
 * do not change here either. What changes is only the opening
 * width itself, on the user's explicit speed-first ruling: from
 * 32 the ladder needs two doublings to the 128 top instead of
 * three, and the ramp needs three adjudications instead of
 * four. The trade-off is stated plainly: an unknown host now
 * gets 32 simultaneous opening connections before any evidence
 * exists — the goodwill cost B2's caution avoided. Known-bad
 * hosts stay bounded by the remembered rate-limit cap (which
 * presses EVERY branch below), the global budget still bounds
 * the cross-task total, and the scaler still trims at the
 * first plateau, so the exposure is the opening seconds only.
 *
 * v7.7 (Step B3) also closes the resume gap: the engine now
 * passes the remembered hint for resumes too, and the resume
 * branch uses it as a FLOOR (start at the larger of the
 * persisted count and the hint, pressed by the bound). Another
 * task from the same host may have settled a better count since
 * this task was paused; restarting below it would re-pay the
 * climb for no reason. Probing stays ON for resumes (today's
 * network is still re-judged) and the cap still presses the
 * result — the hint never lifts a start past the cap.
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

    /** v4.2: opening burst for a host we know nothing about.
     *  v7.7 (Step B3): raised 16 -> 32 on the user's speed-first
     *  ruling (see the class doc for the B2 relationship). */
    const val BURST_START = 32

    /**
     * v7.8 (audit round 63, Step B2): the A/B switch for the
     * v7.7 fast-open shape, prepared after the user's
     * same-link A/B report (v7.6.0 averaged 31.1 MB/s on a
     * kernel.org mirror; v7.7.0 ran the SAME link at
     * 2.7 MB/s with all 64 connections present but each
     * crawling at 39-53 KB/s). The prime code-level suspect
     * is the instant full-width open: a remembered host now
     * opens its whole learned count at second 0 (GrowthRamp
     * baseGrant, Step B3) where v7.6 granted 16 and climbed
     * over ~6 s — exactly the burst shape per-IP fairness /
     * penalty systems punish, and the silent-throttle shape
     * the rate-limit memory cannot learn (it learns only
     * from 429 / clustered-403 rejections on FAILED runs;
     * a host that merely slows every connection teaches it
     * nothing, and the remembered optimum re-arms the burst
     * on every later run).
     *
     * TRUE (the default) keeps the shipped v7.7 behaviour
     * exactly: burst 32, remembered optimum opens full width.
     * FALSE selects the conservative form for a real-device
     * A/B: fresh hosts burst at [BURST_START_LEGACY] (the
     * v4.2-v7.6 width) and a remembered optimum is granted
     * only the burst width up front, reaching full width
     * through the ordinary ramp steps (max(8, desired/4)
     * per adjudication — 32 -> 48 -> 64 for a learned 64,
     * ~2 adjudications, a few seconds, never instant).
     * Flipping the default waits on the user's A/B result;
     * this constant is the ONLY thing that changes.
     */
    const val FULL_WIDTH_OPEN = true

    /** The pre-v7.7 fresh-host burst, used when
     *  [FULL_WIDTH_OPEN] is off (see its doc). */
    const val BURST_START_LEGACY = 16

    data class Decision(val start: Int, val probing: Boolean)

    /**
     * @param hint remembered per-host optimum (0 = none). A
     *   FRESH start begins AT the hint with no probing (the
     *   v1.5 branch below); a RESUME uses the hint only as a
     *   floor under its persisted restart count and keeps
     *   probing (v7.7 Step B3 — see the class doc).
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
        fullWidthOpen: Boolean = FULL_WIDTH_OPEN,
    ): Decision {
        val bound = if (cap in HostMemory.MIN_CAP..DownloadEngine.MAX_THREADS) {
            cap
        } else {
            DownloadEngine.MAX_THREADS
        }
        // Remembered optimum, FRESH start: begin AT it (pressed
        // by the bound), no probing — the v1.5 behaviour,
        // unchanged. A resume deliberately does NOT take this
        // branch (v7.7 Step B3): it uses the hint as a floor
        // below and keeps probing. (Pre-B3 this branch ignored
        // `resuming`, which is why the old engine gate passed
        // hint = 0 for resumes; the gate is gone and the branch
        // order now carries the distinction.)
        if (!resuming && hint in 2..DownloadEngine.MAX_THREADS) {
            return Decision(start = minOf(hint, bound), probing = false)
        }
        // Resume: restart at the persisted count, bounded, but
        // keep probing on so the scaler re-judges it against
        // today's network. v7.7 (Step B3): the remembered hint
        // is a FLOOR here — a better count another run of this
        // host settled since is not restarted below — and the
        // bound (the rate-limit cap) still presses the result.
        if (resuming) {
            val hintFloor = if (hint in 2..DownloadEngine.MAX_THREADS) hint else 0
            val base = maxOf(persistedThreads, hintFloor)
            val start = if (bound < 2) bound else base.coerceIn(2, bound)
            return Decision(start = start, probing = true)
        }
        // Fresh start on an unknown host: burst in, then trim.
        // v7.8 (Step B2): with the A/B switch off the burst is
        // the legacy 16 (see FULL_WIDTH_OPEN); the hint and
        // resume branches above are identical in both forms —
        // the conservative form differs only in how FAST the
        // count is granted (GrowthRamp.baseGrantFor), not in
        // the target itself.
        val burst = if (fullWidthOpen) BURST_START else BURST_START_LEGACY
        return Decision(start = minOf(burst, bound), probing = true)
    }
}
