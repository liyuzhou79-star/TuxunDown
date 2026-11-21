package com.tuxundown.android.core

/**
 * Download statistics helpers (v1.6): the statistics screen shows
 * 累计下载量 / 今日下载量 / 完成任务数 / 平均速度， all counted
 * locally on the device (nothing is uploaded anywhere). Only the
 * day-rollover math lives here, pure and unit-tested; the counters
 * themselves are plain SharedPreferences values in DownloadCenter.
 */
object StatsMath {

    /** "2026-11-22" style local day key for an epoch-ms instant. */
    fun dayKey(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /** Stored "today" bytes are only valid while the day matches. */
    fun todayBytes(storedKey: String, storedBytes: Long, nowMs: Long): Long =
        if (storedKey == dayKey(nowMs)) storedBytes else 0L
}
