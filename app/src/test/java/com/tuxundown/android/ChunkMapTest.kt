package com.tuxundown.android

import com.tuxundown.android.core.ChunkMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.5: the chunk progress map's aggregation rule — one
 * cell per chunk while small, consecutive buckets when
 * big, and the DONE > ACTIVE > PARTIAL > PENDING state
 * priority inside every cell.
 */
class ChunkMapTest {

    private fun flags(size: Int, doneIdx: Set<Int>): BooleanArray =
        BooleanArray(size) { it in doneIdx }

    @Test
    fun emptyInputGivesNoCells() {
        assertTrue(ChunkMap.cells(BooleanArray(0), emptySet()).isEmpty())
        assertTrue(ChunkMap.hlsCells(BooleanArray(0), running = true).isEmpty())
    }

    @Test
    fun smallCountsStayOneCellPerChunk() {
        val cells = ChunkMap.cells(
            flags(5, setOf(0, 1)), active = setOf(2),
        )
        assertEquals(5, cells.size)
        assertEquals(ChunkMap.State.DONE, cells[0].state)
        assertEquals(ChunkMap.State.DONE, cells[1].state)
        assertEquals(ChunkMap.State.ACTIVE, cells[2].state)
        assertEquals(ChunkMap.State.PENDING, cells[3].state)
        assertEquals(ChunkMap.State.PENDING, cells[4].state)
        // A done chunk that is also (stale-)active reads DONE.
        val both = ChunkMap.cells(flags(2, setOf(0)), active = setOf(0, 1))
        assertEquals(ChunkMap.State.DONE, both[0].state)
        assertEquals(ChunkMap.State.ACTIVE, both[1].state)
    }

    @Test
    fun exactlyAtTheCapStillPerChunk() {
        val cells = ChunkMap.cells(
            flags(ChunkMap.MAX_CELLS, emptySet()), emptySet(),
        )
        assertEquals(ChunkMap.MAX_CELLS, cells.size)
        assertTrue(cells.all { it.total == 1 })
    }

    @Test
    fun bigCountsAggregateIntoBuckets() {
        // 200 chunks, cap 72 -> bucket = ceil(200/72) = 3,
        // 67 cells (66 full buckets + a 2-chunk tail).
        val cells = ChunkMap.cells(flags(200, emptySet()), emptySet())
        assertEquals(67, cells.size)
        assertEquals(200, cells.sumOf { it.total })
        assertEquals(2, cells.last().total)
        assertTrue(cells.all { it.state == ChunkMap.State.PENDING })
    }

    @Test
    fun bucketStatesFollowThePriority() {
        // 6 chunks, cap 3 -> buckets of 2: [done,done],
        // [done,pending], [pending,pending] with chunk 5
        // active inside the last one.
        val done = flags(6, setOf(0, 1, 2))
        val cells = ChunkMap.cells(done, active = setOf(5), maxCells = 3)
        assertEquals(3, cells.size)
        assertEquals(ChunkMap.State.DONE, cells[0].state)
        assertEquals(2, cells[0].done)
        assertEquals(ChunkMap.State.PARTIAL, cells[1].state)
        assertEquals(1, cells[1].done)
        // ACTIVE outranks PENDING inside a bucket.
        assertEquals(ChunkMap.State.ACTIVE, cells[2].state)
        // And outranks PARTIAL too: one done + one active.
        val mixed = ChunkMap.cells(flags(4, setOf(0)), active = setOf(1), maxCells = 2)
        assertEquals(ChunkMap.State.ACTIVE, mixed[0].state)
        assertEquals(1, mixed[0].done)
    }

    @Test
    fun hlsMarksTheMergeCursorWhileRunning() {
        val done = flags(6, setOf(0, 1, 2))
        val running = ChunkMap.hlsCells(done, running = true)
        assertEquals(ChunkMap.State.DONE, running[2].state)
        assertEquals(ChunkMap.State.ACTIVE, running[3].state)
        assertEquals(ChunkMap.State.PENDING, running[4].state)
        // Paused: no cursor, the prefix just stops.
        val paused = ChunkMap.hlsCells(done, running = false)
        assertEquals(ChunkMap.State.PENDING, paused[3].state)
        // Fully merged: no cursor to mark.
        val all = ChunkMap.hlsCells(flags(3, setOf(0, 1, 2)), running = true)
        assertTrue(all.all { it.state == ChunkMap.State.DONE })
    }
}
