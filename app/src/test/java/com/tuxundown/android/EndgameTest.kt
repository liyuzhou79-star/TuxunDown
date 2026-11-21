package com.tuxundown.android

import com.tuxundown.android.core.Endgame
import org.junit.Assert.assertEquals
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
