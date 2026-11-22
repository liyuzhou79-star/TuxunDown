package com.tuxundown.android

import com.tuxundown.android.core.Endgame
import com.tuxundown.android.core.HostMemory
import com.tuxundown.android.core.RateLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round 19 (v3.3) — COMBINATION tests. Every mechanism
 * here was already unit-tested alone (ThreadScaler, Endgame,
 * BandwidthBudget, SpeedSchedule, RateLimit, HostMemory);
 * these pin what happens when two of them meet:
 *
 * - scaler shrink x endgame tail queue (the worker-exit rule);
 * - rate-limit evidence -> HostMemory ceiling -> failure
 *   forget (the ceiling must survive the forget that erases
 *   the thread optimum it shares an entry with).
 */
class CombinationAuditTest {

    private val now = 1_700_000_000_000L

    // ---- scaler x endgame: the surplus-exit rule ----

    @Test
    fun surplusWorkerExitsOnlyWhenNoTailWaits() {
        // More workers than the target, nothing cut: surplus.
        assertTrue(Endgame.shouldExitSurplus(activeWorkers = 8, target = 4, tailsPending = 0))
        // Same surplus, but cut tails are queued: NOT surplus —
        // the worker drains a tail instead of leaving.
        assertFalse(Endgame.shouldExitSurplus(activeWorkers = 8, target = 4, tailsPending = 2))
        // At or below target a worker is never surplus, tails
        // or not.
        assertFalse(Endgame.shouldExitSurplus(activeWorkers = 4, target = 4, tailsPending = 0))
        assertFalse(Endgame.shouldExitSurplus(activeWorkers = 2, target = 4, tailsPending = 0))
        assertFalse(Endgame.shouldExitSurplus(activeWorkers = 2, target = 4, tailsPending = 3))
    }

    // ---- rate-limit chain: evidence -> ceiling -> forget ----

    @Test
    fun learnedCeilingSurvivesTheFailureForget() {
        // The host 429s at 32 connections: evidence convicts,
        // the ceiling learned is half of that (16).
        val events = listOf(RateLimit.Rejection(429, now, 32))
        val atLimit = RateLimit.assess(events)
        assertEquals(32, atLimit)
        var entries = HostMemory.recordRateLimit(emptyList(), "a.com", atLimit, now)
        assertEquals(16, HostMemory.capOf(entries, "a.com", now + 1000))
        // A later run settles an optimum BELOW the ceiling;
        // that must not relax the ceiling (it proves nothing
        // about it).
        entries = HostMemory.recordSuccess(entries, "a.com", 8, now)
        assertEquals(16, HostMemory.capOf(entries, "a.com", now + 1000))
        assertEquals(8, HostMemory.lookup(entries, "a.com", now + 1000))
        // Two ordinary failures (dead network, vanished file):
        // the OPTIMUM is forgotten and re-probing happens…
        entries = HostMemory.recordFailure(entries, "a.com", now)
        entries = HostMemory.recordFailure(entries, "a.com", now)
        assertEquals(0, HostMemory.lookup(entries, "a.com", now + 1000))
        // …but the CEILING survives, so the re-probe stays
        // bounded instead of storming back to the full count.
        assertEquals(16, HostMemory.capOf(entries, "a.com", now + 1000))
        // And it still expires on the usual TTL.
        assertEquals(0, HostMemory.capOf(entries, "a.com", now + HostMemory.TTL_MS + 1))
    }

    @Test
    fun failureForgetWithoutCeilingStillDeletesTheEntry() {
        // The pre-v3.3 behaviour is unchanged for hosts that
        // never rate-limited: two failures, entry gone.
        var entries = HostMemory.recordSuccess(emptyList(), "b.com", 16, now)
        entries = HostMemory.recordFailure(entries, "b.com", now)
        entries = HostMemory.recordFailure(entries, "b.com", now)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun ceilingRelaxesAgainAfterSurvivingAForget() {
        // Cap-only entry left by a forget; a run that then
        // completes AT the ceiling earns the usual +4 relax —
        // the surviving ceiling is a live one, not a fossil.
        var entries = HostMemory.recordRateLimit(emptyList(), "c.com", 24, now)
        assertEquals(12, HostMemory.capOf(entries, "c.com", now + 1000))
        entries = HostMemory.recordFailure(entries, "c.com", now)
        entries = HostMemory.recordFailure(entries, "c.com", now)
        assertEquals(12, HostMemory.capOf(entries, "c.com", now + 1000))
        entries = HostMemory.recordSuccess(entries, "c.com", 12, now)
        assertEquals(16, HostMemory.capOf(entries, "c.com", now + 1000))
    }
}
