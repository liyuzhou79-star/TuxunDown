package com.tuxundown.android.core

import java.util.concurrent.ConcurrentHashMap

/**
 * v4.4 (finalize pipeline): the bookkeeping behind the serial
 * finalize lane in DownloadService.
 *
 * Until v4.3, finalizeFinished ran INLINE on the download thread:
 * the rename, the whole-file hash verification and the zip
 * extraction all held a maxConcurrent slot, so the next queued
 * task waited out a multi-GB hash / a big unzip before it could
 * start. Now the transfer thread hands a DONE task to a serial
 * lane (disk-bound work, one at a time) and frees its download
 * slot at once. This class is the lane's pure accounting plus
 * the two decisions the rest of the service must get right:
 *
 * - drain exit: the drain loop may only let the service stop
 *   when nothing is queued, nothing is deferred AND nothing is
 *   waiting on / running in the finalize lane — stopSelf with a
 *   finalize in flight would kill an extract mid-file.
 * - completion side effects: the completion notification, the
 *   statistics counter, the feedback and the user's completion
 *   auto-action may fire only when the task's record still
 *   exists at the end of finalize. A delete that landed while
 *   finalize ran (the record is gone) must not be followed by a
 *   "download complete" celebration for a card the user already
 *   removed.
 *
 * Plain JVM logic (a concurrent set + pure verdicts) so it is
 * unit-testable, in the shape of EngineClaims.
 */
class FinalizeGate {

    private val pending = ConcurrentHashMap.newKeySet<String>()

    /**
     * Mark [taskId] as handed to the finalize lane. True when
     * this call took the mark; false when the task is already
     * pending (a duplicate hand-off — the caller keeps the work
     * inline rather than queueing it twice).
     */
    fun submit(taskId: String): Boolean = pending.add(taskId)

    /** The lane finished this task (whatever the outcome). */
    fun complete(taskId: String) {
        pending.remove(taskId)
    }

    fun isPending(taskId: String): Boolean = pending.contains(taskId)

    fun pendingCount(): Int = pending.size

    companion object {
        /**
         * v4.6 (audit round 31): is any QUEUED task parked —
         * waiting on a condition only the live drain can notice
         * returning (network back, portal login done, power
         * plugged in)? ALL THREE park flags count. The drain
         * used to inline this verdict counting only netDeferred
         * and portalDeferred; a charge-only park was kept alive
         * solely by the charge gate branch re-deriving its
         * condition from a SECOND live battery read — and when
         * that read disagreed with the start loop's first read
         * (power landed between the two), the drain fell
         * through to stopSelf with the task still QUEUED and
         * parked, and no power-connected listener exists
         * anywhere to restart the service. Judging the park
         * FLAGS here makes the exit accounting agree with the
         * parks the service actually set.
         */
        fun anyParked(tasks: List<TaskRecord>): Boolean =
            tasks.any {
                (it.netDeferred || it.portalDeferred || it.chargeDeferred) &&
                    it.status == TaskStatus.QUEUED
            }

        /**
         * The drain loop's exit verdict, consulted when no
         * engine thread is running: the service may stop only
         * when there is no queued work, no deferred (parked)
         * work and no finalize work outstanding.
         */
        fun drainMayExit(
            anyQueued: Boolean,
            anyDeferred: Boolean,
            pendingFinalize: Int,
        ): Boolean = !anyQueued && !anyDeferred && pendingFinalize <= 0

        /**
         * Completion side effects (notify / record / feedback /
         * auto-action) fire only for a task whose record still
         * exists when finalize reaches them.
         */
        fun mayFireCompletionSideEffects(recordExists: Boolean): Boolean = recordExists
    }
}
