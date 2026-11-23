package com.tuxundown.android

import com.tuxundown.android.core.DownloadEngine
import com.tuxundown.android.core.Endgame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndgameTest {

    private val minSplit = 256L * 1024

    private fun range(index: Int, pos: Long, end: Long) = Endgame.Range(index, pos, end)

    @Test
    fun noRangesNoSplit() {
        assertNull(Endgame.planSplit(emptyList(), 3, minSplit))
    }

    @Test
    fun nobodyIdleNoSplit() {
        assertNull(Endgame.planSplit(listOf(range(0, 0, 10_000_000)), 0, minSplit))
    }

    @Test
    fun tooSmallRemainderNoSplit() {
        // Exactly at the threshold and just above it but not
        // divisible into two >= minSplit/2 pieces... just above
        // the threshold DOES split; at/below it never does.
        assertNull(Endgame.planSplit(listOf(range(0, 0, minSplit)), 4, minSplit))
        assertNull(Endgame.planSplit(listOf(range(0, 0, minSplit - 1)), 4, minSplit))
    }

    @Test
    fun picksLargestRemainder() {
        val split = Endgame.planSplit(
            listOf(
                range(0, 500_000, 900_000),          // 400 KB left
                range(1, 0, 8_000_000),              // 8 MB left — the drag
                range(2, 7_000_000, 7_500_000),      // 500 KB left
            ),
            idleWorkers = 1,
            minSplit = minSplit,
        )!!
        assertEquals(1, split.index)
        // One idle worker -> two pieces -> one cut, at the midpoint.
        assertEquals(listOf(4_000_000L), split.cuts)
    }

    @Test
    fun moreIdleWorkersMorePieces() {
        val split = Endgame.planSplit(
            listOf(range(3, 1_000_000, 9_000_000)), // 8 MB left
            idleWorkers = 3,
            minSplit = minSplit,
        )!!
        assertEquals(3, split.index)
        // 3 idle + owner = 4 pieces of 2 MB.
        assertEquals(listOf(3_000_000L, 5_000_000L, 7_000_000L), split.cuts)
    }

    @Test
    fun piecesNeverBelowHalfMinSplit() {
        // 300 KB remainder with minSplit 256 KB: two pieces would
        // be 150 KB each (>= 128 KB floor) so it splits once; ask
        // with 8 idle workers and it must still stop at 2 pieces.
        val split = Endgame.planSplit(
            listOf(range(0, 0, 300_000)),
            idleWorkers = 8,
            minSplit = minSplit,
        )!!
        assertEquals(1, split.cuts.size)
        assertEquals(150_000L, split.cuts[0])
    }

    @Test
    fun cutsAreStrictlyInsideAndAscending() {
        val split = Endgame.planSplit(
            listOf(range(0, 123_456, 9_876_543)),
            idleWorkers = 5,
            minSplit = minSplit,
        )!!
        assertTrue(split.cuts.all { it > 123_456 && it < 9_876_543 })
        assertTrue(split.cuts.zipWithNext().all { (a, b) -> b > a })
        // Pieces tile the remainder exactly: first cut - pos +
        // interior spans + end - last cut == remaining.
        val bounds = listOf(123_456L) + split.cuts + listOf(9_876_543L)
        var sum = 0L
        for (i in 0 until bounds.size - 1) sum += bounds[i + 1] - bounds[i]
        assertEquals(9_876_543L - 123_456L, sum)
    }

    // ---- v7.7 (Step B3): the early endgame-zone trigger ----

    @Test
    fun earlySplitZoneOpensWhenUnclaimedDropsBelowTheFleet() {
        // The pre-B3 trigger was unclaimed == 0 only; the zone
        // now opens as soon as fresh chunks cannot keep every
        // live worker busy.
        assertTrue(Endgame.shouldAttemptSplit(0, 8))
        assertTrue(Endgame.shouldAttemptSplit(7, 8))
        assertTrue(Endgame.shouldAttemptSplit(1, 2))
        // At/above the fleet size every worker still has fresh
        // work: no splitting.
        assertFalse(Endgame.shouldAttemptSplit(8, 8))
        assertFalse(Endgame.shouldAttemptSplit(100, 8))
        // No live workers: nobody to hand a piece to.
        assertFalse(Endgame.shouldAttemptSplit(0, 0))
        assertFalse(Endgame.shouldAttemptSplit(0, -1))
    }

    // ---- v7.7 (Step B3): finer tail granularity ----

    @Test
    fun engineMinSplitIs128KbAndSplitsWhat256KbCouldNot() {
        assertEquals(128L * 1024, DownloadEngine.MIN_SPLIT)
        val finer = DownloadEngine.MIN_SPLIT
        // A 200 KB remainder: below the old 256 KB floor (no
        // split then), above the new one — it splits, and its
        // two 100 KB pieces respect the new 64 KB piece floor.
        assertNull(Endgame.planSplit(listOf(range(0, 0, 200_000)), 1, minSplit))
        val split = Endgame.planSplit(listOf(range(0, 0, 200_000)), 1, finer)!!
        assertEquals(listOf(100_000L), split.cuts)
        // At/below the new floor nothing splits, as before.
        assertNull(Endgame.planSplit(listOf(range(0, 0, finer)), 4, finer))
        assertNull(Endgame.planSplit(listOf(range(0, 0, finer - 1)), 4, finer))
        // Pieces never go below half the floor (64 KB): a
        // 150 KB remainder with many idle workers still cuts
        // into just two pieces of 75 KB.
        val capped = Endgame.planSplit(listOf(range(0, 0, 150_000)), 8, finer)!!
        assertEquals(1, capped.cuts.size)
        assertEquals(75_000L, capped.cuts[0])
    }

    @Test
    fun alreadyFinishedRangesAreIgnored() {
        // A range with nothing left (pos == end) must not win
        // "largest" over a real remainder.
        val split = Endgame.planSplit(
            listOf(
                range(0, 5_000_000, 5_000_000),
                range(1, 0, 2_000_000),
            ),
            idleWorkers = 1,
            minSplit = minSplit,
        )!!
        assertEquals(1, split.index)
    }
}
