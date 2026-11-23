package com.tuxundown.android

import com.tuxundown.android.core.ChunkPlanner
import com.tuxundown.android.core.TailRace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TailRaceTest {

    private fun ledger(start: Long = 1000, end: Long = 101_000) =
        TailRace.Ledger(start, end, ownerStart = 0, ownerEnd = end)

    // Invariant 1+2: a raced span is credited once, at max, and
    // never beyond its length — checked after every advance of a
    // randomised two-racer simulation against the model.
    @Test
    fun creditedIsAlwaysMaxAndNeverExceedsSpan() {
        val rnd = Random(42)
        repeat(50) {
            val start = rnd.nextLong(0, 10_000)
            val len = rnd.nextLong(1, 500_000)
            val l = TailRace.Ledger(start, start + len, 0, start + len)
            var pos0 = start
            var pos1 = start
            var creditedSum = 0L
            var dupSum = 0L
            var fetchedSum = 0L
            while (pos0 < start + len || pos1 < start + len) {
                val racer = rnd.nextInt(2)
                val cur = if (racer == 0) pos0 else pos1
                if (cur >= start + len) continue
                val step = rnd.nextLong(1, len / 2 + 2)
                    .coerceAtMost(start + len - cur)
                val newPos = cur + step
                val adv = l.advance(racer, newPos, step.toInt())
                if (racer == 0) pos0 = newPos else pos1 = newPos
                creditedSum += adv.credited
                dupSum += adv.duplicate
                fetchedSum += step
                // Model: credited == max - start, at every instant.
                assertEquals(maxOf(pos0, pos1) - start, creditedSum)
                assertEquals(maxOf(pos0, pos1) - start, l.credited())
                // Duplicate == everything fetched beyond credit.
                assertEquals(fetchedSum - creditedSum, dupSum)
                assertEquals(fetchedSum - creditedSum, l.duplicate())
                // Progress can never pass 100% of the span.
                assertTrue(creditedSum <= len)
                if (l.finished) break
            }
        }
    }

    // Invariant 3: a trailing racer adds zero credited bytes
    // (the sampler, reading downloadedBytes, sees winner bytes
    // only) until it passes the leader.
    @Test
    fun trailingRacerCreditsNothing() {
        val l = ledger()
        assertEquals(50_000L, l.advance(0, 51_000, 50_000).credited)
        val behind = l.advance(1, 41_000, 40_000)
        assertEquals(0L, behind.credited)
        assertEquals(40_000L, behind.duplicate)
        // Passing the leader credits only the excess.
        val pass = l.advance(1, 61_000, 20_000)
        assertEquals(10_000L, pass.credited)
        assertEquals(10_000L, pass.duplicate)
    }

    // Invariant 4: loser bytes land in duplicate, never credit —
    // including advances that arrive after the win.
    @Test
    fun loserBytesAreDuplicateOnly() {
        val l = ledger()
        l.advance(1, 61_000, 60_000)
        val win = l.advance(0, 101_000, 100_000)
        assertTrue(win.becameWinner)
        assertTrue(l.isWinner(0))
        assertFalse(l.isWinner(1))
        val late = l.advance(1, 101_000, 40_000)
        assertEquals(0L, late.credited)
        assertEquals(40_000L, late.duplicate)
        assertEquals(100_000L, l.credited())
        assertEquals(100_000L, l.duplicate())
    }

    @Test
    fun firstToEndWinsExactlyOnce() {
        val l = ledger()
        assertFalse(l.advance(0, 90_000, 89_000).becameWinner)
        assertTrue(l.advance(1, 101_000, 100_000).becameWinner)
        assertFalse(l.advance(0, 101_000, 11_000).becameWinner)
        assertEquals(1, l.winner)
    }

    // Invariant 1 at task level: bitmap bytes + in-flight
    // credited progress == downloadedBytes, and the total never
    // exceeds the file size while a race is in flight.
    @Test
    fun taskLevelDownloadedEqualsBitmapPlusCredited() {
        val total = 1_000_000L
        val chunk = 250_000L
        val grid = ChunkPlanner.uniform(total, chunk)
        val done = booleanArrayOf(true, true, false, false)
        val bitmapBytes = ChunkPlanner.downloadedOf(grid, done)
        assertEquals(500_000L, bitmapBytes)
        // Chunk 3 (750_000..1_000_000) is raced from 800_000.
        val l = TailRace.Ledger(800_000, total, 750_000, total)
        l.advance(0, 900_000, 100_000)
        l.advance(1, 950_000, 150_000)
        val downloaded = bitmapBytes + 50_000 /* chunk 3 pre-race */ + l.credited()
        assertEquals(700_000L, downloaded)
        assertTrue(downloaded <= total)
        l.advance(1, total, 50_000)
        // Chunk 3 is now fully credited; chunk 2 is still open,
        // so the honest total is 500k + 250k, not the file size.
        assertEquals(750_000L, bitmapBytes + 50_000 + l.credited())
    }

    // Invariant 5 (caliber): the winner drains chunkParts over
    // the ORIGINAL span — the span-based rebalance the split
    // path uses is untouched by racing (a race adds no count).
    @Test
    fun raceLedgerCarriesOriginalSpanForTheWinnerDrain() {
        val l = TailRace.Ledger(800_000, 1_000_000, 750_000, 1_000_000)
        assertEquals(750_000L, l.ownerStart)
        assertEquals(1_000_000L, l.ownerEnd)
        assertEquals(200_000L, l.spanBytes)
        // The rebalance a split would have produced for the same
        // indexes is independent of the race (existing function,
        // existing meaning).
        val counts = ChunkPlanner.splitRebalancedCounts(
            origOverlap = listOf(3), newOverlap = listOf(3),
            tailOverlaps = listOf(listOf(3)),
        ) { 1 }
        assertEquals(mapOf(3 to 2), counts)
    }

    @Test
    fun budgetIsOnePercentCappedAtFourMb() {
        assertEquals(10_000L, TailRace.budgetBytes(1_000_000))
        assertEquals(4L * 1024 * 1024, TailRace.budgetBytes(10_000_000_000L))
        assertEquals(0L, TailRace.budgetBytes(0))
        assertEquals(0L, TailRace.budgetBytes(-5))
    }

    @Test
    fun loserRemainingAtWinIsEndMinusLoserPosClamped() {
        // v7.8 Step C caliber: the figure is the loser's
        // unfinished bytes at the winner's finish — end minus
        // the snapshotted loser position, never negative (a
        // loser already at the end contributes 0, not a claim).
        assertEquals(30_000L, TailRace.loserRemainingAtWin(100_000, 70_000))
        assertEquals(100_000L, TailRace.loserRemainingAtWin(100_000, 0))
        assertEquals(0L, TailRace.loserRemainingAtWin(100_000, 100_000))
        assertEquals(0L, TailRace.loserRemainingAtWin(100_000, 120_000))
        // Summed over two finished races it is plain addition —
        // the task counter accumulates exactly these values.
        val total = TailRace.loserRemainingAtWin(100_000, 70_000) +
            TailRace.loserRemainingAtWin(50_000, 50_000)
        assertEquals(30_000L, total)
    }

    @Test
    fun shouldRaceTriggerTable() {
        val minSplit = 128L * 1024
        fun call(
            enabled: Boolean = true, total: Long = 100_000_000,
            races: Int = 0, remaining: Long = 100_000,
            budgetLeft: Long = 1_000_000,
        ) = TailRace.shouldRace(enabled, total, races, remaining, minSplit, budgetLeft)
        assertTrue(call())
        assertFalse(call(enabled = false))               // switch off
        assertFalse(call(remaining = minSplit + 1))       // still splittable
        assertTrue(call(remaining = minSplit))            // boundary: unsplittable
        assertFalse(call(remaining = TailRace.MIN_RACE_BYTES - 1)) // too small
        assertFalse(call(races = TailRace.MAX_RACES))     // race slots full
        assertFalse(call(budgetLeft = 99_999))            // budget exhausted
        assertFalse(call(total = 0))
    }
}
