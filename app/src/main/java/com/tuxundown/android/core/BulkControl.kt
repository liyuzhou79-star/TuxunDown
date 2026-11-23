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

    /** The PAUSED half of the resumable set. */
    fun pausedCount(tasks: List<TaskRecord>): Int =
        tasks.count { it.status == TaskStatus.PAUSED }

    /** The FAILED half of the resumable set. */
    fun failedCount(tasks: List<TaskRecord>): Int =
        tasks.count { it.status == TaskStatus.FAILED }

    /**
     * v5.3 (audit round 38): the tile's RESUME_ALL subtitle.
     * It used to read 「已暂停 N 个」 with N = PAUSED + FAILED —
     * one name covering two states, while the task card calls
     * those same tasks 已暂停 and 下载失败 respectively. The
     * tap still resumes/re-queues both halves (that is what
     * 全部继续 means everywhere), but the subtitle now names
     * each half with the card's own words, so the count a user
     * can check against the list adds up either way.
     */
    fun resumeSubtitle(tasks: List<TaskRecord>): String {
        val paused = pausedCount(tasks)
        val failed = failedCount(tasks)
        return when {
            paused > 0 && failed > 0 ->
                "已暂停 $paused 个 · 失败 $failed 个 · 点按全部继续"
            failed > 0 -> "失败 $failed 个 · 点按全部继续"
            else -> "已暂停 $paused 个 · 点按全部继续"
        }
    }

    /**
     * v5.3 (audit round 38): the summary card's all-stopped
     * header — the same correction as [resumeSubtitle] for the
     * card's own surface. 「任务都暂停了」 is only said when
     * every resumable task really is user-paused; a failed
     * task is never relabelled as paused just because the
     * same button would retry it.
     */
    fun stoppedTitle(tasks: List<TaskRecord>): String = when {
        pausedCount(tasks) > 0 && failedCount(tasks) > 0 -> "任务都停下了"
        failedCount(tasks) > 0 -> "任务都失败了"
        else -> "任务都暂停了"
    }

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
