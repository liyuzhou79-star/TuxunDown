package com.tuxundown.android.core

/**
 * 全部暂停 / 全部继续 decision logic (v2.7) — pure, unit-tested.
 *
 * One implementation shared by the home summary card's button,
 * the quick-settings tile and (v4.1) the home-screen widget's
 * button, so the three surfaces can never disagree about what
 * a tap would do. The semantics mirror the
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

    /**
     * v4.1: the widget button's label for an [action], or null
     * when the button must hide (NONE — nothing to toggle).
     * The widget has room for the bare verb only (the tile and
     * the card add counts around the same words); keeping the
     * label decision here means the widget's wording and its
     * visibility can never drift from the shared semantics.
     */
    fun buttonLabel(action: Action): String? = when (action) {
        Action.PAUSE_ALL -> "全部暂停"
        Action.RESUME_ALL -> "全部继续"
        Action.NONE -> null
    }
}
