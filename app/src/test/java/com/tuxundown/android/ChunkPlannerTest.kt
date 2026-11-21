package com.tuxundown.android

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
    fun manyThreadsOnMidFileStillGetSeveralChunksEach() {
        // 64 MB at 32 threads: the 512 KB tier alone would give
        // only 4 chunks per thread; the guard may shrink chunks
        // but never below MIN_CHUNK.
        val chunk = ChunkPlanner.chunkSizeFor(64L * 1024 * 1024, 32)
        assertTrue(chunk >= ChunkPlanner.MIN_CHUNK)
        assertTrue(ChunkPlanner.chunkCount(64L * 1024 * 1024, chunk) >= 32 * 4)
    }
}
