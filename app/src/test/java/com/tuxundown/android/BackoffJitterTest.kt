package com.tuxundown.android

import com.tuxundown.android.core.BackoffJitter
import com.tuxundown.android.core.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * v5.8: BackoffJitter — the spread applied to every retry
 * backoff (whole-task, HTTP chunk, HLS segment, FTP chunk) so
 * failures that happened together do not retry together.
 * The declared shape is "equal jitter": uniform in
 * [base / 2, base] — never above the base (no cap can break),
 * never below half of it (no instant re-hit, no zero wait).
 */
class BackoffJitterTest {

    @Test
    fun endpointsAreHalfAndFullBase() {
        assertEquals(500L, BackoffJitter.apply(1_000L) { 0.0 })
        assertEquals(750L, BackoffJitter.apply(1_000L) { 0.5 })
        assertEquals(1_000L, BackoffJitter.apply(1_000L) { 1.0 })
        // The chunk-level bases used by the engine.
        assertEquals(200L, BackoffJitter.apply(400L) { 0.0 })
        assertEquals(250L, BackoffJitter.apply(500L) { 0.0 })
    }

    @Test
    fun sameSeedReproducesSameSequence() {
        val bases = listOf(500L, 1_000L, 10_000L, 400L, 180_000L, 500L)
        val a = Random(42)
        val b = Random(42)
        val seqA = bases.map { BackoffJitter.apply(it, a) }
        val seqB = bases.map { BackoffJitter.apply(it, b) }
        assertEquals(seqA, seqB)
        // And a lambda source is just as reproducible.
        assertEquals(
            BackoffJitter.apply(10_000L) { 0.25 },
            BackoffJitter.apply(10_000L) { 0.25 },
        )
    }

    @Test
    fun jitterStaysInsideDeclaredBounds() {
        val bases = listOf(1L, 10L, 50L, 400L, 500L, 1_500L, 10_000L, 90_000L, 180_000L)
        for (seed in 0L until 100L) {
            val rnd = Random(seed)
            for (base in bases) {
                val v = BackoffJitter.apply(base, rnd)
                assertTrue("seed=$seed base=$base got $v", v <= base)
                assertTrue("seed=$seed base=$base got $v", v >= base / 2)
                assertTrue("seed=$seed base=$base got $v", v >= minOf(BackoffJitter.MIN_WAIT_MS, base))
                assertTrue("seed=$seed base=$base got $v", v > 0)
            }
        }
    }

    @Test
    fun capNeverBreaksForLargeAttemptsAndBases() {
        // Whole-task: every used count, including far past the cap.
        for (used in 0..20) {
            val cap = RetryPolicy.backoffMs(used)
            val rnd = Random(used * 31L + 7)
            repeat(20) {
                val v = RetryPolicy.backoffMsJittered(used) { rnd.nextDouble() }
                assertTrue("used=$used got $v over cap $cap", v <= cap)
                assertTrue("used=$used got $v under half $cap", v >= cap / 2)
            }
        }
        // Chunk-style bases scaled by a (hypothetically) huge attempt.
        val rnd = Random(1)
        repeat(50) {
            assertTrue(BackoffJitter.apply(500L * 1_000, rnd) <= 500_000L)
        }
    }

    @Test
    fun worstCaseTaskTotalIsUnchanged() {
        // Four retries (used 0..3) waited at most 10+30+90+180 s
        // before jitter; jitter only shortens, so that bound and
        // the half-of-it floor both hold for every seed.
        for (seed in 0L until 50L) {
            val rnd = Random(seed)
            val total = (0..3).sumOf {
                RetryPolicy.backoffMsJittered(it) { rnd.nextDouble() }
            }
            assertTrue("seed=$seed total $total", total <= 310_000L)
            assertTrue("seed=$seed total $total", total >= 155_000L)
        }
    }

    @Test
    fun zeroAndNegativeBasesWaitZero() {
        assertEquals(0L, BackoffJitter.apply(0L) { 0.0 })
        assertEquals(0L, BackoffJitter.apply(0L) { 1.0 })
        assertEquals(0L, BackoffJitter.apply(-100L) { 0.5 })
        // A negative used count falls back to the first backoff.
        val v = RetryPolicy.backoffMsJittered(-3) { 0.5 }
        assertTrue(v in 5_000L..10_000L)
    }

    @Test
    fun tinyBasesNeverJitterToZero() {
        for (base in 1L..100L) {
            assertTrue(BackoffJitter.apply(base) { 0.0 } > 0)
            assertTrue(BackoffJitter.apply(base) { 0.0 } >= minOf(BackoffJitter.MIN_WAIT_MS, base))
        }
    }

    @Test
    fun outOfRangeRandomIsClamped() {
        assertEquals(1_000L, BackoffJitter.apply(1_000L) { 1.5 })
        assertEquals(500L, BackoffJitter.apply(1_000L) { -1.0 })
        assertEquals(500L, BackoffJitter.apply(1_000L) { Double.NaN })
    }

    @Test
    fun jitterActuallySpreads() {
        val rnd = Random(7)
        val draws = (1..20).map { BackoffJitter.apply(10_000L, rnd) }
        assertTrue(draws.all { it in 5_000L..10_000L })
        assertTrue("draws not spread: $draws", draws.toSet().size >= 10)
    }
}
