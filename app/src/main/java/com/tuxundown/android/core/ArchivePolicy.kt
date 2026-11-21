package com.tuxundown.android.core

/**
 * v2.4: auto-archive of old completed records. When the user
 * turns it on （设置 → 引导与数据）, a completed task whose
 * finish time is at least N days in the past has its RECORD
 * folded into the 已归档 filter on the home screen. The
 * downloaded file itself is never moved or deleted — archiving
 * is purely about list tidiness, and every archived record can
 * be restored to the normal tabs.
 *
 * Pure decisions, unit-tested; the sweep runs once per launch
 * (and immediately when the setting changes).
 */
object ArchivePolicy {

    const val DAY_MS = 86_400_000L

    /** Eligible = archiving on, task DONE, a real finish time,
     *  and that finish at least [days] full days ago. A task
     *  finished exactly N days ago counts (boundary inclusive). */
    fun shouldArchive(status: TaskStatus, finishedAt: Long, now: Long, days: Int): Boolean {
        if (days <= 0) return false
        if (status != TaskStatus.DONE) return false
        if (finishedAt <= 0) return false
        return now - finishedAt >= days * DAY_MS
    }

    /** Mark every eligible, not-yet-archived record; returns how
     *  many changed so the caller can persist only then. */
    fun sweep(tasks: List<TaskRecord>, now: Long, days: Int): Int {
        var changed = 0
        for (t in tasks) {
            if (!t.archived && shouldArchive(t.status, t.finishedAt, now, days)) {
                t.archived = true
                changed++
            }
        }
        return changed
    }
}
