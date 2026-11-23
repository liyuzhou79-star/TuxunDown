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
     * the same arguments. (v7.7: the simulation below computes
     * that same sequence in O(n + picks) instead of re-running
     * the pick core per pick — see the implementation comment;
     * a randomized test pins the two sequences identical.)
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
        // v7.7 (audit round 62, B-1): this used to re-run
        // [pickWithServed] over the whole remaining list for
        // EVERY pick — O(picks x n), ~6.8M element visits plus
        // ~4500 list allocations at 1500 all-QUEUED. The pick
        // chain's inputs never change during a simulation —
        // eligibility, effective tier, jumped and host are all
        // fixed per task at [now], and a pick only REMOVES the
        // picked task — so the eligible set is filtered ONCE
        // and bucketed by effective tier (a small integer,
        // TaskPriority's -1 / 0 / 1):
        //   - picks always drain the highest remaining tier
        //     first (the tier ring precedes jumped and
        //     rotation), so buckets drain strictly in
        //     descending tier order, carrying the local served
        //     host across buckets;
        //   - inside one tier, jumped tasks go first, in list
        //     order (the jumped ring precedes rotation on
        //     every pick, so they drain consecutively);
        //   - the rest is a rotation simulation over RUNS of
        //     equal host: a pick is the first run's head when
        //     its host differs from the served host (or none
        //     was served yet, or it is the only run), and the
        //     second run's head when the first run's host IS
        //     the served host (the whole first run is then
        //     rotation-ineligible, and the second run's head
        //     is the first element that differs). Runs are an
        //     index-linked list, so each pick is O(1); a task
        //     changes runs at most once (when an emptied second
        //     run lets its same-host neighbours merge), which
        //     keeps the whole simulation O(n + picks).
        // Equivalence with the old per-pick [pickWithServed]
        // simulation is pinned by a randomized test in
        // QueuePositionsTest. [nextEligible] / [pickWithServed]
        // themselves are UNCHANGED — the drain never takes
        // this path.
        val byTier = HashMap<Int, MutableList<TaskRecord>>()
        for (t in tasks) {
            if (t.status == TaskStatus.QUEUED && t.id !in excludeIds && !t.netDeferred &&
                !t.portalDeferred && !(t.scheduledAt > 0 && t.scheduledAt > now)
            ) {
                byTier.getOrPut(effectiveTier(t, now)) { mutableListOf() }.add(t)
            }
        }
        if (byTier.isEmpty()) return emptyMap()
        val positions = LinkedHashMap<String, Int>()
        var servedHost = lastServedHost
        var nextPosition = 1
        for (tier in byTier.keys.sortedDescending()) {
            val bucket = byTier.getValue(tier)
            val plain = ArrayList<TaskRecord>(bucket.size)
            for (t in bucket) {
                if (t.jumpedAt > 0) {
                    positions[t.id] = nextPosition++
                    servedHost = HostMemory.hostOf(t.url)
                } else {
                    plain.add(t)
                }
            }
            // Rotation over runs of equal host, index-linked.
            class Run(
                val host: String,
                val items: java.util.ArrayDeque<TaskRecord>,
                var next: Int = -1,
            )
            val runs = ArrayList<Run>()
            for (t in plain) {
                val host = HostMemory.hostOf(t.url)
                val last = runs.lastOrNull()
                if (last != null && last.host == host) {
                    last.items.addLast(t)
                } else {
                    if (last != null) last.next = runs.size
                    runs.add(Run(host, java.util.ArrayDeque<TaskRecord>().apply { addLast(t) }))
                }
            }
            var head = if (runs.isEmpty()) -1 else 0
            while (head >= 0) {
                val first = runs[head]
                val secondIdx = first.next
                val pickSecond =
                    servedHost != null && first.host == servedHost && secondIdx >= 0
                val run = if (pickSecond) runs[secondIdx] else first
                val picked = run.items.removeFirst()
                positions[picked.id] = nextPosition++
                servedHost = run.host
                if (run.items.isEmpty()) {
                    if (pickSecond) {
                        first.next = run.next
                        // An emptied second run can leave its
                        // neighbours sharing one host (A B A):
                        // for the next "first host that differs"
                        // scan they are ONE run, so merge them.
                        val newSecond = first.next
                        if (newSecond >= 0 && runs[newSecond].host == first.host) {
                            first.items.addAll(runs[newSecond].items)
                            first.next = runs[newSecond].next
                        }
                    } else {
                        head = run.next
                    }
                }
            }
        }
        return positions
    }

    /** Earliest future scheduled start among QUEUED tasks, or 0. */
    fun nextScheduledAt(tasks: List<TaskRecord>, now: Long): Long =
        tasks.filter {
            it.status == TaskStatus.QUEUED && it.scheduledAt > now
        }.minOfOrNull { it.scheduledAt } ?: 0

    /**
     * v7.6 (audit round 61, Step B-6): a cheap structural
     * signature of EVERY input [queuePositions] (through
     * [pickWithServed]) actually reads, in list order:
     * - the rotation state itself, [lastServedHost] — read
     *   once by queuePositions as the simulation's starting
     *   memory, and cleanly readable here because it is a
     *   public val on this object (that readability is what
     *   makes this memo possible at all; had it been private
     *   drain state, this item would have been deferred);
     * - [excludeIds], as a sorted join (set order is not an
     *   input — membership is);
     * - per task, in list order (list order IS an input —
     *   the simulation removes picks from a copy of this
     *   list): id (exclusion + result key), status
     *   (eligibility), [effectiveTier] (the tier ring — this
     *   one Int is the COMPLETE footprint of priority /
     *   queuedAt / createdAt / scheduledAt / [now] on the
     *   tier judgement, including the v7.1 aging crossing),
     *   jumpedAt (the jumped ring), netDeferred /
     *   portalDeferred (eligibility), the not-yet-due
     *   boolean (eligibility's ONLY use of scheduledAt
     *   besides the tier footprint above), and the task's
     *   host via HostMemory.hostOf (the rotation ring's
     *   only use of the url).
     * Two calls with equal signatures therefore provably run
     * the identical simulation and return equal maps — the
     * memo below never serves a stale position. Fields the
     * simulation never reads (downloaded bytes, note, error,
     * sizes…) are deliberately absent, so a RUNNING task's
     * per-second progress does NOT bust the memo.
     */
    fun queuePositionsSignature(
        tasks: List<TaskRecord>,
        now: Long,
        excludeIds: Set<String> = emptySet(),
    ): List<Any?> {
        val sig = ArrayList<Any?>(tasks.size * 8 + 2)
        sig.add(lastServedHost)
        sig.add(excludeIds.sorted().joinToString(","))
        for (t in tasks) {
            sig.add(t.id)
            sig.add(t.status)
            sig.add(effectiveTier(t, now))
            sig.add(t.jumpedAt)
            sig.add(t.netDeferred)
            sig.add(t.portalDeferred)
            sig.add(t.scheduledAt > 0 && t.scheduledAt > now)
            sig.add(HostMemory.hostOf(t.url))
        }
        return sig
    }

    /**
     * v7.6 (Step B-6): a single-entry memo over
     * [queuePositions], keyed by [queuePositionsSignature].
     * The UI recomputes positions on every live-tick beat;
     * the simulation is O(n + picks) since v7.7 while the
     * signature is one O(n) pass with smaller constants, so beats where nothing
     * queue-relevant changed (the common case while a
     * download runs) skip the simulation entirely. NOT
     * thread-safe by design: it exists for the single UI
     * composition thread that owns the one instance; the
     * drain never uses it (it calls [nextEligible] directly,
     * exactly as before — this memo cannot delay or alter a
     * real pick).
     */
    class QueuePositionsMemo {
        private var lastSignature: List<Any?>? = null
        private var lastResult: Map<String, Int> = emptyMap()

        fun positions(
            tasks: List<TaskRecord>,
            now: Long,
            excludeIds: Set<String> = emptySet(),
        ): Map<String, Int> {
            val sig = queuePositionsSignature(tasks, now, excludeIds)
            if (sig == lastSignature) return lastResult
            val result = queuePositions(tasks, now, excludeIds)
            lastSignature = sig
            lastResult = result
            return result
        }
    }
}
