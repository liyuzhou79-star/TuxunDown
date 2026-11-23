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
 *
 * v7.1 (audit round 56): anti-starvation AGING. The pre-v7.1
 * chain had no wait-time term, so a steady stream of higher-tier
 * tasks could starve a LOW task forever. Now a task's EFFECTIVE
 * tier (see [effectiveTier]) is its own priority tier, raised by
 * exactly one step — LOW to NORMAL — once its current continuous
 * QUEUED wait reaches [AGING_PROMOTE_MS]. The aging ring is
 * deliberately weak, and its bounds are part of the design:
 *   - It is CAPPED at NORMAL: [agingTier] never produces HIGH,
 *     so aging can never outrank a task the user (or the app)
 *     actually marked 高.
 *   - Strength order, strongest first: 插队 (jumped) > HIGH
 *     tier > aged-to-NORMAL (exactly tied with a plain NORMAL
 *     task, list order between them) > un-aged. Explicit user
 *     actions always outrank this automatic help.
 *   - ELIGIBILITY IS STILL JUDGED FIRST: aging never resurrects
 *     a net-parked / portal-parked / not-yet-due task — those
 *     are filtered out before any tier is computed.
 *   - An aged task gains NO jumped identity and NO rotation
 *     immunity; only the tier ring changes. Jumped, rotation
 *     and list order below the tier ring are untouched.
 * The wait clock is [TaskRecord.queuedAt] (persisted; stamped
 * at enqueue, at the net / portal parks, at the daily
 * reschedule and at interrupted-task recovery, and at creation
 * via TaskStore.add; cleared when the engine really starts).
 * The auto-retry backoff re-queue deliberately does NOT stamp
 * it: that task never left its claim, so its wait never broke.
 * Records with queuedAt == 0 (written before v7.1) fall back to
 * createdAt — that over-estimates the wait, never under, and
 * retires itself as records re-queue through the funnel.
 * KNOWN RESIDUAL (round 56 channel (2d), recorded, NOT fixed
 * here): within one tier the list order decides, and a NEW task
 * is inserted at the list HEAD, so same-tier order is newest
 * first, not arrival FIFO — a same-tier arrival stream can
 * still overtake an older task, and aging (which only lifts
 * LOW to NORMAL) does not reach that channel. TaskStore.add's
 * head insertion is the app's display / persistence
 * foundation and stays untouched; the mitigations are queue
 * position visibility and the user's explicit remedy （插队） —
 * explicit outranks automatic, as everywhere else here.
 */
object QueueOrder {

    /** Host of the task the drain most recently committed to
     *  starting, or null when nothing has been served yet in this
     *  process (the pick then falls back to plain list order —
     *  see [nextEligible]). Process-local, never persisted. */
    @Volatile
    var lastServedHost: String? = null
        private set

    /** v7.1: how long a LOW task must wait continuously in
     *  QUEUED before aging lifts it one step to NORMAL
     *  (30 minutes — far above the ~3-minute auto-retry backoff
     *  scale, far below the "user forgot it" scale). */
    const val AGING_PROMOTE_MS: Long = 1_800_000L

    /**
     * v7.1: when [t]'s current continuous QUEUED wait started,
     * for aging purposes: its [TaskRecord.queuedAt] stamp, or
     * [TaskRecord.createdAt] when the stamp is 0 (a pre-v7.1
     * record) — and never earlier than [TaskRecord.scheduledAt]:
     * time before a scheduled slot is the user's own
     * appointment, not queue starvation, so a scheduled task's
     * wait only accrues from the moment its slot arrives.
     */
    fun waitStart(t: TaskRecord): Long =
        maxOf(if (t.queuedAt > 0) t.queuedAt else t.createdAt, t.scheduledAt)

    /**
     * v7.1: the tier AGING alone gives [t] at [now]: NORMAL
     * when a LOW task's continuous wait (from [waitStart]) has
     * reached [AGING_PROMOTE_MS], otherwise the task's own tier
     * capped at NORMAL. The value is in {LOW, NORMAL} by
     * construction — it NEVER produces HIGH: a HIGH result here
     * could let a forgotten task outrank one the user actually
     * marked 高. (A HIGH task's own tier still wins through
     * [effectiveTier]'s max, from its priority, not from aging.)
     */
    fun agingTier(t: TaskRecord, now: Long): Int {
        val base = TaskPriority.clamp(t.priority)
        if (base == TaskPriority.LOW &&
            now - waitStart(t) >= AGING_PROMOTE_MS
        ) {
            return TaskPriority.NORMAL
        }
        return base.coerceAtMost(TaskPriority.NORMAL)
    }

    /** v7.1: the tier the pick actually judges [t] by at [now]:
     *  its own priority tier, or its aging tier when that is
     *  higher. Only ever raises LOW to NORMAL (see [agingTier]). */
    fun effectiveTier(t: TaskRecord, now: Long): Int =
        maxOf(TaskPriority.clamp(t.priority), agingTier(t, now))

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
     * goes first (see TaskPriority); list order breaks ties
     * within one tier (list order is the queue order — for new
     * tasks that means newest first, since creation inserts at
     * the list head; it is NOT arrival FIFO), and 插队's
     * physical move still matters. Eligibility is judged BEFORE
     * priority: a scheduled 高 task never blocks a due 中 task
     * behind it.
     *
     * v4.8 pick order (eligibility — status / excludeIds /
     * netDeferred / portalDeferred / scheduled time — is still
     * judged first, before everything):
     *   1. highest tier among the eligible — v7.1: the EFFECTIVE
     *      tier ([effectiveTier]: own tier, with a LOW task
     *      aged to NORMAL after [AGING_PROMOTE_MS] of continuous
     *      waiting; never aged to HIGH);
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
    ): TaskRecord? = pickWithServed(tasks, now, excludeIds, lastServedHost)

    /**
     * The ONE pick core behind both [nextEligible] and
     * [queuePositions]: eligibility, effective tier (with the
     * v7.1 aging ring), jumped, then host rotation against
     * [servedHost], then list order — exactly the chain
     * documented on [nextEligible]. It is a pure function of
     * its arguments: the rotation memory is PASSED IN, never
     * read from or written to [lastServedHost] here, so a
     * caller can simulate a whole drain locally (see
     * [queuePositions]) without disturbing the real memory.
     * [nextEligible] is this function with the real memory.
     */
    internal fun pickWithServed(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String>,
        servedHost: String?,
    ): TaskRecord? {
        val eligible = tasks.filter { t ->
            t.status == TaskStatus.QUEUED && t.id !in excludeIds && !t.netDeferred &&
                !t.portalDeferred &&
                !(t.scheduledAt > 0 && t.scheduledAt > now)
        }
        if (eligible.isEmpty()) return null
        val topTier = eligible.maxOf { effectiveTier(it, now) }
        val tier = eligible.filter { effectiveTier(it, now) == topTier }
        val jumped = tier.firstOrNull { it.jumpedAt > 0 }
        if (jumped != null) return jumped
        if (servedHost != null) {
            val rotated = tier.firstOrNull { HostMemory.hostOf(it.url) != servedHost }
            if (rotated != null) return rotated
        }
        return tier.first()
    }

    /**
     * v7.1 (audit round 56, Step C): where each currently
     * pickable QUEUED task stands in line — task id to its
     * 1-based queue position — computed by SIMULATING a drain
     * with the very same pick core the real drain uses
     * ([pickWithServed], the core behind [nextEligible]):
     * start from the current [lastServedHost], repeatedly
     * pick, record the next position, update a LOCAL served
     * host with exactly [noteServed]'s rule
     * (HostMemory.hostOf of the picked task's url), and remove
     * the picked task from the local candidate list. There is
     * no second, approximate ordering anywhere: position 1 is
     * by construction what [nextEligible] would return for
     * the same arguments.
     *
     * Only tasks the simulation actually picks get a position.
     * A QUEUED task that is not eligible right now — net-parked
     * (netDeferred), portal-parked (portalDeferred) or its
     * scheduledAt has not arrived — is never picked, so it has
     * NO position here and its card keeps its existing status
     * wording; no number is faked for it. A charge-parked task
     * (chargeDeferred) IS inside the eligibility gate (the
     * charge gate is a post-pick gate in the drain), so the
     * simulation picks it and it does get a position.
     *
     * [excludeIds] caliber: the caller must pass the same
     * exclusion caliber the drain passes to [nextEligible].
     * The drain passes its running-thread ids; the task list
     * UI passes the EngineClaims claim set, read per task via
     * DownloadCenter.isClaimed (a claim is held for the whole
     * runOne invocation, so this also excludes a task sitting
     * in its auto-retry backoff window — status flipped back
     * to QUEUED but still claimed — exactly as the drain's
     * running set excludes it). Excluded tasks get no position
     * and occupy none. Residual difference, stated honestly:
     * the claim set is a snapshot taken when the UI computes
     * the positions, while the drain re-reads its own running
     * set on every pass, so a claim taken or released between
     * the snapshot and a drain pass can leave a displayed
     * position briefly one off until the next UI refresh.
     *
     * No side effects: [lastServedHost] is only read (once, as
     * the simulation's starting memory), the input list is
     * copied before anything is removed from it, and no task
     * record is touched.
     */
    fun queuePositions(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ): Map<String, Int> {
        val remaining = tasks.toMutableList()
        val positions = LinkedHashMap<String, Int>()
        var servedHost = lastServedHost
        var nextPosition = 1
        while (true) {
            val picked = pickWithServed(remaining, now, excludeIds, servedHost) ?: break
            positions[picked.id] = nextPosition++
            servedHost = HostMemory.hostOf(picked.url)
            remaining.remove(picked)
        }
        return positions
    }

    /** Earliest future scheduled start among QUEUED tasks, or 0. */
    fun nextScheduledAt(tasks: List<TaskRecord>, now: Long): Long =
        tasks.filter {
            it.status == TaskStatus.QUEUED && it.scheduledAt > now
        }.minOfOrNull { it.scheduledAt } ?: 0
}
