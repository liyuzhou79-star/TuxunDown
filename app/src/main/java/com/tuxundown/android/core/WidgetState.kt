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
}
