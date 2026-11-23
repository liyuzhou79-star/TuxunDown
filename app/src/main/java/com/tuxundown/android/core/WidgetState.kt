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
     * v5.3 (audit round 38): the widget's title line. It used to
     * read 「正在下载 N 个任务」 whenever the active set was
     * non-empty — but the active set is RUNNING + QUEUED, so a
     * queue parked whole (等 WiFi / 等充电 / 断网停泊， nothing
     * actually transferring, the service possibly not even
     * alive) was announced as downloading. The tile's umbrella
     * word for the same set is 进行中 and the card's word for a
     * queued task is 排队中； the widget now says 正在下载 only
     * when [Summary.anyRunning] proves something is really
     * moving, and 排队中 otherwise. The empty case keeps the
     * bare app name (the line below it already says
     * 没有进行中的任务）.
     */
    fun headline(s: Summary): String = when {
        s.activeCount <= 0 -> "TuxunDown"
        s.anyRunning -> "正在下载 ${s.activeCount} 个任务"
        else -> "排队中 ${s.activeCount} 个任务"
    }

    /**
     * v5.3 (audit round 38): ONE task's integer percent, in the
     * same Long arithmetic as [summary] — the single-task mirror
     * the task card and the notification's per-task texts read.
     * They used to go through the Float ratio
     * (Format.percent(task.progress)), which can land one point
     * BELOW the Long verdict on the same bytes (29/100: the
     * Float nearest 0.29 times 100 truncates to 28), so with a
     * single running task the card/notification said 28% while
     * the widget and the summary card said 29% about the very
     * same task. Semantics mirror TaskRecord.progress exactly:
     * an HLS task's percent is its merged-segment fraction, an
     * unknown size is 0 (the surfaces say 大小未知 instead of
     * leaning on the number), and the result is clamped to
     * 0..100. The Float [TaskRecord.progress] itself stays —
     * it feeds the progress BAR, where a fraction is the right
     * shape and one percent of fill is invisible.
     */
    fun taskPercent(task: TaskRecord): Int {
        if (task.isHls && task.hlsTotalSegments > 0) {
            val done = task.hlsDone.count { it }
            return (done * 100L / task.hlsTotalSegments).toInt().coerceIn(0, 100)
        }
        if (task.totalSize <= 0) return 0
        return (task.downloaded * 100 / task.totalSize).toInt().coerceIn(0, 100)
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
