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
 */
class SpeedTest {

    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    /**
     * Download [url] with [connections] parallel connections for up
     * to [durationMs], sampling the live aggregate speed (EMA) into
     * [onSample] about twice a second. Returns the average bytes/s
     * over the phase (0 when nothing moved). Blocking — call from a
     * background thread.
     */
    fun runPhase(
        url: String,
        connections: Int,
        durationMs: Long,
        onSample: (Long) -> Unit,
    ): Long {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        val threads = (0 until connections).map {
            Thread {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 5_000
                    conn.setRequestProperty("User-Agent", DownloadEngine.DEFAULT_UA)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(128 * 1024)
                        var mine = 0L
                        while (!cancelled &&
                            System.currentTimeMillis() < deadline &&
                            mine < PER_CONNECTION_CAP
                        ) {
                            val n = input.read(buf)
                            if (n < 0) break
                            mine += n
                            total.addAndGet(n.toLong())
                        }
                    }
                } catch (e: Exception) {
                    // One dead connection just contributes nothing;
                    // if ALL of them die the phase reports 0.
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }.apply { isDaemon = true; start() }
        }
        var lastBytes = 0L
        var lastMs = start
        var live = 0L
        while (!cancelled && System.currentTimeMillis() < deadline &&
            threads.any { it.isAlive }
        ) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            val bytes = total.get()
            val dt = now - lastMs
            if (dt > 0) {
                live = SpeedTestMath.ema(live, (bytes - lastBytes) * 1000 / dt)
                onSample(live)
            }
            lastBytes = bytes
            lastMs = now
        }
        threads.forEach { runCatching { it.join(1_500) } }
        val elapsed = System.currentTimeMillis() - start
        return SpeedTestMath.avgBps(total.get(), elapsed)
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
