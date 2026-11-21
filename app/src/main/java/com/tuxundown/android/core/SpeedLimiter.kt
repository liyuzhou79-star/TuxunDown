package com.tuxundown.android.core

/**
 * Token-bucket speed limiter for the user-facing "全局限速" setting.
 *
 * Workers call [throttle] after writing each buffer; the call sleeps
 * just long enough that the long-run average stays at or below the
 * configured rate. A rate <= 0 means unlimited (no-op). The bucket
 * allows a burst of up to one second's worth of bytes so small reads
 * are not chopped into useless micro-sleeps.
 *
 * Clock and sleeper are injectable so the accounting is unit-testable
 * without real waiting.
 */
class SpeedLimiter(
    private val rateBps: () -> Long,
    private val clock: () -> Long = { System.nanoTime() },
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) {
    private var nextFreeNanos = 0L

    @Synchronized
    fun throttle(bytes: Int) {
        val rate = rateBps()
        if (rate <= 0 || bytes <= 0) return
        val now = clock()
        // Burst allowance: never let the schedule run more than 1 s
        // ahead of the clock (i.e. at most 1 s of bytes banked).
        val floor = now - 1_000_000_000L
        if (nextFreeNanos < floor) nextFreeNanos = floor
        val cost = bytes.toLong() * 1_000_000_000L / rate
        val start = maxOf(now, nextFreeNanos)
        nextFreeNanos = start + cost
        val waitMs = (nextFreeNanos - now) / 1_000_000L
        if (waitMs > 0) {
            runCatching { sleep(waitMs) }
        }
    }
}
