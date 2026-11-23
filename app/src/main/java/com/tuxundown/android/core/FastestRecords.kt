package com.tuxundown.android.core

/**
 * v6.2 step C: the statistics screen's「最快纪录」— the
 * Top 3 task records by peak speed, as a pure function so
 * the ranking is unit-pinned instead of eyeballed.
 *
 * Data source: [TaskRecord.peakBps], the per-task peak the
 * engine already records and TaskStore already serializes
 * (records written before the field existed parse as 0).
 * Only records with peakBps > 0 qualify — a 0 peak means
 * "never measured", never a real speed, so it can never
 * take a place on the board. When nothing qualifies the
 * result is empty and the screen omits the whole section
 * (title included) — the DownloadReport honesty rule: no
 * data, no section, nothing invented.
 *
 * DATE CALIBER: a record's date is its COMPLETION stamp,
 * the same caliber the existing statistics code uses —
 * DownloadReport dates tasks by finishedAt (「本月按完成
 * 时间算」) and the files screen stamps a record by
 * finishedAt too. A record that never finished has
 * finishedAt = 0; its only honest date is its creation
 * stamp, so dateOf falls back to createdAt for those
 * (never 0 / 1970 on screen).
 *
 * ORDER (total, deterministic): peakBps descending, then
 * date descending (a newer record wins a tie), then the
 * record id ascending as the final tiebreak — the same
 * input always yields the same board, in any input order.
 *
 * Display name follows DownloadReport.displayName's
 * caliber: savedName, then fileName, then the URL.
 */
object FastestRecords {

    /** One row of the board: what the screen renders. */
    data class Entry(
        val name: String,
        val peakBps: Long,
        val dateMs: Long,
    )

    /** The board shows at most this many records. */
    const val LIMIT = 3

    /** The record's date, per the caliber in the header. */
    fun dateOf(task: TaskRecord): Long =
        if (task.finishedAt > 0) task.finishedAt else task.createdAt

    private fun displayName(task: TaskRecord): String =
        task.savedName.ifBlank { task.fileName }.ifBlank { task.url }

    /** Top [limit] records by peak speed; see header. */
    fun top(tasks: List<TaskRecord>, limit: Int = LIMIT): List<Entry> =
        tasks.filter { it.peakBps > 0 }
            .sortedWith(
                compareByDescending<TaskRecord> { it.peakBps }
                    .thenByDescending { dateOf(it) }
                    .thenBy { it.id },
            )
            .take(limit.coerceAtLeast(0))
            .map { Entry(displayName(it), it.peakBps, dateOf(it)) }
}
