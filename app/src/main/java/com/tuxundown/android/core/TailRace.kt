package com.tuxundown.android.core

/**
 * v7.8 (Step A): tail duplication ("first finisher wins") byte
 * accounting — pure logic, unit-tested.
 *
 * HLS and FTP downloads do NOT participate in tail racing: this
 * ledger is only ever created by the segmented HTTP path in
 * DownloadEngine. HLS segments are whole objects fetched by a
 * different pool and FTP parts have their own session model;
 * neither is wired to a Ledger.
 *
 * Why this class exists (the v7.7 END-8 verdict): two live
 * connections over the SAME byte span would each advance the
 * sampler's downloadedBytes (and per-source sourceBytes) with
 * their own position, so the same bytes would be counted twice
 * and the displayed speed / progress would be inflated — a
 * direct hit on the project's red line (reported numbers must
 * never be inflated). This ledger is the price of making
 * duplication honest:
 *
 * Both racers start at the same span start and fetch the same
 * bytes (same URL / Range; the file content at those offsets is
 * identical, and each response still passes downloadRange's
 * Content-Range / total / ETag gauntlet). The span's completion
 * is therefore max(posA, posB): a byte is in the file as soon as
 * EITHER racer has written it. Credited progress advances only
 * when the leading position advances:
 *
 *   creditedDelta = newMax - oldMax        (goes to downloadedBytes)
 *   duplicateDelta = fetched - creditedDelta (goes to duplicateBytes)
 *
 * Invariants (pinned by TailRaceTest):
 *  1. A raced span contributes its bytes to downloadedBytes
 *     exactly once: total credited over the span's life equals
 *     max(posA, posB) - start at every instant, never the sum.
 *  2. Credited progress for a span never exceeds the span
 *     length, so task progress can never pass 100% from racing.
 *  3. The speed sampler reads downloadedBytes only, so it counts
 *     winner (leading) bytes only — a trailing racer's advance
 *     adds 0 credited bytes until it passes the leader.
 *  4. Every byte the trailing racer fetched is accounted as
 *     duplicate traffic in duplicateBytes — never dropped from
 *     the books, never mixed into downloadedBytes / sourceBytes
 *     (sourceBytes receives only the credited delta, attributed
 *     to the racer whose advance produced it).
 *  5. Racing does not touch the chunkParts / bitset accounting:
 *     the racer is a shadow part that owns no chunkParts count.
 *     Exactly one completion drain happens for the raced part —
 *     performed by the winner, over the ORIGINAL part's full
 *     span — so the v4.7 invariant (a count reaching 0 is the
 *     only way an index is marked done) is unchanged.
 *
 * The first racer whose position reaches the span end wins
 * (decided atomically inside [Ledger.advance]); the engine then
 * cancels the loser promptly (cancel flag + connection
 * disconnect, checked on every read-loop iteration).
 */
object TailRace {

    /** At most this many spans race at once per task. */
    const val MAX_RACES = 2

    /** Fixed hard cap on raced span bytes per run. */
    const val BUDGET_CAP_BYTES = 4L * 1024 * 1024

    /** A remainder smaller than this is not worth a second
     *  connection's round trip. */
    const val MIN_RACE_BYTES = 16L * 1024

    /**
     * Duplicate-traffic budget for one run: raced spans may
     * total at most 1% of the file AND at most
     * [BUDGET_CAP_BYTES], whichever is smaller.
     */
    fun budgetBytes(totalSize: Long): Long =
        if (totalSize <= 0) 0 else minOf(totalSize / 100, BUDGET_CAP_BYTES)

    /**
     * Trigger decision (the engine calls this only inside the
     * v7.7 endgame zone, after a split attempt found nothing
     * worth splitting): race the largest remaining span when
     *  - the user switch is on,
     *  - the span is no longer splittable ([remaining] at or
     *    below the engine's MIN_SPLIT) but still at least
     *    [MIN_RACE_BYTES],
     *  - fewer than [MAX_RACES] races are already running, and
     *  - the span fits the run's remaining duplicate budget.
     */
    fun shouldRace(
        enabled: Boolean,
        totalSize: Long,
        activeRaces: Int,
        remaining: Long,
        minSplit: Long,
        budgetLeft: Long,
    ): Boolean =
        enabled && totalSize > 0 &&
            activeRaces in 0 until MAX_RACES &&
            remaining >= MIN_RACE_BYTES && remaining <= minSplit &&
            remaining <= budgetLeft

    /**
     * v7.8 (Step C): the provable "what the race bought" figure —
     * when the winner reaches [end], how many bytes the loser
     * still had left at that moment ([loserPos] is the loser's
     * position snapshotted by the engine inside cancelRaceLoser,
     * under the loser's own lock). This is a recorded fact, NOT
     * a time estimate: it is never converted into seconds.
     * Clamped to [0, span]: a loser position outside the span
     * (already at the end, or a stale snapshot) contributes 0
     * rather than a negative or inflated claim.
     */
    fun loserRemainingAtWin(end: Long, loserPos: Long): Long =
        (end - loserPos).coerceAtLeast(0)

    /** Result of one racer advance: how many of the [fetched]
     *  bytes were new (credited) and how many were duplicate. */
    data class Advance(val credited: Long, val duplicate: Long, val becameWinner: Boolean)

    /**
     * One raced span [start, end). [ownerStart, ownerEnd) is the
     * ORIGINAL part's full span (the race usually covers only
     * its tail): the winner drains chunkParts over that full
     * span, exactly as the un-raced part would have.
     * Racer ids: 0 = the original part, 1 = the shadow racer.
     */
    class Ledger(
        val start: Long,
        val end: Long,
        val ownerStart: Long,
        val ownerEnd: Long,
    ) {
        private var pos0: Long = start
        private var pos1: Long = start

        @Volatile var finished: Boolean = false
            private set

        /** 0 / 1 once [finished], -1 while the race is open. */
        @Volatile var winner: Int = -1
            private set

        val spanBytes: Long get() = end - start

        /** Bytes of this span credited so far: max口径, once. */
        @Synchronized fun credited(): Long = maxOf(pos0, pos1) - start

        /** Duplicate bytes fetched so far (both racers' totals
         *  minus the credited share). */
        @Synchronized fun duplicate(): Long =
            (pos0 - start) + (pos1 - start) - credited()

        fun isWinner(racer: Int): Boolean = finished && winner == racer

        /**
         * Record that [racer] has now written up to [newPos]
         * (it fetched [fetched] bytes to get there). Returns the
         * credited / duplicate split for THIS advance. The first
         * racer to reach [end] becomes the winner atomically.
         */
        @Synchronized
        fun advance(racer: Int, newPos: Long, fetched: Int): Advance {
            val clamped = newPos.coerceIn(start, end)
            val oldMax = maxOf(pos0, pos1)
            if (racer == 0) {
                if (clamped > pos0) pos0 = clamped
            } else {
                if (clamped > pos1) pos1 = clamped
            }
            val newMax = maxOf(pos0, pos1)
            var credited = newMax - oldMax
            var duplicate = fetched.toLong() - credited
            if (finished && winner != racer) {
                // The race was already decided: nothing this
                // racer brings can be credited any more.
                duplicate += credited
                credited = 0
            }
            var became = false
            if (!finished && clamped >= end) {
                finished = true
                winner = racer
                became = true
            }
            return Advance(credited, duplicate.coerceAtLeast(0), became)
        }
    }
}
