package com.tuxundown.android.core

/**
 * v3.5: pure aggregation behind the task-details chunk
 * progress map （分片进度图）. One cell per chunk while the
 * count is small; consecutive buckets when it is not, so
 * the map never draws more than [MAX_CELLS] cells however
 * many chunks a big file was cut into.
 *
 * Aggregation rule (pinned by ChunkMapTest):
 * - count <= maxCells: one cell per chunk, in order.
 * - count > maxCells: buckets of ceil(count / maxCells)
 *   consecutive chunks; the last bucket may be smaller.
 * A cell's state:
 * - DONE — every chunk in it is done;
 * - ACTIVE — not all done, but at least one chunk in it
 *   is being downloaded right now;
 * - PARTIAL — some but not all done, none active;
 * - PENDING — nothing done, nothing active.
 * DONE wins over ACTIVE (a finished chunk an engine is
 * just letting go of is finished), ACTIVE wins over
 * PARTIAL (live work is the more useful signal).
 */
object ChunkMap {

    const val MAX_CELLS = 72

    enum class State { DONE, ACTIVE, PARTIAL, PENDING }

    data class Cell(val state: State, val done: Int, val total: Int)

    fun cells(
        done: BooleanArray,
        active: Set<Int>,
        maxCells: Int = MAX_CELLS,
    ): List<Cell> {
        if (done.isEmpty()) return emptyList()
        val cap = maxCells.coerceAtLeast(1)
        if (done.size <= cap) {
            return done.indices.map { i ->
                Cell(
                    state = when {
                        done[i] -> State.DONE
                        i in active -> State.ACTIVE
                        else -> State.PENDING
                    },
                    done = if (done[i]) 1 else 0,
                    total = 1,
                )
            }
        }
        val bucket = (done.size + cap - 1) / cap
        val out = ArrayList<Cell>((done.size + bucket - 1) / bucket)
        var from = 0
        while (from < done.size) {
            val to = minOf(done.size, from + bucket)
            var d = 0
            var anyActive = false
            for (i in from until to) {
                if (done[i]) d++
                if (i in active) anyActive = true
            }
            val total = to - from
            out.add(
                Cell(
                    state = when {
                        d == total -> State.DONE
                        anyActive -> State.ACTIVE
                        d > 0 -> State.PARTIAL
                        else -> State.PENDING
                    },
                    done = d,
                    total = total,
                )
            )
            from = to
        }
        return out
    }

    /**
     * HLS geometry: [done] is the MERGED prefix (segments
     * are written strictly in playlist order). The record
     * vouches only for that prefix, so the map shows it as
     * DONE, the segment at the merge cursor — the first
     * not-yet-merged one — as ACTIVE while the task runs
     * (later segments may already be downloading in
     * parallel, but they are not merged yet and the map
     * does not pretend otherwise), and the rest PENDING.
     */
    fun hlsCells(
        done: BooleanArray,
        running: Boolean,
        maxCells: Int = MAX_CELLS,
    ): List<Cell> {
        val active = if (running) {
            val idx = done.indexOfFirst { !it }
            if (idx >= 0) setOf(idx) else emptySet()
        } else {
            emptySet()
        }
        return cells(done, active, maxCells)
    }
}
