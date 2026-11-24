package com.tuxundown.android.core

import java.net.URL

/**
 * Per-host optimal thread memory (v1.5).
 *
 * The engine's thread prober (4 -> 8 -> 16 -> 32 -> 64 -> 128) costs the first
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

    /**
     * v4.3: the user measured the tiers themselves (diagnostics
     * connection-count comparison) and picked [threads] as this
     * host's starting count — write it as the remembered optimum
     * verbatim, clamped to 1..[DownloadEngine.MAX_THREADS] (a
     * manual pick of 4 is a real answer, not a probing artifact,
     * so the recordSuccess floor of 2 does not apply here; the
     * floor is 1 so a stored optimum is never the "unknown" 0).
     *
     * Unlike [recordSuccess] this is NOT completion evidence, so
     * the cap-relax logic must not run: a learned rate-limit
     * ceiling is preserved exactly as it was (StartPlan still
     * bounds the actual start by it). The failure streak resets —
     * the user just proved this count works on this host.
     */
    fun recordManual(
        entries: List<Entry>,
        host: String,
        threads: Int,
        nowMs: Long,
    ): List<Entry> {
        if (host.isBlank()) return entries
        val t = threads.coerceIn(1, DownloadEngine.MAX_THREADS)
        val cap = entries.firstOrNull { it.host == host }?.cap ?: 0
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

    /**
     * v8.3: the user explicitly forgets [host] from the stats
     * screen — the WHOLE entry goes, rate-limit ceiling
     * included, so the next download from this host starts
     * completely fresh. This is a deliberate user forget, which
     * is a different thing from [recordFailure]'s automatic
     * forget: that one keeps the cap on purpose, because two
     * ordinary failures say nothing about the host's rate
     * limit. A blank or unknown host changes nothing.
     */
    fun forget(entries: List<Entry>, host: String): List<Entry> {
        if (host.isBlank()) return entries
        return entries.filter { it.host != host }
    }

    /** One entry per line: host \t threads \t fails \t updatedMs
     *  \t cap. v2.8 appended the cap field; [parse] still accepts
     *  the pre-v2.8 four-field lines (cap = 0), so an upgrade
     *  never loses the thread memory it already had. */
    fun serialize(entries: List<Entry>): String =
        entries.joinToString("\n") {
            "${it.host}\t${it.threads}\t${it.fails}\t${it.updatedMs}\t${it.cap}"
        }

    fun parse(text: String): List<Entry> = parseWithLoss(text).first

    /**
     * v7.3 (audit round 58, B5): same parse as [parse], but also
     * reports how many non-blank lines were dropped as
     * unparseable, so the silent line-salvage becomes a countable
     * trace (DownloadCenter accumulates it for the diagnostics
     * 存储健康 section). Blank lines are ignored, not counted —
     * a trailing newline is not corruption.
     */
    fun parseWithLoss(text: String): Pair<List<Entry>, Int> {
        if (text.isBlank()) return emptyList<Entry>() to 0
        var dropped = 0
        val out = text.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split('\t')
            if (p.size != 4 && p.size != 5) { dropped++; return@mapNotNull null }
            val threads = p[1].toIntOrNull() ?: run { dropped++; return@mapNotNull null }
            val fails = p[2].toIntOrNull() ?: run { dropped++; return@mapNotNull null }
            val updated = p[3].toLongOrNull() ?: run { dropped++; return@mapNotNull null }
            val cap = if (p.size == 5) {
                p[4].toIntOrNull() ?: run { dropped++; return@mapNotNull null }
            } else {
                0
            }
            if (p[0].isBlank()) { dropped++; null } else Entry(p[0], threads, fails, updated, cap)
        }
        return out to dropped
    }
}

/**
 * v5.1 audit round 36: memoizing wrapper over [HostMemory.parse].
 *
 * The serialized host table lives in SharedPreferences and used
 * to be re-parsed on EVERY read — including from the task card's
 * 服务器画像 section on every recomposition (the home list ticks
 * once a second), and from the engine's per-start hint/cap reads.
 * The table changes only when a download settles, fails or is
 * rate-limited, so parsing the same string again is pure waste.
 *
 * This cache returns the SAME list instance while the raw text
 * is unchanged and re-parses only when it changes; [version]
 * counts re-parses so UI callers can key a `remember` on it and
 * refresh exactly when the underlying memory actually moved.
 * Pure JVM logic — unit-tested.
 */
class HostMemoryParseCache {

    private var raw: String? = null
    private var parsed: List<HostMemory.Entry> = emptyList()

    /** Number of times the text actually changed and was
     *  re-parsed (the first read counts as one). */
    @Volatile
    var version: Int = 0
        private set

    /** v7.3 (B5): lines dropped by the most recent re-parse. */
    @Volatile
    var lastDropped: Int = 0
        private set

    @Synchronized
    fun get(text: String): List<HostMemory.Entry> {
        if (text == raw) return parsed
        raw = text
        val (entries, dropped) = HostMemory.parseWithLoss(text)
        parsed = entries
        lastDropped = dropped
        version++
        return parsed
    }
}

/**
 * v8.6 audit round 71: forget-vs-learn guard for the host table.
 *
 * The user can forget a host (or clear the whole table) WHILE a
 * download from that host is still running. That run started
 * before the forget, so its completion write-back (recordSuccess
 * / recordFailure / recordRateLimit) would silently re-teach the
 * very host the user just forgot — the next download would start
 * from the "forgotten" values instead of fresh, which is exactly
 * what 忘掉 promises will not happen.
 *
 * The guard is generation stamps, no table format change: a run
 * captures [stampForRunStart] when it starts (the forget
 * generation it can see at that moment); [onForget] /
 * [onClearAll] advance the generation; a write-back may land
 * only when its stamp still matches the host's current forget
 * generation ([mayLearn]). A run that starts AFTER the forget
 * stamps the new generation and learns normally — forgetting
 * blocks only the runs that were already in flight, never future
 * ones. Hosts never forgotten have generation 0, so unstamped /
 * legacy callers keep learning exactly as before.
 *
 * Pure JVM logic — unit-tested. DownloadCenter owns the one
 * instance and checks [mayLearn] inside its host-table lock, so
 * the check and the write it gates are atomic against a forget
 * taking the same lock.
 */
class HostLearnGuard {

    private var nextGen = 0L
    private var clearGen = 0L
    private val forgetGen = HashMap<String, Long>()

    private fun forgetStampOf(host: String): Long =
        maxOf(forgetGen[host] ?: 0L, clearGen)

    /** The stamp a run starting NOW must carry for [host]. */
    @Synchronized
    fun stampForRunStart(host: String): Long {
        if (host.isBlank()) return 0L
        return forgetStampOf(host)
    }

    /** The user forgot [host]: in-flight runs lose the right to
     *  teach it again. */
    @Synchronized
    fun onForget(host: String) {
        if (host.isBlank()) return
        forgetGen[host] = ++nextGen
    }

    /** The user cleared the whole table: same, for every host. */
    @Synchronized
    fun onClearAll() {
        clearGen = ++nextGen
    }

    /** May a write-back carrying [stamp] still teach [host]? */
    @Synchronized
    fun mayLearn(host: String, stamp: Long): Boolean {
        if (host.isBlank()) return false
        return stamp >= forgetStampOf(host)
    }
}
