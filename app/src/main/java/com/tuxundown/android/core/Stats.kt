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

/**
 * Per-host download totals (v2.3): which sites the user actually
 * downloads from, ranked by bytes — shown on the statistics
 * screen as a Top 5. Counted locally at completion time (the
 * same moment as the other counters), never uploaded.
 *
 * Pure — unit-tested. [DownloadCenter] persists the serialized
 * form in SharedPreferences, one entry per line:
 * host \t bytes \t completedCount.
 */
object HostStats {

    const val MAX_ENTRIES = 50

    data class Entry(val host: String, val bytes: Long, val count: Int)

    /** Add one completed download of [bytes] from [host]. */
    fun record(entries: List<Entry>, host: String, bytes: Long): List<Entry> {
        if (host.isBlank() || bytes <= 0) return entries
        val prev = entries.firstOrNull { it.host == host }
        val merged = Entry(
            host,
            (prev?.bytes ?: 0) + bytes,
            (prev?.count ?: 0) + 1,
        )
        val rest = entries.filter { it.host != host }
        return (listOf(merged) + rest)
            .sortedByDescending { it.bytes }
            .take(MAX_ENTRIES)
    }

    /** The [n] hosts with the most downloaded bytes. */
    fun top(entries: List<Entry>, n: Int = 5): List<Entry> =
        entries.sortedByDescending { it.bytes }.take(n)

    fun serialize(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.host}\t${it.bytes}\t${it.count}" }

    fun parse(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            val bytes = p[1].toLongOrNull() ?: return@mapNotNull null
            val count = p[2].toIntOrNull() ?: return@mapNotNull null
            if (p[0].isBlank()) null else Entry(p[0], bytes, count)
        }
    }
}
