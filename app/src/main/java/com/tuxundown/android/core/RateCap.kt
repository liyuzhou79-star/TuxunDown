package com.tuxundown.android.core

/**
 * v2.1: compose the two user speed limits into the single rate an
 * engine's token bucket obeys.
 *
 * - The GLOBAL limit (v1.3; a total budget shared across running
 *   tasks since v1.6) arrives as this task's current share in
 *   bytes/s — 0 means "no global limit".
 * - The PER-TASK limit (v2.1) lives on the task itself, in KB/s
 *   (the same unit the settings UI uses) — 0 means "no cap of its
 *   own".
 *
 * The effective rate is the SMALLER of the limits that are set,
 * and 0 (unlimited) only when neither is set. Pure, unit-tested.
 */
object RateCap {
    fun effectiveBps(globalShareBps: Long, taskLimitKBps: Int): Long {
        val taskBps = if (taskLimitKBps > 0) taskLimitKBps.toLong() * 1024 else 0L
        return when {
            globalShareBps <= 0 -> taskBps
            taskBps <= 0 -> globalShareBps
            else -> minOf(globalShareBps, taskBps)
        }
    }
}
