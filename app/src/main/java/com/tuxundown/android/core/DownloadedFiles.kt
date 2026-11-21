package com.tuxundown.android.core

/**
 * The 已下载文件 page's data model (v1.9): finished files living
 * in the current download location, from two sources merged:
 *  - task records (authoritative — name, size, finish time, and
 *    the record link that lets the user delete the record and
 *    the file separately);
 *  - a scan of the same directory, which finds finished files
 *    whose record was deleted earlier (deleting a record keeps
 *    the file by design) — those show as unclaimed.
 *
 * Pure merge/sort/total logic, unit-tested; the Android-side
 * directory scan lives in PublicStorage.
 */
object DownloadedFiles {

    data class Entry(
        val name: String,
        val uri: String,
        /** -1 = the provider would not say. */
        val size: Long,
        /** Epoch ms; 0 = unknown. */
        val modifiedAt: Long,
        /** Owning task id; "" for scan-only (unclaimed) files. */
        val taskId: String,
    )

    const val SORT_TIME = 0
    const val SORT_SIZE = 1

    /**
     * Records win by Uri; scan entries only add files no record
     * claims. ".part" files are never finished files, whichever
     * source they come from.
     */
    fun merge(fromRecords: List<Entry>, fromScan: List<Entry>): List<Entry> {
        val claimed = fromRecords.map { it.uri }.toHashSet()
        val out = fromRecords
            .filter { !it.name.endsWith(".part", ignoreCase = true) }
            .toMutableList()
        val kept = out.map { it.uri }.toHashSet()
        for (e in fromScan) {
            if (e.uri in claimed || e.uri in kept) continue
            if (e.name.endsWith(".part", ignoreCase = true)) continue
            out.add(e)
            kept.add(e.uri)
        }
        return out
    }

    fun sort(list: List<Entry>, mode: Int): List<Entry> = when (mode) {
        SORT_SIZE -> list.sortedWith(
            compareByDescending<Entry> { it.size }.thenBy { it.name.lowercase() }
        )
        else -> list.sortedWith(
            compareByDescending<Entry> { it.modifiedAt }.thenBy { it.name.lowercase() }
        )
    }

    /** Total bytes the listed files occupy (unknown sizes count 0). */
    fun totalSize(list: List<Entry>): Long = list.sumOf { it.size.coerceAtLeast(0) }
}
