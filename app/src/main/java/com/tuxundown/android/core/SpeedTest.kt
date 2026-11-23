package com.tuxundown.android.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Pure math for the built-in speed test (unit-tested). */
object SpeedTestMath {

    /** Average throughput in bytes/s; 0 when the sample is meaningless. */
    fun avgBps(bytes: Long, elapsedMs: Long): Long =
        if (bytes <= 0 || elapsedMs <= 0) 0 else bytes * 1000 / elapsedMs

    /** Exponential moving average for the live readout (same 3:1 blend as the engine's sampler). */
    fun ema(prev: Long, sample: Long): Long =
        if (prev <= 0) sample else (prev * 3 + sample) / 4

    /**
     * The one window-selection rule shared by [steadyBps] and
     * [steadyWindowMs] (they must never disagree about which
     * window a result was computed over): drop samples with
     * t < [warmupMs]; if the kept samples number >= 2 and span
     * >= 1000 ms, that kept window is used; otherwise fall back
     * to the whole sample range. Returns (first, last) of the
     * chosen window, or null when there are fewer than 2 samples.
     */
    private fun chosenWindow(
        samples: List<Pair<Long, Long>>,
        warmupMs: Long,
    ): Pair<Pair<Long, Long>, Pair<Long, Long>>? {
        if (samples.size < 2) return null
        val kept = samples.filter { it.first >= warmupMs }
        if (kept.size >= 2 && kept.last().first - kept.first().first >= 1000) {
            return kept.first() to kept.last()
        }
        return samples.first() to samples.last()
    }

    /**
     * Steady-state throughput in bytes/s from (elapsedMs,
     * cumulativeBytes) samples in ascending time order: the
     * first [warmupMs] (connection setup + TCP slow-start ramp)
     * is discarded and speed is Δbytes/Δt across the kept
     * window. When the kept window is too short, falls back to
     * the whole-range average. 0 when there are < 2 samples,
     * the chosen span is <= 0, or bytes did not grow.
     */
    fun steadyBps(samples: List<Pair<Long, Long>>, warmupMs: Long): Long {
        val window = chosenWindow(samples, warmupMs) ?: return 0
        val (first, last) = window
        val dt = last.first - first.first
        val db = last.second - first.second
        if (dt <= 0 || db <= 0) return 0
        return db * 1000 / dt
    }

    /** Milliseconds spanned by the window [steadyBps] computes over. */
    fun steadyWindowMs(samples: List<Pair<Long, Long>>, warmupMs: Long): Long {
        val window = chosenWindow(samples, warmupMs) ?: return 0
        val span = window.second.first - window.first.first
        return if (span > 0) span else 0
    }
}

/** Three-way verdict for the single-vs-multi comparison (pure, unit-tested). */
object SpeedVerdict {

    enum class Verdict { FASTER, SIMILAR, SLOWER }

    /**
     * Compare with integer cross-multiplication (no floats):
     * multi >= single * 1.15 -> FASTER; multi < single * 0.85
     * -> SLOWER; otherwise SIMILAR. Null when either is <= 0.
     */
    fun verdict(singleBps: Long, multiBps: Long): Verdict? {
        if (singleBps <= 0 || multiBps <= 0) return null
        return when {
            multiBps * 100 >= singleBps * 115 -> Verdict.FASTER
            multiBps * 100 < singleBps * 85 -> Verdict.SLOWER
            else -> Verdict.SIMILAR
        }
    }
}

/**
 * Built-in network speed test (v1.8): measure THIS device's real
 * download throughput against a public test endpoint — first with
 * ONE connection, then with several in parallel, which is exactly
 * the single-vs-multi comparison this app exists for.
 *
 * Honesty rules, same as everywhere else in this app:
 *  - the result is the speed to THE TEST SERVER, over the current
 *    network; the UI says so, and never presents it as a promise
 *    about any particular file's server;
 *  - a phase that cannot move a single byte reports 0 and the UI
 *    falls back to the next endpoint instead of inventing a number.
 *
 * Data use is bounded: each phase runs at most [PHASE_MS] and each
 * connection stops after [PER_CONNECTION_CAP] bytes.
 *
 * Measurement口径 (v4.0): a phase's reported speed is a STEADY-STATE
 * rate, not a whole-phase average. The monitor loop records
 * (elapsedMs, cumulativeBytes) samples; the first [WARMUP_MS]
 * (connection setup — DNS/TCP/TLS — plus the TCP slow-start ramp,
 * during which few bytes flow) is discarded and the speed is
 * Δbytes/Δt across the remaining window (falling back to the
 * whole-phase average when that window is too short). Both the
 * single and the multi phase use this same口径, and the thread
 * joins after the phase no longer pollute the measurement.
 */
class SpeedTest {

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    /** Result of one phase: steady-state [bps], the window it was
     *  actually computed over ([windowMs]), and total [bytes]. */
    data class PhaseResult(val bps: Long, val windowMs: Long, val bytes: Long)

    /**
     * Download [url] with [connections] parallel connections for up
     * to [durationMs], sampling the live aggregate speed (EMA) into
     * [onSample] about twice a second. Returns a [PhaseResult]
     * whose bps is the steady-state rate (see the class comment;
     * 0 when nothing moved). Blocking — call from a background
     * thread.
     *
     * [totalCapBytes] (v4.2) additionally ends the phase once the
     * AGGREGATE bytes across all connections reach it — the
     * connection-count comparison passes its shared remaining
     * budget here so four phases together cannot exceed one hard
     * traffic cap. The default is unlimited, so existing callers
     * behave exactly as before.
     */
    fun runPhase(
        url: String,
        connections: Int,
        durationMs: Long,
        totalCapBytes: Long = Long.MAX_VALUE,
        onSample: (Long) -> Unit,
    ): PhaseResult {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        val threads = (0 until connections).map {
            Thread {
                var conn: HttpURLConnection? = null
                // v4.1 (step A): true only when the body ran to
                // EOF — use{} has then closed a fully consumed
                // stream and the socket belongs back in the
                // keep-alive pool. A deadline / cancel / cap exit
                // leaves bytes unread and keeps the disconnect.
                var eof = false
                try {
                    // v5.9 audit round 44: open through
                    // ProxyState like every other auxiliary
                    // path in the app (sniffer, SizeProbe,
                    // LinkPrecheck, HlsProbe, sidecar) — this
                    // was the one raw openConnection left, so
                    // with a proxy configured the test silently
                    // measured the DIRECT path (and a link only
                    // reachable through the proxy measured 0)
                    // while the screen calls the result the
                    // speed "under the current network". With
                    // no proxy configured ProxyState.open is
                    // exactly openConnection, so nothing else
                    // changes.
                    conn = ProxyState.open(URL(url))
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("User-Agent", DownloadEngine.DEFAULT_UA)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(128 * 1024)
                        var mine = 0L
                        while (!cancelled &&
                            System.currentTimeMillis() < deadline &&
                            mine < PER_CONNECTION_CAP &&
                            // v4.2 total cap: each worker checks
                            // the SHARED aggregate before every
                            // read, so the phase stops at (about)
                            // totalCapBytes. Approximate by design:
                            // workers already inside a read can
                            // overshoot by at most one buffer each.
                            total.get() < totalCapBytes
                        ) {
                            val n = input.read(buf)
                            if (n < 0) {
                                eof = true
                                break
                            }
                            mine += n
                            total.addAndGet(n.toLong())
                        }
                    }
                } catch (e: Exception) {
                    // One dead connection just contributes nothing;
                    // if ALL of them die the phase reports 0.
                } finally {
                    if (!eof) runCatching { conn?.disconnect() }
                }
            }.apply { isDaemon = true; start() }
        }
        var lastBytes = 0L
        var lastMs = start
        var live = 0L
        val samples = mutableListOf<Pair<Long, Long>>()
        samples.add(0L to 0L)
        while (!cancelled && System.currentTimeMillis() < deadline &&
            // v4.2: once the shared total cap is reached the phase
            // is over — exit and take the final sample instead of
            // idling until the deadline while workers wind down.
            total.get() < totalCapBytes &&
            threads.any { it.isAlive }
        ) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            val bytes = total.get()
            samples.add((now - start) to bytes)
            val dt = now - lastMs
            if (dt > 0) {
                live = SpeedTestMath.ema(live, (bytes - lastBytes) * 1000 / dt)
                onSample(live)
            }
            lastBytes = bytes
            lastMs = now
        }
        // Final sample BEFORE the joins, so join tail time never
        // enters the measurement.
        val finalNow = System.currentTimeMillis()
        samples.add((finalNow - start) to total.get())
        val bps = SpeedTestMath.steadyBps(samples, WARMUP_MS)
        val windowMs = SpeedTestMath.steadyWindowMs(samples, WARMUP_MS)
        val bytesTotal = total.get()
        threads.forEach { runCatching { it.join(1_500) } }
        return PhaseResult(bps, windowMs, bytesTotal)
    }

    data class Endpoint(val label: String, val url: String)

    companion object {
        /**
         * Public test endpoints, in try order. Cloudflare's speed
         * endpoint serves an exact byte count; the fallback is a
         * large file on the kernel.org mirror network (only the
         * first bytes are ever read — the phase caps see to that).
         */
        val ENDPOINTS = listOf(
            Endpoint(
                "Cloudflare 测速节点",
                "https://speed.cloudflare.com/__down?bytes=25000000",
            ),
            Endpoint(
                "kernel.org 镜像站（大文件，只取开头一段）",
                "https://mirrors.kernel.org/linuxmint/stable/22.2/" +
                    "linuxmint-22.2-cinnamon-64bit.iso",
            ),
            // v3.2: a third network for the multi-point
            // comparison (CacheFly's long-standing test file;
            // availability re-verified 2026-11-22 — 200 with
            // Accept-Ranges, real bytes flow).
            Endpoint(
                "CacheFly 测速文件（大文件，只取开头一段）",
                "https://cachefly.cachefly.net/100mb.test",
            ),
        )

        /** Each phase (single, then multi) runs at most this long. */
        const val PHASE_MS = 6_000L

        /** Leading ramp (setup + slow-start) discarded from the steady-state window. */
        const val WARMUP_MS = 1500L

        /** Per-connection byte ceiling, so a fast network cannot burn unbounded data. */
        const val PER_CONNECTION_CAP = 25L * 1024 * 1024
    }
}

/**
 * v3.2: multi-point comparison — run the same single-vs-multi
 * pair against EVERY endpoint in turn and put the results side
 * by side.
 *
 * The honest framing, stated on the screen itself: the points
 * sit on different networks (a global anycast test service, a
 * mirror network, a CDN test file), reached over different
 * routes, so a point's absolute number says little about that
 * server's worth — the meaningful comparison is single-vs-multi
 * WITHIN one point, and the rough size of the numbers across
 * points. Nothing here ranks the points.
 *
 * Pure math — unit-tested (SpeedCompareTest).
 */
object SpeedCompare {

    data class PointResult(
        val label: String,
        val singleBps: Long,
        val multiBps: Long,
    ) {
        /** A point that moved nothing in either phase. */
        val reached: Boolean get() = singleBps > 0 || multiBps > 0
    }

    /** Comparison phases run a touch shorter than the quick
     *  test's — three points back to back should stay near
     *  half a minute in total. */
    const val PHASE_MS = 5_000L

    /** Connections in each point's multi phase. */
    const val MULTI_CONNECTIONS = 4

    /** Rough wall-clock of a full comparison, in seconds. */
    fun totalSeconds(pointCount: Int): Int =
        (pointCount * 2 * PHASE_MS / 1000).toInt()

    /**
     * Data-use band (MB) for the confirmation dialog, scaled
     * from the quick test's own stated band (10–100 MB per
     * point: duration-bound phases, faster network = more
     * data). An estimate for consent, not a promise — the
     * per-connection caps are the hard bound underneath.
     */
    fun dataBandMB(pointCount: Int): IntRange =
        (pointCount * 10)..(pointCount * 100)

    /**
     * Bar lengths for the comparison chart: (single, multi) per
     * point as fractions of the fastest value across all points,
     * so every bar shares one scale. Unreached points and an
     * all-zero run produce zero bars, never NaN.
     */
    fun barFractions(results: List<PointResult>): List<Pair<Float, Float>> {
        val max = results.maxOfOrNull { maxOf(it.singleBps, it.multiBps) } ?: 0
        if (max <= 0) return results.map { 0f to 0f }
        return results.map {
            (it.singleBps.toFloat() / max) to (it.multiBps.toFloat() / max)
        }
    }
}
