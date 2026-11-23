package com.tuxundown.android

import com.tuxundown.android.core.TailRace
import com.tuxundown.android.core.raceFailureIsStale
import com.tuxundown.android.core.sourceIndexFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v7.8 B-fix (audit round 63): the two pure predicates behind the
 * B-1e stale-error guard and the raced-source pinning. (B-1b is a
 * lock-scope change in downloadRange's IO loop with no pure logic
 * to extract; its accounting invariants stay pinned by
 * TailRaceTest, unmodified.)
 */
class RaceFixTest {

    // ---- B-1e: stale race failure predicate ----

    @Test
    fun unracedFailureIsNeverStale() {
        // The pre-fix behaviour for every un-raced part: the catch
        // writes task.error + verdict at attempt >= 3.
        assertFalse(raceFailureIsStale(raceCancelled = false, ledger = null, raceId = -1))
    }

    @Test
    fun cancelledLoserFailureIsStale() {
        // cancelRaceLoser disconnected this racer: its exception is
        // the cancellation, not a failure of the task.
        val l = TailRace.Ledger(0, 100_000, 0, 100_000)
        assertTrue(raceFailureIsStale(raceCancelled = true, ledger = l, raceId = 1))
        assertTrue(raceFailureIsStale(raceCancelled = true, ledger = null, raceId = 1))
    }

    @Test
    fun partnerWonFailureIsStaleButWinnerAndOpenRaceAreNot() {
        val l = TailRace.Ledger(0, 100_000, 0, 100_000)
        // Race still open: a racer's failure is its own, recorded.
        l.advance(0, 50_000, 50_000)
        assertFalse(raceFailureIsStale(raceCancelled = false, ledger = l, raceId = 1))
        assertFalse(raceFailureIsStale(raceCancelled = false, ledger = l, raceId = 0))
        // Racer 1 wins; racer 0's repeated stall evictions after
        // that must not pollute the DONE task.
        l.advance(1, 100_000, 100_000)
        assertTrue(l.isWinner(1))
        assertTrue(raceFailureIsStale(raceCancelled = false, ledger = l, raceId = 0))
        // The winner itself is never "stale" by this predicate.
        assertFalse(raceFailureIsStale(raceCancelled = false, ledger = l, raceId = 1))
    }

    // ---- Theme 2 hardening: raced parts pin to one source ----

    @Test
    fun unracedRotationIsUnchanged() {
        // Pre-fix formula: srcs[(index + attempt - 1) mod size].
        for (size in 2..5) {
            for (index in 0..7) {
                for (attempt in 1..6) {
                    assertEquals(
                        (index + attempt - 1).mod(size),
                        sourceIndexFor(index, attempt, raced = false, sourceCount = size),
                    )
                }
            }
        }
    }

    @Test
    fun racedPartPinsToOneSourceForEveryAttempt() {
        for (size in 2..5) {
            for (index in 0..7) {
                val pinned = sourceIndexFor(index, 1, raced = true, sourceCount = size)
                assertEquals(index.mod(size), pinned)
                for (attempt in 2..6) {
                    assertEquals(pinned, sourceIndexFor(index, attempt, raced = true, sourceCount = size))
                }
                // Owner (raceId 0) and shadow racer (raceId 1) are
                // built over the same part index, so the pin makes
                // them land on the SAME source by construction.
            }
        }
    }

    @Test
    fun singleSourceAlwaysIndexZero() {
        assertEquals(0, sourceIndexFor(3, 1, raced = false, sourceCount = 1))
        assertEquals(0, sourceIndexFor(3, 3, raced = true, sourceCount = 1))
    }
}
