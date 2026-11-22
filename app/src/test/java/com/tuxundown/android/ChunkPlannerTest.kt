package com.tuxundown.android

import com.tuxundown.android.core.ChunkGrid
import com.tuxundown.android.core.ChunkPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    @Test
    fun chunksCoverFileExactly() {
        val total = 10_000_001L
        val chunk = 1_000_000L
        val count = ChunkPlanner.chunkCount(total, chunk)
        assertEquals(11, count)
        var covered = 0L
        for (i in 0 until count) {
            val r = ChunkPlanner.rangeOf(total, chunk, i)
            assertEquals(covered, r.first)
            covered = r.last + 1
        }
        assertEquals(total, covered)
    }

    @Test
    fun bitsetRoundTrip() {
        val done = booleanArrayOf(true, false, true, true, false, false, false, false, true)
        val back = ChunkPlanner.decodeBitset(ChunkPlanner.encodeBitset(done), done.size)
        assertTrue(done.contentEquals(back))
    }

    @Test
    fun downloadedOfSumsFinishedChunks() {
        val total = 3_000L
        val chunk = 1_000L
        val done = booleanArrayOf(true, false, true)
        assertEquals(2_000L, ChunkPlanner.downloadedOf(done, total, chunk))
    }

    @Test
    fun chunkSizeClamped() {
        assertEquals(ChunkPlanner.MIN_CHUNK, ChunkPlanner.chunkSizeFor(1_000, 32))
        assertEquals(ChunkPlanner.MAX_CHUNK, ChunkPlanner.chunkSizeFor(10L * 1024 * 1024 * 1024, 1))
    }

    @Test
    fun adaptiveChunksAreFineGrainedForLargeFiles() {
        // v1.8 tiers: the 271 MB KataGo model lands in the 1 MB
        // tier — still a few hundred chunks for work stealing,
        // but ~1/3 fewer requests than the v1.3 formula made.
        val total = 271_440_852L
        val chunk = ChunkPlanner.chunkSizeFor(total, 16)
        assertEquals(1024L * 1024, chunk)
        val count = ChunkPlanner.chunkCount(total, chunk)
        assertTrue(count in 200..400)
    }

    @Test
    fun chunkTiersByFileSize() {
        // v1.8: one representative size per tier (16 threads).
        val kb = 1024L
        val mb = 1024L * kb
        assertEquals(128 * kb, ChunkPlanner.chunkSizeFor(2 * mb, 16))
        assertEquals(512 * kb, ChunkPlanner.chunkSizeFor(30 * mb, 16))
        assertEquals(1 * mb, ChunkPlanner.chunkSizeFor(500 * mb, 16))
        assertEquals(2 * mb, ChunkPlanner.chunkSizeFor(2 * 1024 * mb, 16))
        assertEquals(4 * mb, ChunkPlanner.chunkSizeFor(20 * 1024 * mb, 16))
        // Tier boundaries are inclusive on the lower tier.
        assertEquals(128 * kb, ChunkPlanner.chunkSizeFor(8 * mb, 16))
        assertEquals(512 * kb, ChunkPlanner.chunkSizeFor(128 * mb, 16))
        assertEquals(1 * mb, ChunkPlanner.chunkSizeFor(1024 * mb, 16))
    }

    @Test
    fun chunkCountStaysInFewHundredRange() {
        // The point of the tiers: no size explodes into thousands
        // of requests, and no mid-size file starves the steal queue.
        val mb = 1024L * 1024
        for (total in listOf(30 * mb, 271 * mb, 1024 * mb, 5 * 1024 * mb)) {
            val chunk = ChunkPlanner.chunkSizeFor(total, 16)
            val count = ChunkPlanner.chunkCount(total, chunk)
            assertTrue("total=$total count=$count", count in 32..4200)
        }
    }

    @Test
    fun chunkSizeIsMonotonicInFileSize() {
        var prev = 0L
        var total = 1024L * 1024
        while (total <= 32L * 1024 * 1024 * 1024) {
            val chunk = ChunkPlanner.chunkSizeFor(total, 16)
            assertTrue("total=$total", chunk >= prev)
            prev = chunk
            total *= 2
        }
    }

    @Test
    fun chunkCountIsCappedAgainstHostileInput() {
        // v1.9 audit: counts parsed from an imported backup must
        // never size a giant array — the count clamps instead.
        assertEquals(
            ChunkPlanner.MAX_CHUNKS,
            ChunkPlanner.chunkCount(Long.MAX_VALUE - 1, 1),
        )
        // Long overflow in (total + chunk - 1) must not explode either.
        val n = ChunkPlanner.chunkCount(Long.MAX_VALUE, 2)
        assertTrue(n in 0..ChunkPlanner.MAX_CHUNKS)
        // decodeBitset clamps its count and only parses the hex
        // bytes the clamped count needs.
        val bits = ChunkPlanner.decodeBitset("ff".repeat(100_000), Int.MAX_VALUE)
        assertEquals(ChunkPlanner.MAX_CHUNKS, bits.size)
    }

    // ---------- v4.2: two-zone ChunkGrid ----------

    /** The tiling contract every grid must satisfy: ranges are
     *  contiguous, non-overlapping, non-empty, their union is
     *  exactly [0, total), and chunkCount agrees with enumerating
     *  rangeOf until the file is covered. */
    private fun assertGridTiles(grid: ChunkGrid) {
        val total = grid.totalSize
        val count = ChunkPlanner.chunkCount(grid)
        var covered = 0L
        var enumerated = 0
        while (covered < total) {
            val r = ChunkPlanner.rangeOf(grid, enumerated)
            assertEquals("grid=$grid index=$enumerated", covered, r.first)
            assertTrue("empty range at $enumerated in $grid", r.last >= r.first)
            covered = r.last + 1
            enumerated++
        }
        assertEquals("grid=$grid", total, covered)
        assertEquals("grid=$grid", count, enumerated)
    }

    @Test
    fun twoZoneGridsTileExactly() {
        val mb = 1024L * 1024
        // A gridFor product: 1 GB, 1 MB coarse, burst opening.
        assertGridTiles(ChunkPlanner.gridFor(1024 * mb, mb, 16))
        // Fine-zone boundary lands EXACTLY on total/8: 256 MB at
        // 2 MB coarse -> fine 512 KB, zone = min(32 MB, 512 KB x
        // 16 x 4 = 32 MB) = 32 MB = total/8, fineCount = 64.
        val exact = ChunkPlanner.gridFor(256 * mb, 2 * mb, 16)
        assertEquals(256 * mb / 8, exact.fineZoneBytes)
        assertGridTiles(exact)
        // Fine zone's last piece truncated by total (the fine
        // piece itself is bigger than the whole file).
        assertGridTiles(ChunkGrid(250_000, 300_000, 1, 500_000))
        // Coarse tail's last piece truncated by total.
        assertGridTiles(ChunkGrid(1_000_003, 300_000, 2, 500_000))
        // Boundary between zones mid-file, odd sizes everywhere.
        assertGridTiles(ChunkGrid(10_000, 1_000, 3, 2_000))
        assertGridTiles(ChunkGrid(999_983, 128 * 1024, 5, 1024 * 1024))
    }

    @Test
    fun uniformGridMatchesLegacyFunctionsValueForValue() {
        val cases = listOf(
            1L to 1L,
            3_000L to 1_000L,
            10_000_001L to 1_000_000L,
            271_440_852L to 1024L * 1024,
            5L * 1024 * 1024 * 1024 to 2L * 1024 * 1024,
        )
        for ((total, chunk) in cases) {
            val grid = ChunkPlanner.uniform(total, chunk)
            assertEquals(0, grid.fineCount)
            assertEquals(0L, grid.fineZoneBytes)
            assertEquals(
                ChunkPlanner.chunkCount(total, chunk),
                ChunkPlanner.chunkCount(grid),
            )
            val count = ChunkPlanner.chunkCount(grid)
            for (i in 0 until count) {
                assertEquals(
                    "total=$total chunk=$chunk i=$i",
                    ChunkPlanner.rangeOf(total, chunk, i),
                    ChunkPlanner.rangeOf(grid, i),
                )
            }
            val done = BooleanArray(count) { it % 3 == 0 }
            assertEquals(
                ChunkPlanner.downloadedOf(done, total, chunk),
                ChunkPlanner.downloadedOf(grid, done),
            )
        }
    }

    @Test
    fun gridDownloadedOfSumsAcrossTheZoneBoundary() {
        // Fine: [0,1k) [1k,2k) [2k,3k); coarse: [3k,5k) [5k,7k)
        // [7k,9k) [9k,10k).
        val grid = ChunkGrid(10_000, 1_000, 3, 2_000)
        assertEquals(7, ChunkPlanner.chunkCount(grid))
        // Last fine piece + first coarse piece.
        val done = booleanArrayOf(false, false, true, true, false, false, false)
        assertEquals(1_000L + 2_000L, ChunkPlanner.downloadedOf(grid, done))
        // Everything: exactly the file size, truncated tail included.
        val all = BooleanArray(7) { true }
        assertEquals(10_000L, ChunkPlanner.downloadedOf(grid, all))
    }

    @Test
    fun gridForRules() {
        val kb = 1024L
        val mb = 1024L * kb
        // Small file: no fine zone however coarse the chunks.
        assertEquals(0, ChunkPlanner.gridFor(100 * mb, mb, 16).fineCount)
        // Coarse below 512 KB: no fine zone however big the file.
        assertEquals(0, ChunkPlanner.gridFor(1024 * mb, 256 * kb, 16).fineCount)
        // The pinned example: 1 GB at 1 MB coarse, burst 16 ->
        // fine 256 KB, zone = min(128 MB, 256 KB x 16 x 4) =
        // 16 MB, fineCount = 64.
        val g = ChunkPlanner.gridFor(1024 * mb, mb, 16)
        assertEquals(256 * kb, g.fineSize)
        assertEquals(64, g.fineCount)
        assertEquals(16 * mb, g.fineZoneBytes)
        assertEquals(mb, g.coarseSize)
        // The 256 MB threshold is inclusive.
        assertTrue(ChunkPlanner.gridFor(256 * mb, mb, 16).fineCount > 0)
        assertEquals(0, ChunkPlanner.gridFor(256 * mb - 1, mb, 16).fineCount)
        // Coarse exactly 512 KB -> fine exactly MIN_CHUNK, allowed.
        val edge = ChunkPlanner.gridFor(256 * mb, 512 * kb, 16)
        assertEquals(ChunkPlanner.MIN_CHUNK, edge.fineSize)
        assertTrue(edge.fineCount > 0)
        // A fine zone never swallows the whole file.
        assertTrue(g.fineZoneBytes < g.totalSize)
    }

    @Test
    fun manyThreadsOnMidFileStillGetSeveralChunksEach() {
        // 64 MB at 32 threads: the 512 KB tier alone would give
        // only 4 chunks per thread; the guard may shrink chunks
        // but never below MIN_CHUNK.
        val chunk = ChunkPlanner.chunkSizeFor(64L * 1024 * 1024, 32)
        assertTrue(chunk >= ChunkPlanner.MIN_CHUNK)
        assertTrue(ChunkPlanner.chunkCount(64L * 1024 * 1024, chunk) >= 32 * 4)
    }
}
