package com.tuxundown.android.core

/**
 * Pure queue-order helpers (v1.4), kept free of Android/Context so
 * they are unit-testable. The service drains the queue by taking the
 * first QUEUED record in list order, so list order IS queue order.
 */
object QueueOrder {

    /**
     * "插队": move [task] so it becomes the first QUEUED task in the
     * list (directly behind any RUNNING tasks, which are already
     * transferring and are never displaced). Returns true if the
     * order actually changed.
     */
    fun moveToFrontOfQueue(tasks: MutableList<TaskRecord>, task: TaskRecord): Boolean {
        val from = tasks.indexOf(task)
        if (from < 0) return false
        var insertAt = tasks.indexOfFirst { it.status == TaskStatus.QUEUED }
        if (insertAt < 0) insertAt = tasks.size
        if (from == insertAt) return false
        tasks.removeAt(from)
        if (from < insertAt) insertAt--
        tasks.add(insertAt, task)
        return true
    }

    /** First task that may start right now: QUEUED and its scheduled
     *  time (if any) has arrived, and not in [excludeIds]. */
    fun nextEligible(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ): TaskRecord? = tasks.firstOrNull {
        it.status == TaskStatus.QUEUED && it.id !in excludeIds && !it.netDeferred &&
            (it.scheduledAt <= 0 || it.scheduledAt <= now)
    }

    /** Earliest future scheduled start among QUEUED tasks, or 0. */
    fun nextScheduledAt(tasks: List<TaskRecord>, now: Long): Long =
        tasks.filter {
            it.status == TaskStatus.QUEUED && it.scheduledAt > now
        }.minOfOrNull { it.scheduledAt } ?: 0
}
