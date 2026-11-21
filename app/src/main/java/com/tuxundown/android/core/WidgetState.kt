package com.tuxundown.android.core

/**
 * v2.1: the home-screen widget's numbers, computed exactly the
 * way the foreground notification computes its aggregate (same
 * active set, same definition of total progress) so the two
 * surfaces can never disagree. Pure, unit-tested.
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
        )
    }
}
