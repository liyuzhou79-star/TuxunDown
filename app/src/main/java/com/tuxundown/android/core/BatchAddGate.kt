package com.tuxundown.android.core

/**
 * v7.7 (audit round 62, B-2): flush bookkeeping for batch task
 * creation — the add-side counterpart of TaskStore.removeAll's
 * single-flush bulk removal (v3.4).
 *
 * The problem it solves: a batch create (multi-line paste, .txt
 * import, a sniff multi-select — capped at 100 links) funnelled
 * every record through TaskStore.add, and add flushes the WHOLE
 * store to disk immediately (saveNow) per record, on the UI
 * thread: O(batch x store) — ~900 MB of IO for 100 links into a
 * 1500-record store. A single add must keep that immediate
 * flush (losing a whole task record to a crash is far worse
 * than losing 1.5 s of progress — see TaskStore's save
 * comment), so the batch is the thing that changes: while a
 * batch is open, adds ride the existing 1.5 s coalesced save()
 * instead, and closing the OUTERMOST batch performs exactly
 * one saveNow.
 *
 * Pure counter, no Android, no IO — TaskStore owns the gate
 * and acts on its answers; the nesting and end-without-begin
 * rules are unit-tested here. Thread-safe: a batch is opened
 * on the thread running the batch loop while the adds
 * themselves land on the UI thread (the startBatch latch
 * pattern), so every method is synchronized.
 */
class BatchAddGate {

    private var depth = 0

    /** Open one batch level (batches nest safely). */
    @Synchronized
    fun begin() {
        depth++
    }

    /**
     * Close one batch level. Returns true exactly when this
     * call closed the OUTERMOST batch — the caller then owes
     * the batch's one full flush. An end with no open batch
     * returns false and changes nothing.
     */
    @Synchronized
    fun end(): Boolean {
        if (depth == 0) return false
        depth--
        return depth == 0
    }

    /** True while any batch level is open: adds must NOT
     *  flush immediately inside a batch. */
    @Synchronized
    fun inBatch(): Boolean = depth > 0
}
