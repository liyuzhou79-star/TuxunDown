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
}
