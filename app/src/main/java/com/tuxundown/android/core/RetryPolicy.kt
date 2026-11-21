package com.tuxundown.android.core

/**
 * Whole-task automatic retry policy (v1.4): after a task FAILS, the
 * service may re-run it a limited number of times with an
 * exponential backoff, so a flaky server or a brief network drop
 * does not leave the user with a dead task. Pure — unit-tested.
 */
object RetryPolicy {

    /** [used] = retries already spent on this failure streak. */
    fun shouldRetry(used: Int, limit: Int): Boolean = limit > 0 && used < limit

    /** Backoff before retry #[used] (0-based): 10s, 30s, 90s, then 3 min cap. */
    fun backoffMs(used: Int): Long = when (used.coerceAtLeast(0)) {
        0 -> 10_000
        1 -> 30_000
        2 -> 90_000
        else -> 180_000
    }
}
