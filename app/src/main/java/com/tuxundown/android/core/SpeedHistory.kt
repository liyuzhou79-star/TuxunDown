package com.tuxundown.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Speed-test history (v4.1): the last [MAX_ENTRIES] QUICK tests
 * (single-point, single-vs-multi), newest first, shown under the
 * result on the speed-test screen so the user can see whether
 * the network is getting faster or slower over time instead of
 * each run evaporating.
 *
 * Scope decisions, deliberate:
 *  - the multi-point COMPARISON does not record here: it runs a
 *    different口径 (shorter phases, every point including dead
 *    ones) and its numbers are not comparable with a quick
 *    test's, so mixing the two in one list would mislead;
 *  - an entry exists only for a COMPLETED quick test — both
 *    phases moved bytes and [SpeedVerdict] reached a verdict.
 *    Cancelled / failed runs leave no trace.
 *
 * Pure logic, unit-tested; [DownloadCenter] persists the
 * serialized form in SharedPreferences under its own key. The
 * history is LOCAL ONLY: it is deliberately absent from both
 * backup shapes (settingsSnapshotJson / statsSnapshotJson never
 * mention the key, and Backup only wraps what it is handed) —
 * a speed measurement describes THIS device on THIS network
 * and says nothing worth carrying to a new phone.
 */
object SpeedHistory {

    const val MAX_ENTRIES = 10

    data class Entry(
        /** When the test finished (epoch ms). */
        val atMs: Long,
        /** Readable endpoint name (SpeedTest.Endpoint.label). */
        val pointLabel: String,
        val singleBps: Long,
        val multiBps: Long,
        val verdict: SpeedVerdict.Verdict,
    )

    /** Prepend [entry], keeping only the newest [MAX_ENTRIES]. */
    fun add(entries: List<Entry>, entry: Entry): List<Entry> =
        (listOf(entry) + entries).take(MAX_ENTRIES)

    /** One-tap clear on the screen: the empty history. */
    fun clear(): List<Entry> = emptyList()

    /** Short verdict label for one history row. */
    fun verdictShort(verdict: SpeedVerdict.Verdict): String = when (verdict) {
        SpeedVerdict.Verdict.FASTER -> "多线程更快"
        SpeedVerdict.Verdict.SIMILAR -> "单多线程差不多"
        SpeedVerdict.Verdict.SLOWER -> "多线程更慢"
    }

    fun serialize(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("at", e.atMs)
            o.put("point", e.pointLabel)
            o.put("single", e.singleBps)
            o.put("multi", e.multiBps)
            o.put("verdict", e.verdict.name)
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * Tolerant read: a corrupt store (hand-edited, downgrade,
     * partial write) parses to whatever entries are individually
     * sound, or an empty list — never a crash. Entries with a
     * blank point, a non-positive timestamp, non-positive speeds
     * or an unknown verdict name are dropped (a completed test
     * always has all four). The result is capped at
     * [MAX_ENTRIES] so a bloated store self-heals on next save.
     */
    fun parse(text: String): List<Entry> {
        val arr = runCatching { JSONArray(text.trim()) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val verdict = runCatching {
                SpeedVerdict.Verdict.valueOf(o.optString("verdict"))
            }.getOrNull() ?: continue
            val entry = Entry(
                atMs = o.optLong("at"),
                pointLabel = o.optString("point"),
                singleBps = o.optLong("single"),
                multiBps = o.optLong("multi"),
                verdict = verdict,
            )
            if (entry.atMs <= 0 || entry.pointLabel.isBlank() ||
                entry.singleBps <= 0 || entry.multiBps <= 0
            ) {
                continue
            }
            out.add(entry)
        }
        return out.take(MAX_ENTRIES)
    }
}
