package com.tuxundown.android.core

/**
 * v7.5 (audit round 60, Step C): THE speed smoothing caliber
 * for the whole app — the one and only place the EMA formula
 * lives.
 *
 * Every speed number a surface can show derives from a value
 * produced here: the five engine sampler paths (segmented /
 * HLS / FTP single-stream / FTP segmented / HTTP single
 * stream) smooth their per-tick raw byte rate through [next]
 * into TaskRecord.speedBps — the value the task card, the
 * summary card, the notifications, the speed chart and both
 * ETA derivations (TaskRecord.etaSeconds and the aggregate
 * WidgetState.etaSeconds) all read — and the per-connection
 * rows (ConnInfo.speedBps) smooth their own per-tick deltas
 * through the same [next], each connection keeping its own
 * previous value, so the connection rows and the card's big
 * number are finally the same kind of number and can be
 * compared (before v7.5 the rows were single-tick
 * instantaneous deltas while the headline was this EMA, and
 * the two visibly failed to add up).
 *
 * The formula itself is unchanged from the pre-v7.5 inline
 * copies, which were byte-identical in all five paths:
 * the first tick (no previous value yet, prev <= 0) takes
 * the raw rate as-is; every later tick is
 * (prev * 3 + raw) / 4 — a 1-second-tick EMA that weights
 * the newest sample at 1/4. Task-level values are therefore
 * exactly what they were before this extraction; only the
 * formula's ADDRESS changed. Do not "improve" the weights
 * here without re-auditing every surface that reads the
 * result — this helper is a caliber, not a tuning knob.
 *
 * Pure, unit-tested.
 */
object SpeedSmoothing {

    /**
     * One smoothing step: [prev] is the caller's previous
     * smoothed value (0 = none yet), [raw] is this tick's
     * measured rate in bytes/second. Returns the new
     * smoothed value.
     */
    fun next(prev: Long, raw: Long): Long =
        if (prev <= 0L) raw else (prev * 3 + raw) / 4
}
