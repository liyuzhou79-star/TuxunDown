package com.tuxundown.android.core

/**
 * Pure queue-order helpers (v1.4), kept free of Android/Context so
 * they are unit-testable. The service drains the queue by taking the
 * first QUEUED record in list order, so list order IS queue order.
 *
 * v4.8: host rotation memory ([lastServedHost]) is PROCESS-LOCAL
 * state on purpose and is deliberately never persisted: a restart
 * just restarts fairness from list order, and backups never carry
 * queue politics. Only the drain loop writes it (via [noteServed],
 * at the moment it commits to starting a task); every other caller
 * of [nextEligible] only reads, so all readers predict the same
 * pick the drain will make.
 */
object QueueOrder {

    /** Host of the task the drain most recently committed to
     *  starting, or null when nothing has been served yet in this
     *  process (the pick then falls back to plain list order —
     *  see [nextEligible]). Process-local, never persisted. */
    @Volatile
    var lastServedHost: String? = null
        private set

    /** Record that [task] was actually served (the drain loop
     *  calls this only once it has committed to starting the
     *  task — a task a gate turned away was never served). */
    fun noteServed(task: TaskRecord) {
        lastServedHost = HostMemory.hostOf(task.url)
    }

    /** Forget the rotation memory (tests, and any future reset). */
    fun clearServedMemory() {
        lastServedHost = null
    }

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

    /**
     * Next task that may start right now: QUEUED, its scheduled
     * time (if any) has arrived, and not in [excludeIds].
     *
     * v3.1: among the eligible tasks the highest priority tier
     * goes first (see TaskPriority); list order breaks ties, so
     * within one tier the queue stays FIFO and 插队's physical
     * move still matters. Eligibility is judged BEFORE priority:
     * a scheduled 高 task never blocks a due 中 task behind it.
     *
     * v4.8 pick order (eligibility — status / excludeIds /
     * netDeferred / portalDeferred / scheduled time — is still
     * judged first, before everything):
     *   1. highest tier (TaskPriority.clamp) among the eligible;
     *   2. inside that tier, jumped tasks (jumpedAt > 0) first,
     *      in list order (the last jump sits physically first);
     *   3. among the rest, host rotation: when [lastServedHost]
     *      is known and some candidate's host differs from it,
     *      the first such candidate; otherwise list order.
     * Hosts come from HostMemory.hostOf (the same keying the
     * learning data uses; a blank host is one shared bucket).
     *
     * Equivalence: with lastServedHost == null and no jumpedAt
     * set, this returns the first task of the highest tier in
     * list order — exactly what the pre-v4.8 scan returned (it
     * kept the first task whose tier was strictly higher than
     * every earlier one, i.e. the first occurrence of the
     * maximum tier).
     */
    fun nextEligible(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ): TaskRecord? {
        val eligible = tasks.filter { t ->
            t.status == TaskStatus.QUEUED && t.id !in excludeIds && !t.netDeferred &&
                !t.portalDeferred &&
                !(t.scheduledAt > 0 && t.scheduledAt > now)
        }
        if (eligible.isEmpty()) return null
        val topTier = eligible.maxOf { TaskPriority.clamp(it.priority) }
        val tier = eligible.filter { TaskPriority.clamp(it.priority) == topTier }
        val jumped = tier.firstOrNull { it.jumpedAt > 0 }
        if (jumped != null) return jumped
        val served = lastServedHost
        if (served != null) {
            val rotated = tier.firstOrNull { HostMemory.hostOf(it.url) != served }
            if (rotated != null) return rotated
        }
        return tier.first()
    }

    /** Earliest future scheduled start among QUEUED tasks, or 0. */
    fun nextScheduledAt(tasks: List<TaskRecord>, now: Long): Long =
        tasks.filter {
            it.status == TaskStatus.QUEUED && it.scheduledAt > now
        }.minOfOrNull { it.scheduledAt } ?: 0
}
