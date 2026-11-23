package com.tuxundown.android.core

/**
 * Old-record cleanup (v3.4) — pure selection logic, unit-tested.
 *
 * Settings → 引导与数据 → 清理旧任务记录 deletes DONE / FAILED
 * task RECORDS whose terminal time is at least N days ago
 * (30 / 90 / 180). Records only, by the same rule archiving
 * follows: downloaded files are NEVER deleted by this, and an
 * unfinished .part is not deleted either (a leftover .part is
 * the orphan cleaner's job, one row down in the same settings
 * group). QUEUED / RUNNING / PAUSED records are never eligible:
 * a paused download is a download in progress, not clutter.
 *
 * The destructive half (backup-first + bulk removal) lives in
 * DownloadCenter.cleanupOldRecords; this object only decides
 * WHO is eligible, so the rule is testable without Android.
 */
object RecordCleanup {

    const val DAY_MS = ArchivePolicy.DAY_MS

    /**
     * When this record reached its terminal state, as best the
     * data knows. finishedAt is stamped at completion — and
     * since v3.4 also when a run fails (DownloadEngine's
     * finally). Records written by older versions may carry
     * finishedAt = 0 for a failure; for those, the creation
     * time is the only honest fallback (it UNDERSTATES the
     * failure's age, so such a record is cleaned later than
     * its real failure date, never earlier).
     */
    fun terminalAt(task: TaskRecord): Long =
        if (task.finishedAt > 0) task.finishedAt else task.createdAt

    /** Eligible = DONE or FAILED, with a real terminal time at
     *  least [days] full days in the past (boundary inclusive,
     *  the same convention as ArchivePolicy.shouldArchive). */
    fun eligible(task: TaskRecord, now: Long, days: Int): Boolean {
        if (days <= 0) return false
        if (task.status != TaskStatus.DONE && task.status != TaskStatus.FAILED) {
            return false
        }
        val at = terminalAt(task)
        if (at <= 0) return false
        return now - at >= days * DAY_MS
    }

    /** All eligible records, in list order. */
    fun select(tasks: List<TaskRecord>, now: Long, days: Int): List<TaskRecord> =
        tasks.filter { eligible(it, now, days) }
}
