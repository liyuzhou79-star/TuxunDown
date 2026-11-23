package com.tuxundown.android.core

/**
 * Download statistics helpers (v1.6): the statistics screen shows
 * 累计下载量 / 今日下载量 / 完成任务数 / 平均速度， all counted
 * locally on the device (nothing is uploaded anywhere). Only the
 * day-rollover math lives here, pure and unit-tested; the counters
 * themselves are plain SharedPreferences values in DownloadCenter.
 */
object StatsMath {

    /**
     * "2026-11-22" style local day key for an epoch-ms instant,
     * read in [zone] (v6.4 audit round 49: injectable, default =
     * the device zone at call time). The caliber is LOCAL DAY in
     * the zone in force when the key is computed: after a zone
     * change the same instant can key to a different day, and a
     * stored "today" counter simply stops matching (todayBytes
     * reads 0) while the DaySeries history keeps its old keys —
     * statistics re-key, no counted byte is lost or moved.
     */
    fun dayKey(
        epochMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): String {
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /** Stored "today" bytes are only valid while the day matches. */
    fun todayBytes(
        storedKey: String, storedBytes: Long, nowMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Long =
        if (storedKey == dayKey(nowMs, zone)) storedBytes else 0L
}

/**
 * Per-day download totals (v2.7) — pure, unit-tested.
 *
 * HONEST SCOPE: before v2.7 the app kept only TODAY's counter,
 * so there is no historical series to "reuse" — this series
 * starts recording with v2.7 and grows one day at a time. The
 * one bridge: a day's value is stored as that day's TOTAL (the
 * same number the 今日 counter keeps), so the first completion
 * after upgrading seeds today with today's full total instead
 * of just its own bytes.
 *
 * [DownloadCenter] persists the serialized form in
 * SharedPreferences, one entry per line: dayKey \t bytes,
 * newest last, pruned to [MAX_DAYS].
 */
object DaySeries {

    const val MAX_DAYS = 31

    data class Day(val key: String, val bytes: Long)

    /** Set [key]'s total to [totalBytes] (upsert), pruned to
     *  the newest [MAX_DAYS] days. Keys sort chronologically
     *  because the format is yyyy-MM-dd. */
    fun recordDayTotal(
        entries: List<Day>, key: String, totalBytes: Long,
    ): List<Day> {
        if (key.isBlank()) return entries
        val merged = entries.filter { it.key != key } +
            Day(key, totalBytes.coerceAtLeast(0))
        return merged.sortedBy { it.key }.takeLast(MAX_DAYS)
    }

    /** The last [n] days ending today, oldest first; days with
     *  no record read as 0 so the chart has no holes. "Today"
     *  and the day steps are read in [zone] (v6.4: injectable,
     *  default = the device zone at call time), and the keys
     *  come from the same zone so the walk and the lookup can
     *  never disagree about which day an instant belongs to. */
    fun lastDays(
        entries: List<Day>, n: Int, nowMs: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): List<Day> {
        if (n <= 0) return emptyList()
        val byKey = entries.associateBy { it.key }
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(n - 1))
        return (0 until n).map {
            val key = StatsMath.dayKey(cal.timeInMillis, zone)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            byKey[key] ?: Day(key, 0)
        }
    }

    fun serialize(entries: List<Day>): String =
        entries.joinToString("\n") { "${it.key}\t${it.bytes}" }

    fun parse(text: String): List<Day> {
        if (text.isBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 2) return@mapNotNull null
            val bytes = p[1].toLongOrNull() ?: return@mapNotNull null
            if (p[0].isBlank()) null else Day(p[0], bytes)
        }
    }
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
