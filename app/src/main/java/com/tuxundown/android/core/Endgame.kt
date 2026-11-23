package com.tuxundown.android.core

/**
 * Endgame split planning (v2.2) — pure logic, unit-tested.
 *
 * The classic endgame problem: near the end of a segmented
 * download every chunk has been STARTED, most workers are idle,
 * and the whole task waits on the one slowest connection still
 * dragging its final chunk. The fix is to cut the largest
 * in-flight remainder into pieces and hand the extra pieces to
 * the idle workers, so the tail finishes in parallel instead of
 * at the slowest connection's pace.
 *
 * This object only DECIDES: given the in-flight byte ranges
 * ([pos, end) per chunk) and how many workers are standing idle,
 * it returns the cut points for the single largest remainder.
 * The engine applies the plan (shrinks the owner's range, queues
 * the tail pieces). Splitting one range per planning round is
 * enough: several idle workers each trigger a round, and each
 * round re-reads the live ranges.
 *
 * Accounting honesty (how this coexists with the chunk bitset):
 * completion is tracked at CHUNK granularity — one bit per chunk
 * in [TaskRecord.chunkDone]. A chunk cut into parts still owns
 * exactly one bit; the engine counts outstanding parts
 * (chunkParts) and sets the bit only when every part has landed.
 * A part's live position (how far it has written inside the
 * chunk) exists ONLY in memory. If the process dies mid-chunk,
 * the bytes already written inside every unfinished chunk are
 * NOT credited on the next start — [ChunkPlanner.downloadedOf]
 * recomputes progress from the bitset, so those chunks are
 * fetched again from their first byte. Worst case re-download =
 * (in-flight chunks, at most the thread count) x chunk size
 * (at most [ChunkPlanner.MAX_CHUNK], 4 MB): a pathological
 * 32-thread x 4 MB corner is ~128 MB, but in practice only a
 * handful of chunks are in flight with partial bytes, so the
 * real cost is a few MB — the price of a bitset that stays one
 * bit per chunk instead of a per-part journal.
 *
 * v7.7 (Step B3, fast finish): the split trigger moves EARLIER.
 * Before B3 a worker only planned a split after the fresh-chunk
 * cursor was fully exhausted (unclaimed == 0) — the tail could
 * not start shrinking until every chunk had been claimed, even
 * when the fleet was about to run out of fresh work anyway.
 * Now [shouldAttemptSplit] opens the endgame zone as soon as
 * the unclaimed chunk count drops below the live worker count:
 * from that point at least one worker would otherwise finish
 * its fresh chunk and find nothing to claim, so splitting the
 * largest in-flight remainder first costs no fresh-work
 * parallelism (a worker that finds nothing worth splitting
 * falls through to its fresh claim unchanged). The split SIZE
 * floor also halves (the engine's MIN_SPLIT 256 KB -> 128 KB,
 * so the smallest piece halves 128 KB -> 64 KB): more, smaller
 * tail pieces for idle workers to chew in parallel. The
 * accounting impact is nil by construction — a split's parts
 * are tracked by the span-based chunkParts rebalance
 * (ChunkPlanner.splitRebalancedCounts), whose invariant (a
 * count reaching 0 is the ONLY way an index is marked done)
 * does not depend on how many parts a chunk was cut into; the
 * equivalence / invariant tests for that rebalance are
 * untouched and must keep passing.
 *
 * v7.7 (Step B3) verdict — 「尾巴双下」 (tail duplication: the
 * final chunk fetched by TWO connections at once, first
 * finisher wins, the loser discarded) is NOT adopted this
 * version. What blocks it is the byte accounting, not the
 * split machinery: the sampler's downloadedBytes counter (the
 * single source for the displayed speed and progress) advances
 * by each part's pos, so two live parts covering the SAME span
 * would both count the same bytes — reported speed and
 * downloaded totals would be inflated by exactly the
 * duplicated span, which the project's red line (never inflate
 * reported numbers) forbids. Per-source byte attribution
 * (task.sourceBytes) double-counts the same way. Making
 * duplication honest needs first-finisher-wins accounting
 * (the loser's pos advance rewound out of every counter, plus
 * a prompt cancel of its in-flight read) — a rework of the
 * completion path, not a constant change. It stays on the
 * v7.8.0 list with that rework as its price. The duplicate
 * TRAFFIC itself (at most one chunk span) was never the
 * blocker.
 */
object Endgame {

    /** One in-flight range: chunk [index], bytes [pos, end) left. */
    data class Range(val index: Int, val pos: Long, val end: Long) {
        val remaining: Long get() = end - pos
    }

    /**
     * A split plan for chunk [index]: the owner's range ends at
     * the first cut; each following segment [cuts[i], cuts[i+1])
     * (last one ends at the original end) becomes a tail piece
     * for an idle worker.
     */
    data class Split(val index: Int, val cuts: List<Long>)

    /**
     * Plan a split of the largest in-flight remainder for
     * [idleWorkers] idle workers, or null when nothing is worth
     * splitting:
     * - no ranges, or nobody idle -> null
     * - the largest remainder is <= [minSplit] -> null (too small
     *   to repay another connection's round trip)
     * - the remainder cannot be divided into pieces of at least
     *   [minSplit] / 2 each -> null
     * Otherwise the remainder is divided into
     * min(idleWorkers + 1, remainder / (minSplit / 2)) roughly
     * equal pieces and the interior cut points are returned in
     * ascending order.
     */
    fun planSplit(ranges: List<Range>, idleWorkers: Int, minSplit: Long): Split? {
        if (idleWorkers <= 0 || ranges.isEmpty()) return null
        val target = ranges.maxByOrNull { it.remaining } ?: return null
        val remaining = target.remaining
        if (remaining <= minSplit) return null
        val minPiece = (minSplit / 2).coerceAtLeast(1)
        val maxPieces = (remaining / minPiece).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pieces = minOf(idleWorkers + 1, maxPieces)
        if (pieces < 2) return null
        val cuts = ArrayList<Long>(pieces - 1)
        for (i in 1 until pieces) {
            cuts.add(target.pos + remaining * i / pieces)
        }
        // Equal division of a positive remainder into >= 2 pieces
        // guarantees strictly ascending interior points; guard
        // anyway so a bad plan can never reach the engine.
        if (cuts.any { it <= target.pos || it >= target.end } ||
            cuts.zipWithNext().any { (a, b) -> b <= a }
        ) return null
        return Split(target.index, cuts)
    }

    /**
     * v7.7 (Step B3): the early endgame-zone trigger (see the
     * class doc). Splitting is worth ATTEMPTING once the
     * unclaimed fresh chunks can no longer keep every live
     * worker busy — [unclaimedChunks] < [activeWorkers]. At
     * [unclaimedChunks] == 0 this is exactly the pre-B3 trigger
     * (the worker that finds no fresh chunk splits instead);
     * above the zone (enough fresh work for everyone) splitting
     * stays off, so mid-download workers never steal pieces
     * from each other while fresh chunks remain plentiful. An
     * attempt that finds nothing worth splitting (largest
     * remainder at/below the size floor) costs one [planSplit]
     * call and the worker claims fresh work as before.
     */
    fun shouldAttemptSplit(unclaimedChunks: Int, activeWorkers: Int): Boolean =
        activeWorkers > 0 && unclaimedChunks < activeWorkers

    /**
     * Worker-exit rule where the ThreadScaler's target meets
     * the endgame (v3.3, audit round 19 — combination pass):
     * a worker counts as surplus ONLY when it outnumbers the
     * scaler's target AND no already-cut tail piece is waiting
     * for a worker. A tail piece is finished work-planning —
     * its bytes are owed by exactly one worker — so a worker
     * that leaves "because the scaler shrank" while tails are
     * queued can strand them: the sampler's deficit top-up
     * respawns workers only while FRESH chunks remain, so in
     * the endgame nobody would ever come back for the tails
     * and a nearly-done task would fail with bytes missing
     * that were perfectly fetchable. Surplus workers therefore
     * drain the tail queue first; they exit only when it is
     * empty.
     */
    fun shouldExitSurplus(activeWorkers: Int, target: Int, tailsPending: Int): Boolean =
        activeWorkers > target && tailsPending <= 0
}
