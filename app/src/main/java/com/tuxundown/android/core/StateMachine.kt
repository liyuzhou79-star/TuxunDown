package com.tuxundown.android.core

/**
 * v7.4 (audit round 59, Step B): the task state machine, written
 * down as ONE pure table plus the guard the commit points route
 * through.
 *
 * The audit mapped every status write in the repo (47 points).
 * They split into two kinds:
 *
 * - COMMIT points — the places that DECIDE a task's status:
 *   the enqueue funnel, pauseTask, recoverInterrupted, the
 *   drain's run claim, the service's park / backoff re-entries,
 *   the finalize hand-off and its failure flip, the daily
 *   re-queue, and the load-time normalization in TaskStore.
 *   Commits route through [commit] / [canEnqueue] here, so an
 *   illegal transition is refused (and counted, see
 *   [IllegalTransitions]) instead of silently landing.
 *
 * - MIRROR points — the engine's in-run record mutations. The
 *   engine writes the status it is about to RETURN, and the
 *   service decides completion from that return value (audit
 *   C9), never from the shared field. Mirrors do not call the
 *   guard (they are mid-run bookkeeping, not decisions), but
 *   every value they can write is a legal cell of this table
 *   from RUNNING, which the full-grid test pins.
 *
 * "Archived" is deliberately NOT here: completion auto-archive
 * flips a visibility flag on DONE records (ArchivePolicy), it
 * never changes status — the ARCHIVE event below is legal only
 * from DONE and maps DONE -> DONE for exactly that reason.
 *
 * Pure JVM logic, no Android, no I/O — unit-testable like
 * EngineClaims / FinalizeGate.
 */
enum class StateEvent {
    START,            // 开始： drain claims a QUEUED task and runs it
    PAUSE,            // 暂停： user pause (card / notification / import parking)
    RESUME,           // 继续： manual (re-)queue of a paused / already-queued task
    CANCEL,           // 取消： cancel mark only — status itself does not change
    DELETE,           // 删除： record removal — legal from every status, no target status
    RETRY,            // 重试： failed (or daily-finished) task re-queues
    NET_PARK,         // 断网停泊： outage failure parks back into the queue
    CHARGE_PARK,      // 充电停泊： charge gate parks a QUEUED task in place
    PORTAL_PARK,      // 门户停泊： captive-portal failure parks back into the queue
    BACKOFF_REQUEUE,  // 退避： runOne's own retry loop re-queues after the sleep
    FINALIZE_SUCCESS, // 收尾成功： the run's bytes are complete
    FINALIZE_FAILURE, // 收尾失败： run / finalize failure lands FAILED
    DEATH_RECOVERY,   // 进程死亡恢复： load normalization + wasRunning re-queue
    ARCHIVE,          // 归档： visibility only — DONE stays DONE
}

object StateMachine {

    /**
     * The legal (from, event) -> to cells. Anything absent is
     * illegal. CANCEL and DELETE are legal from every status and
     * map to the SAME status (they never move a task; cancel only
     * marks, delete removes the record outright).
     */
    private val legal: Map<Pair<TaskStatus, StateEvent>, TaskStatus> = buildMap {
        fun put(from: TaskStatus, event: StateEvent, to: TaskStatus) {
            this[from to event] = to
        }
        // 开始
        put(TaskStatus.QUEUED, StateEvent.START, TaskStatus.RUNNING)
        // 暂停 (QUEUED->PAUSED is also the import-parking cell)
        put(TaskStatus.QUEUED, StateEvent.PAUSE, TaskStatus.PAUSED)
        put(TaskStatus.RUNNING, StateEvent.PAUSE, TaskStatus.PAUSED)
        // 继续 (QUEUED->QUEUED is the funnel's legal re-entry:
        // multi-select resume and the wifi-only branch depend on it)
        put(TaskStatus.PAUSED, StateEvent.RESUME, TaskStatus.QUEUED)
        put(TaskStatus.QUEUED, StateEvent.RESUME, TaskStatus.QUEUED)
        // 重试 (DONE->QUEUED exists ONLY here and ONLY for the
        // daily re-queue, which never passes the enqueue funnel —
        // the funnel's own gate [canEnqueue] refuses DONE)
        put(TaskStatus.FAILED, StateEvent.RETRY, TaskStatus.QUEUED)
        put(TaskStatus.DONE, StateEvent.RETRY, TaskStatus.QUEUED)
        // 停泊三兄弟 + 退避： a failed run re-enters the queue;
        // the charge park is a QUEUED task staying QUEUED in place
        put(TaskStatus.FAILED, StateEvent.NET_PARK, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.PORTAL_PARK, TaskStatus.QUEUED)
        put(TaskStatus.QUEUED, StateEvent.CHARGE_PARK, TaskStatus.QUEUED)
        put(TaskStatus.FAILED, StateEvent.BACKOFF_REQUEUE, TaskStatus.QUEUED)
        // 收尾 (DONE->FAILED is the finalize lane's TargetGone
        // flip: status was set DONE at the hand-off, finalize
        // then proved the bytes gone)
        put(TaskStatus.RUNNING, StateEvent.FINALIZE_SUCCESS, TaskStatus.DONE)
        put(TaskStatus.RUNNING, StateEvent.FINALIZE_FAILURE, TaskStatus.FAILED)
        put(TaskStatus.DONE, StateEvent.FINALIZE_FAILURE, TaskStatus.FAILED)
        // 进程死亡恢复： persisted RUNNING parks to PAUSED at
        // load; the wasRunning PAUSED records re-queue
        put(TaskStatus.RUNNING, StateEvent.DEATH_RECOVERY, TaskStatus.PAUSED)
        put(TaskStatus.PAUSED, StateEvent.DEATH_RECOVERY, TaskStatus.QUEUED)
        // 归档： DONE only, status unchanged
        put(TaskStatus.DONE, StateEvent.ARCHIVE, TaskStatus.DONE)
        // 取消 / 删除： every status, status unchanged
        for (s in TaskStatus.values()) {
            put(s, StateEvent.CANCEL, s)
            put(s, StateEvent.DELETE, s)
        }
    }

    /** The status [event] legally moves [from] to, or null when illegal. */
    fun target(from: TaskStatus, event: StateEvent): TaskStatus? =
        legal[from to event]

    fun isLegal(from: TaskStatus, event: StateEvent): Boolean =
        target(from, event) != null

    /**
     * The enqueue funnel's gate (audit C-1): a manual (re-)queue
     * accepts only QUEUED (re-entry), PAUSED and FAILED. RUNNING
     * and DONE are refused — a running task already has an owner,
     * and a done task's only re-queue is the daily path, which
     * does not pass through the funnel.
     */
    fun canEnqueue(from: TaskStatus): Boolean =
        from == TaskStatus.QUEUED || from == TaskStatus.PAUSED ||
            from == TaskStatus.FAILED

    /**
     * Route a COMMIT through the guard: on a legal cell the
     * task's status moves and true returns; on an illegal cell
     * nothing changes, the refusal is counted in
     * [IllegalTransitions] under [source], and false returns.
     * Never throws — a refusal must not crash a UI action.
     */
    fun commit(task: TaskRecord, event: StateEvent, source: String): Boolean {
        val to = target(task.status, event)
        if (to == null) {
            IllegalTransitions.record(task.id, task.status, event, source)
            return false
        }
        task.status = to
        return true
    }
}

/**
 * v7.4 (audit round 59, Step B2): the process-local count of
 * refused (illegal) transitions, plus the most recent one.
 * Deliberately carries ONLY the task id and the from/event/
 * source triple — never a URL or file name — so Step C's
 * diagnostics row can show it without leaking link data.
 * Process-local on purpose: it answers "did THIS process see a
 * guard refusal", and it dies with the process like the claims.
 */
object IllegalTransitions {

    data class LastIllegal(
        val taskId: String,
        val from: TaskStatus,
        val event: StateEvent,
        val source: String,
    )

    @Volatile
    var count: Int = 0
        private set

    @Volatile
    var last: LastIllegal? = null
        private set

    @Synchronized
    fun record(taskId: String, from: TaskStatus, event: StateEvent, source: String) {
        count++
        last = LastIllegal(taskId, from, event, source)
    }

    /** Test-only reset (production code never calls this). */
    @Synchronized
    fun resetForTest() {
        count = 0
        last = null
    }
}

/**
 * v7.4 (audit round 59, Step B5 / verdict C-2a): the pure half
 * of the orphaned-finalize detection.
 *
 * The bug: DownloadService persists DONE BEFORE the finalize
 * lane renames the .part (the hand-off window). Death in that
 * window leaves a DONE record whose savedUri is empty while
 * the bytes still sit in the download folder under a ".part"
 * name — and no path ever re-ran finalize for a DONE record.
 *
 * A record is rescuable only when EVERY proof holds: status
 * DONE, savedUri still empty, a target Uri was persisted (the
 * .part's address — without it there is nothing provable), the
 * entry at that Uri provably EXISTS, and its live display name
 * still ends in ".part" (a name without the suffix means
 * finalize actually completed and this is a different story).
 * Any doubt -> false: a missing .part is reconcile / ghost
 * territory and must not be touched here.
 */
object FinalizeRescue {

    fun needsRescue(
        status: TaskStatus,
        savedUri: String,
        targetUri: String,
        partEntryExists: Boolean,
        partDisplayName: String?,
    ): Boolean {
        if (status != TaskStatus.DONE) return false
        if (savedUri.isNotBlank()) return false
        if (targetUri.isBlank()) return false
        if (!partEntryExists) return false
        val name = partDisplayName ?: return false
        return name.endsWith(".part", ignoreCase = true)
    }
}
