package com.tuxundown.android.core

/**
 * v2.6: which tasks count as "interrupted mid-transfer" after a
 * process death or a reboot.
 *
 * The marker is [TaskRecord.wasRunning]: tasks.json persists a
 * running task's status as RUNNING (the engine saves while it
 * runs), and [TaskStore.deserialize] maps that to PAUSED on load
 * — the standing rule since v1.2 — while setting wasRunning so
 * the recovery paths can still tell "the system stopped this
 * one" apart from "the USER paused this one". Only the former
 * are ever re-queued automatically; a user pause is an order,
 * not an accident, and recovery must never override it.
 *
 * Pure on purpose: the boot receiver and the service's sticky
 * restart both consume exactly this verdict.
 */
object Recovery {

    /** Tasks stopped mid-transfer by the system's hand, in list order. */
    fun interrupted(tasks: List<TaskRecord>): List<TaskRecord> =
        tasks.filter { it.wasRunning && it.status == TaskStatus.PAUSED }

    /** True when anything is waiting to be picked up by the drain
     *  (used to decide whether a boot kick is worth starting the
     *  service for at all). */
    fun anyQueued(tasks: List<TaskRecord>): Boolean =
        tasks.any { it.status == TaskStatus.QUEUED }
}
