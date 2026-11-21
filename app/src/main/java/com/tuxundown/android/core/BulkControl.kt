package com.tuxundown.android.core

/**
 * 全部暂停 / 全部继续 decision logic (v2.7) — pure, unit-tested.
 *
 * One implementation shared by the home summary card's button
 * and the quick-settings tile, so the two surfaces can never
 * disagree about what a tap would do. The semantics mirror the
 * multi-select bar (v1.4) exactly:
 * - If anything is RUNNING or QUEUED, the offered action is
 *   PAUSE_ALL (pausing is the urgent direction; a mixed list
 *   pauses first, exactly like selecting all and tapping 暂停）.
 * - Else, if anything is PAUSED or FAILED, the action is
 *   RESUME_ALL （继续 / 重试 go through the same enqueue path
 *   as a card's play button, guards included).
 * - Else there is nothing to toggle: NONE.
 */
object BulkControl {

    enum class Action { PAUSE_ALL, RESUME_ALL, NONE }

    fun action(tasks: List<TaskRecord>): Action = when {
        tasks.any {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        } -> Action.PAUSE_ALL
        tasks.any {
            it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED
        } -> Action.RESUME_ALL
        else -> Action.NONE
    }

    /** Tasks a 全部暂停 tap would pause. */
    fun pauseTargets(tasks: List<TaskRecord>): List<TaskRecord> =
        tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }

    /** Tasks a 全部继续 tap would re-queue. */
    fun resumeTargets(tasks: List<TaskRecord>): List<TaskRecord> =
        tasks.filter {
            it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED
        }

    /** In-flight count for the tile subtitle （进行中 N 个）. */
    fun activeCount(tasks: List<TaskRecord>): Int = pauseTargets(tasks).size

    /** Paused/failed count for the tile subtitle. */
    fun resumableCount(tasks: List<TaskRecord>): Int = resumeTargets(tasks).size
}
