package com.tuxundown.android.core

/**
 * v2.1: the home-screen widget's numbers. v2.6 audit round 12:
 * this is now THE aggregation for all three status surfaces —
 * the foreground notification and the home summary card used
 * to recompute the same numbers inline (the card in Float
 * arithmetic, which can land one percent off the Long math
 * here on large files). One function, one active-set
 * definition (RUNNING + QUEUED), one rounding rule; the
 * surfaces differ only in wording and refresh timing.
 * Pure, unit-tested.
 */
object WidgetState {

    data class Summary(
        /** Tasks not finished yet: RUNNING + QUEUED. */
        val activeCount: Int,
        /** 0..100 across the sized active tasks; 0 when no active
         *  task has a known size (progress is then unknowable,
         *  and the widget says so instead of faking a number). */
        val totalPct: Int,
        val anyRunning: Boolean,
        /** Byte sums over the sized active tasks (v2.6, for the
         *  home summary card's "x / y" line). */
        val totalBytes: Long,
        val doneBytes: Long,
    )

    fun summary(tasks: List<TaskRecord>): Summary {
        val active = tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }
        val sized = active.filter { it.totalSize > 0 }
        val totalAll = sized.sumOf { it.totalSize }
        val doneAll = sized.sumOf { it.downloaded }
        val pct = if (totalAll > 0) (doneAll * 100 / totalAll).toInt() else 0
        return Summary(
            activeCount = active.size,
            totalPct = pct.coerceIn(0, 100),
            anyRunning = active.any { it.status == TaskStatus.RUNNING },
            totalBytes = totalAll,
            doneBytes = doneAll,
        )
    }

    /**
     * v4.4: seconds until EVERYTHING is downloaded — the
     * aggregate mirror of TaskRecord.etaSeconds (the single-task
     *口径: remaining bytes / current speed, integer division,
     * -1 when no honest answer exists).
     *
     * The label is 「全部下完」, so the remaining bytes span the
     * whole active set (RUNNING + QUEUED — a queued task still
     * has to be downloaded before "全部" is true), while the
     * speed is what is actually flowing now: the RUNNING tasks'
     * speedBps sum, exactly the card's 总速度.
     *
     * Honesty rules, all returning -1 (the caller hides the
     * text): nothing is RUNNING, the total speed is <= 0, or
     * nothing remains. And if ANY active task has an unknown
     * size (totalSize <= 0), "全部" is unprovable — returning a
     * number computed from the sized tasks alone would quietly
     * under-promise, so the verdict is -1 instead. Tasks outside
     * the active set (DONE / PAUSED / FAILED) never enter the
     * sums and never veto the verdict.
     */
    fun etaSeconds(tasks: List<TaskRecord>): Long {
        val active = tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }
        if (active.any { it.totalSize <= 0 }) return -1
        val running = active.filter { it.status == TaskStatus.RUNNING }
        if (running.isEmpty()) return -1
        val speed = running.sumOf { it.speedBps }
        if (speed <= 0) return -1
        val remaining = active.sumOf { it.totalSize - it.downloaded }
        if (remaining <= 0) return -1
        return remaining / speed
    }
}
