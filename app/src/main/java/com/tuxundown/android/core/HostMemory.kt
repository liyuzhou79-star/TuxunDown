package com.tuxundown.android.core

import java.net.URL

/**
 * Per-host optimal thread memory (v1.5).
 *
 * The engine's thread prober (4 -> 8 -> 16 -> 32) costs the first
 * ~6 seconds of every download. For a server the user downloads from
 * regularly that optimum barely changes, so the settled thread count
 * is remembered per host: the next download from the same host starts
 * AT the remembered count and skips the probing phase entirely.
 *
 * Safety valves, so a stale memory can never make things worse for
 * long:
 * - entries expire after [TTL_MS] (30 days);
 * - a host whose downloads FAIL twice in a row is forgotten and the
 *   next download probes from scratch;
 * - only settled counts from COMPLETED segmented downloads are ever
 *   recorded, clamped to the engine's legal range.
 *
 * Pure (no Android, no Context) — unit-tested. [DownloadCenter] only
 * persists the serialized form in SharedPreferences.
 */
object HostMemory {

    const val TTL_MS = 30L * 24 * 3600 * 1000
    const val MAX_ENTRIES = 50
    const val FAILS_TO_FORGET = 2

    data class Entry(
        val host: String,
        val threads: Int,
        val fails: Int,
        val updatedMs: Long,
    )

    fun hostOf(url: String): String =
        runCatching { URL(url).host.lowercase() }.getOrDefault("")

    /** Remembered optimum for [host], or 0 when unknown/expired. */
    fun lookup(entries: List<Entry>, host: String, nowMs: Long): Int {
        if (host.isBlank()) return 0
        val e = entries.firstOrNull { it.host == host } ?: return 0
        if (nowMs - e.updatedMs > TTL_MS) return 0
        return e.threads
    }

    /** Record a settled optimum from a completed download. */
    fun recordSuccess(
        entries: List<Entry>,
        host: String,
        threads: Int,
        nowMs: Long,
    ): List<Entry> {
        if (host.isBlank()) return entries
        val t = threads.coerceIn(2, DownloadEngine.MAX_THREADS)
        val rest = entries.filter { it.host != host }
        return (listOf(Entry(host, t, 0, nowMs)) + rest).take(MAX_ENTRIES)
    }

    /**
     * A download from [host] failed. One failure proves nothing (the
     * network may just have hiccuped); two in a row forget the host
     * so the next attempt re-probes from 4 like a first visit.
     */
    fun recordFailure(entries: List<Entry>, host: String, nowMs: Long): List<Entry> {
        if (host.isBlank()) return entries
        val e = entries.firstOrNull { it.host == host } ?: return entries
        val fails = e.fails + 1
        return if (fails >= FAILS_TO_FORGET) {
            entries.filter { it.host != host }
        } else {
            entries.map { if (it.host == host) it.copy(fails = fails, updatedMs = nowMs) else it }
        }
    }

    /** One entry per line: host \t threads \t fails \t updatedMs. */
    fun serialize(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.host}\t${it.threads}\t${it.fails}\t${it.updatedMs}" }

    fun parse(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4) return@mapNotNull null
            val threads = p[1].toIntOrNull() ?: return@mapNotNull null
            val fails = p[2].toIntOrNull() ?: return@mapNotNull null
            val updated = p[3].toLongOrNull() ?: return@mapNotNull null
            if (p[0].isBlank()) null else Entry(p[0], threads, fails, updated)
        }
    }
}
