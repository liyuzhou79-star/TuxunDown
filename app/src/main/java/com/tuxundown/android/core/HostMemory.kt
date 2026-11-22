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
 * - a host whose downloads FAIL twice in a row has its thread
 *   optimum forgotten and the next download probes from scratch
 *   (v3.3: a rate-limit ceiling on the same entry survives that
 *   forget — see recordFailure);
 * - only settled counts from COMPLETED segmented downloads are ever
 *   recorded, clamped to the engine's legal range.
 *
 * v2.8 adds the RATE-LIMIT CEILING (Entry.cap): when a host
 * rejects parallel load with a rate-limit signature (see
 * RateLimit for what counts — a lone 403 does NOT), half the
 * rejected load level is remembered as a ceiling and the next
 * run stays at or below it. Fresh rejection evidence can only
 * tighten it; completions that settle at the ceiling relax it
 * one step at a time. It shares the entry (and its TTL) with
 * the optimum above.
 *
 * Pure (no Android, no Context) — unit-tested. [DownloadCenter] only
 * persists the serialized form in SharedPreferences.
 */
object HostMemory {

    const val TTL_MS = 30L * 24 * 3600 * 1000
    const val MAX_ENTRIES = 50
    const val FAILS_TO_FORGET = 2

    /** v2.8: the lowest ceiling a rate-limit memory may impose —
     *  below this a download barely segments at all. */
    const val MIN_CAP = 4

    /** v2.8: how much a completion at the ceiling raises it by. */
    const val CAP_RELAX_STEP = 4

    data class Entry(
        val host: String,
        val threads: Int,
        val fails: Int,
        val updatedMs: Long,
        /** v2.8: remembered rate-limit ceiling (0 = none). Set
         *  when the host rejected parallel load (see RateLimit);
         *  the next run's probing/scaling stays at or below it
         *  until completions earn it back up. */
        val cap: Int = 0,
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
        // v2.8: a rate-limit ceiling survives successes — and a
        // completion that settled AT the ceiling earns a cautious
        // step back up (+CAP_RELAX_STEP; reaching the engine max
        // retires the ceiling entirely). Settling BELOW the
        // ceiling proves nothing about it, so it stays as it was.
        var cap = entries.firstOrNull { it.host == host }?.cap ?: 0
        if (cap > 0 && t >= cap) {
            cap = if (cap + CAP_RELAX_STEP >= DownloadEngine.MAX_THREADS) {
                0
            } else {
                cap + CAP_RELAX_STEP
            }
        }
        val rest = entries.filter { it.host != host }
        return (listOf(Entry(host, t, 0, nowMs, cap)) + rest).take(MAX_ENTRIES)
    }

    /** v2.8: the remembered rate-limit ceiling for [host], or 0. */
    fun capOf(entries: List<Entry>, host: String, nowMs: Long): Int {
        if (host.isBlank()) return 0
        val e = entries.firstOrNull { it.host == host } ?: return 0
        if (nowMs - e.updatedMs > TTL_MS) return 0
        return e.cap
    }

    /**
     * v2.8: this host rejected load at [threadsAtLimit]
     * connections (see RateLimit's verdict) — remember a ceiling
     * of half that, never below [MIN_CAP]. Fresh evidence can
     * only TIGHTEN an existing ceiling; loosening is earned by
     * completions, one step at a time (see recordSuccess). The
     * remembered optimum and failure streak are preserved; a
     * host with no entry yet gets a cap-only one (threads 0 =
     * "no optimum known", which lookup already reports as 0).
     */
    fun recordRateLimit(
        entries: List<Entry>,
        host: String,
        threadsAtLimit: Int,
        nowMs: Long,
    ): List<Entry> {
        if (host.isBlank()) return entries
        val computed = (threadsAtLimit / 2)
            .coerceIn(MIN_CAP, DownloadEngine.MAX_THREADS)
        val old = entries.firstOrNull { it.host == host }
        val cap = if (old != null && old.cap > 0) minOf(old.cap, computed) else computed
        val entry = Entry(
            host = host,
            threads = old?.threads ?: 0,
            fails = old?.fails ?: 0,
            updatedMs = nowMs,
            cap = cap,
        )
        return (listOf(entry) + entries.filter { it.host != host }).take(MAX_ENTRIES)
    }

    /**
     * A download from [host] failed. One failure proves nothing (the
     * network may just have hiccuped); two in a row forget the host's
     * THREAD OPTIMUM so the next attempt re-probes from 4 like a
     * first visit.
     *
     * v3.3 audit round 19 (combination): the forget used to delete
     * the whole entry — including a rate-limit CEILING learned from
     * a completely different failure mode (the host refusing
     * parallel load, see [recordRateLimit]). Two ordinary failures
     * (a dead network, a vanished file) therefore silently erased
     * the ceiling, and the next run probed straight back up to the
     * full thread count and could earn the same 429 storm once
     * more before re-learning it. The two memories answer
     * different questions, so the forget now keeps a cap-only
     * entry: the optimum is gone (lookup reports 0, re-probe
     * happens) but the ceiling still bounds that re-probe, and it
     * still expires on the usual TTL.
     */
    fun recordFailure(entries: List<Entry>, host: String, nowMs: Long): List<Entry> {
        if (host.isBlank()) return entries
        val e = entries.firstOrNull { it.host == host } ?: return entries
        val fails = e.fails + 1
        return if (fails >= FAILS_TO_FORGET) {
            if (e.cap > 0) {
                entries.map {
                    if (it.host == host) {
                        Entry(host, threads = 0, fails = 0, updatedMs = nowMs, cap = e.cap)
                    } else {
                        it
                    }
                }
            } else {
                entries.filter { it.host != host }
            }
        } else {
            entries.map { if (it.host == host) it.copy(fails = fails, updatedMs = nowMs) else it }
        }
    }

    /** One entry per line: host \t threads \t fails \t updatedMs
     *  \t cap. v2.8 appended the cap field; [parse] still accepts
     *  the pre-v2.8 four-field lines (cap = 0), so an upgrade
     *  never loses the thread memory it already had. */
    fun serialize(entries: List<Entry>): String =
        entries.joinToString("\n") {
            "${it.host}\t${it.threads}\t${it.fails}\t${it.updatedMs}\t${it.cap}"
        }

    fun parse(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4 && p.size != 5) return@mapNotNull null
            val threads = p[1].toIntOrNull() ?: return@mapNotNull null
            val fails = p[2].toIntOrNull() ?: return@mapNotNull null
            val updated = p[3].toLongOrNull() ?: return@mapNotNull null
            val cap = if (p.size == 5) p[4].toIntOrNull() ?: return@mapNotNull null else 0
            if (p[0].isBlank()) null else Entry(p[0], threads, fails, updated, cap)
        }
    }
}
