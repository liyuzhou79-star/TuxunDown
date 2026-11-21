package com.tuxundown.android.core

import java.util.concurrent.ConcurrentHashMap

/**
 * v2.1 audit (round 7, finding C1): the hard invariant behind
 * "one task, one engine".
 *
 * The download service's drain loop tracks the engine threads IT
 * started in a map of its own — but that bookkeeping is per drain
 * loop, and two drain loops could be alive at once (the worker
 * start in onStartCommand and in the connectivity callback was an
 * unsynchronized check-then-act on a @Volatile reference). Two
 * loops would each see the same QUEUED task as unclaimed and run
 * two engines over one .part file: overlapping writes at the same
 * offsets, double speed accounting, racing finalize.
 *
 * This claim set lives in DownloadCenter, outside any one loop,
 * and is held for the WHOLE runOne invocation — the transfer, the
 * retry backoff sleeps and the finalize — so a second runOne for
 * the same task id can never start, no matter which thread asks.
 * Plain JVM logic (a concurrent set) so it is unit-testable.
 */
class EngineClaims {
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    /** True when this call took the claim; false when a run already owns the task. */
    fun claim(taskId: String): Boolean = claimed.add(taskId)

    fun release(taskId: String) {
        claimed.remove(taskId)
    }

    fun isClaimed(taskId: String): Boolean = claimed.contains(taskId)
}
