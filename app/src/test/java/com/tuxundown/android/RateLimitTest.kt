package com.tuxundown.android

import com.tuxundown.android.core.RateLimit
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitTest {

    private fun r(code: Int, atMs: Long, threads: Int) =
        RateLimit.Rejection(code, atMs, threads)

    @Test
    fun emptyAndUnrelatedEvidenceIsNotRateLimiting() {
        assertEquals(0, RateLimit.assess(emptyList()))
        assertEquals(0, RateLimit.assess(listOf(r(404, 0, 32), r(500, 1, 32))))
    }

    @Test
    fun aSingle429UnderLoadConvictsAtThatLoad() {
        assertEquals(16, RateLimit.assess(listOf(r(429, 5_000, 16))))
        // The highest rejected load is the level to remember.
        assertEquals(
            32,
            RateLimit.assess(listOf(r(429, 0, 8), r(200, 1, 32), r(429, 2, 32))),
        )
    }

    @Test
    fun lone403sNeverConvict() {
        // One 403 at full threads: a permission story.
        assertEquals(0, RateLimit.assess(listOf(r(403, 0, 32))))
        // Two together: still not the pattern.
        assertEquals(0, RateLimit.assess(listOf(r(403, 0, 16), r(403, 500, 16))))
        // Three, but each at low parallelism (probe-like): no.
        assertEquals(
            0,
            RateLimit.assess(listOf(r(403, 0, 1), r(403, 100, 2), r(403, 200, 1))),
        )
        // Three at load but spread over a minute: a slow drip of
        // refusals is not a burst — no conviction.
        assertEquals(
            0,
            RateLimit.assess(
                listOf(r(403, 0, 16), r(403, 30_000, 16), r(403, 60_000, 16)),
            ),
        )
    }

    @Test
    fun aBurstOfParallel403sConvicts() {
        val events = listOf(r(403, 1_000, 16), r(403, 2_000, 16), r(403, 3_500, 16))
        assertEquals(16, RateLimit.assess(events))
        // Unordered input still convicts (events are sorted first).
        assertEquals(16, RateLimit.assess(events.reversed()))
        // A burst riding a ramp reports the highest load in it.
        val ramp = listOf(r(403, 0, 8), r(403, 1_000, 16), r(403, 2_000, 32))
        assertEquals(32, RateLimit.assess(ramp))
    }

    @Test
    fun windowEdgeIsInclusive() {
        // Exactly WINDOW_MS apart still counts as one burst.
        val events = listOf(
            r(403, 0, 8), r(403, 5_000, 8), r(403, RateLimit.WINDOW_MS, 8),
        )
        assertEquals(8, RateLimit.assess(events))
    }
}
