package com.tuxundown.android.core

/**
 * v8.1: the FULL-RUN speed curve's pure logic — a second,
 * much coarser series next to the 90-second window in
 * [TaskRecord.speedHistory]. That window answers "how fast
 * right now"; this one answers "how did the whole download
 * go" and must survive the task finishing, so it lives on
 * the record and rides the shared serializer like
 * speedHistory does (disk + backup, one caliber).
 *
 * Sampling caliber (pinned by FullSpeedHistoryTest):
 * - One point every [BASE_INTERVAL_SEC] seconds of ACTIVE
 *   transfer, holding the task's smoothed total speed (the
 *   EMA value in TaskRecord.speedBps — the same number the
 *   card shows; no second measurement is invented here).
 *   The engine's 1-second sampler is the only clock: paused
 *   or parked tasks have no sampler ticks, so they record
 *   nothing, and a finished/failed task stops growing.
 * - At most [MAX_POINTS] points are kept: 720 x 5 s = one
 *   hour at full resolution. The point that would exceed
 *   the cap first DECIMATES the series — adjacent pairs are
 *   averaged, halving the count — and the sampling interval
 *   doubles with it (5 s -> 10 s -> 20 s ...), so the list
 *   length is bounded forever while the covered time span
 *   keeps growing. A decimated point is the mean of the two
 *   real samples it replaces; no value is ever fabricated.
 *
 * Pure — unit-tested. The engine owns the tick counting and
 * the record mutation; this object owns the arithmetic.
 */
object FullSpeedHistory {

    /** Hard cap on retained points — the list NEVER grows past this. */
    const val MAX_POINTS = 720

    /** Seconds of transfer between two points before any decimation. */
    const val BASE_INTERVAL_SEC = 5

    /** True when [ticksSinceSample] 1-second sampler ticks at
     *  the current [intervalSec] spacing complete one point. */
    fun shouldSample(ticksSinceSample: Int, intervalSec: Int): Boolean =
        ticksSinceSample >= sanitizeInterval(intervalSec)

    /**
     * Append one sample of [bps] to [points] sampled every
     * [intervalSec] seconds. Returns the new points and the
     * interval to use from now on: unchanged while there is
     * room; when the series is full, it is decimated first
     * (see the class doc) and the interval doubles. The
     * result NEVER exceeds [MAX_POINTS].
     */
    fun append(
        points: List<Long>,
        intervalSec: Int,
        bps: Long,
    ): Pair<List<Long>, Int> {
        var pts = points
        var iv = sanitizeInterval(intervalSec)
        while (pts.size >= MAX_POINTS) {
            pts = decimate(pts)
            iv *= 2
        }
        return (pts + bps.coerceAtLeast(0)) to iv
    }

    /**
     * Halve a series by averaging adjacent pairs: points
     * (0,1), (2,3), ... each become their mean, so every
     * surviving point covers twice the time span. An odd
     * trailing point has no partner and carries over as-is.
     */
    fun decimate(points: List<Long>): List<Long> {
        if (points.size < 2) return points
        val out = ArrayList<Long>((points.size + 1) / 2)
        var i = 0
        while (i + 1 < points.size) {
            out.add((points[i] + points[i + 1]) / 2)
            i += 2
        }
        if (i < points.size) out.add(points[i])
        return out
    }

    /**
     * The only legal intervals are [BASE_INTERVAL_SEC] x 2^k
     * (5, 10, 20, ...): anything else — a missing key's 0, a
     * corrupt or hostile value from an imported backup —
     * falls back to the base interval rather than reaching
     * the sampler math half-trusted.
     */
    fun sanitizeInterval(v: Int): Int {
        if (v < BASE_INTERVAL_SEC || v % BASE_INTERVAL_SEC != 0) {
            return BASE_INTERVAL_SEC
        }
        val ratio = v / BASE_INTERVAL_SEC
        return if (ratio and (ratio - 1) == 0) v else BASE_INTERVAL_SEC
    }

    /** Seconds of transfer a series of [pointCount] points at
     *  [intervalSec] spacing covers — the honest x-axis span
     *  the detail page prints under the full-run chart. */
    fun spanSeconds(pointCount: Int, intervalSec: Int): Long =
        pointCount.toLong() * sanitizeInterval(intervalSec)
}
