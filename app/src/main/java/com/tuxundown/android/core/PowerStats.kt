package com.tuxundown.android.core

import java.util.concurrent.atomic.AtomicLong

/**
 * v7.6 (audit round 61, Step C): the diagnostics page's
 * 「功耗」 section — process-local power bookkeeping, READ
 * by the diagnostics page only. Nothing here changes any
 * behaviour: no auto-pause, no throttling, no timing is
 * touched anywhere. The counters are bookkeeping taped
 * onto events that already happen:
 *
 * - Wake-lock held time: [WakeGuard] is the app's ONLY
 *   real wake lock (see its doc), reference-counted in
 *   [WakeLockCounter]. The counter reports its 0 -> 1
 *   transition (the moment the ONE underlying hold is
 *   taken) and its 1 -> 0 transition (the moment it is
 *   dropped) to [WakeLockMeter] here — acquire / release
 *   semantics and timing are exactly as before, this is
 *   a timestamp taped onto the two transitions, nothing
 *   more. Overlapping runs share one underlying hold, so
 *   the meter measures the UNDERLYING hold's duration,
 *   never a per-run sum.
 * - Sampler ticks: [DownloadEngine.emitProgress] is
 *   called exactly once per sampler tick on every one of
 *   the five transfer paths (verified at Step C: five
 *   call sites, one at the end of each path's 1 s sampler
 *   loop body, no other callers; the run-finally's own
 *   progress call deliberately does NOT go through it).
 *   It calls [noteSamplerTick] — one cheap atomic
 *   increment, no allocation, no locking — so the count
 *   is "sampler ticks emitted in this process" and the
 *   1 s cadence is untouched.
 * - Active poll sources: assembled by
 *   [activePollSourceCount] from runtime facts only, with
 *   this pinned caliber: the number of RUNNING SAMPLERS
 *   (one sampler thread per running engine by
 *   construction — every transfer path builds exactly
 *   one and stops it on exit — read as the size of
 *   DownloadCenter's registered-engine set) + 1 when the
 *   link-precheck loop is alive + 1 when the drain loop
 *   is alive. The two loop facts are DownloadCenter
 *   mirrors the service writes at loop entry / exit;
 *   null means the service has not reported in this
 *   process, i.e. UNREADABLE — and any unreadable fact
 *   makes the whole figure absent, never guessed.
 *
 * Absence rules (pinned, same caliber as [SecurityStatus]
 * and [StorageHealth]):
 * - a null (unreadable) input drops exactly its own row;
 * - a negative figure is nonsense from a broken read and
 *   drops its row; a real 0 is shown as 0;
 * - the poll-source figure is all-or-nothing: one
 *   unreadable fact drops the whole row;
 * - when EVERY read failed, the section is absent as a
 *   whole (an empty result).
 *
 * Wording is neutral statement only: what was counted,
 * for how long, how many are alive now. No saving
 * percentage or efficiency claim appears anywhere —
 * nothing here was measured against a baseline.
 */
object PowerStats {

    // ---- sampler ticks (process-local atomic counter) ----

    private val samplerTickTotal = AtomicLong(0)

    /** One sampler tick happened. Called from
     *  DownloadEngine.emitProgress only — see the class
     *  doc for the verified one-call-per-tick caliber. */
    fun noteSamplerTick() {
        samplerTickTotal.incrementAndGet()
    }

    /** Sampler ticks emitted so far in this process. */
    fun samplerTicks(): Long = samplerTickTotal.get()

    // ---- wake-lock held time ----

    private val wakeLockMeter = WakeLockMeter()

    /** The underlying wake lock was just taken (the
     *  counter's 0 -> 1 transition). Called by WakeGuard
     *  only; never changes when or whether it is taken. */
    fun noteWakeLockAcquired() {
        wakeLockMeter.noteAcquired(System.currentTimeMillis())
    }

    /** The underlying wake lock was just dropped (the
     *  counter's 1 -> 0 transition). */
    fun noteWakeLockReleased() {
        wakeLockMeter.noteReleased(System.currentTimeMillis())
    }

    /** Whether the underlying wake lock is held right now. */
    fun wakeLockHeldNow(): Boolean = wakeLockMeter.isHeld()

    /** Total time the underlying wake lock has been held
     *  in this process, including a hold still in progress
     *  (counted up to now). */
    fun wakeLockHeldTotalMs(): Long =
        wakeLockMeter.heldTotalMs(System.currentTimeMillis())

    // ---- active poll sources (pure assembly) ----

    /**
     * The active poll-source count under the pinned
     * caliber (see the class doc): running samplers +
     * precheck loop alive (0/1) + drain loop alive (0/1).
     * Null when ANY fact is unreadable (null) or nonsense
     * (a negative sampler count) — the figure is absent
     * rather than guessed.
     */
    fun activePollSourceCount(
        runningSamplers: Int?,
        precheckAlive: Boolean?,
        drainAlive: Boolean?,
    ): Int? {
        if (runningSamplers == null || runningSamplers < 0) return null
        if (precheckAlive == null || drainAlive == null) return null
        return runningSamplers +
            (if (precheckAlive) 1 else 0) +
            (if (drainAlive) 1 else 0)
    }

    /** The runtime reads the screen managed to make; a null
     *  field means that read failed on this device. */
    data class Snapshot(
        val wakeLockHeldTotalMs: Long?,
        val wakeLockHeldNow: Boolean?,
        val samplerTicks: Long?,
        val activePollSources: Int?,
    )

    /** Assemble the section's display rows from a snapshot.
     *  An empty result means the section is absent. */
    fun lines(snapshot: Snapshot): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        val heldMs = snapshot.wakeLockHeldTotalMs
        if (heldMs != null && heldMs >= 0) {
            val base = "本进程累计持有 ${Format.duration(heldMs)}"
            rows += "唤醒锁持有" to when (snapshot.wakeLockHeldNow) {
                true -> "$base；当前正持有"
                false -> "$base；当前未持有"
                null -> base // held-now unreadable: only that fragment drops
            }
        }
        val ticks = snapshot.samplerTicks
        if (ticks != null && ticks >= 0) {
            rows += "采样 tick" to "本进程累计 $ticks 次"
        }
        val pollSources = snapshot.activePollSources
        if (pollSources != null && pollSources >= 0) {
            rows += "活跃轮询源" to
                "当前共 $pollSources 个（运行中的采样器、链接预检循环、" +
                "队列处理循环按存活计入）"
        }
        return rows
    }
}

/**
 * The wake-lock held-time meter: pure JVM logic with an
 * injected clock so the accumulation is unit-tested
 * without Android (same pattern as [WakeLockCounter]
 * behind its fake handle). It tracks the ONE underlying
 * hold: a second acquire while already held does NOT
 * restart the stamp (overlapping runs share the hold),
 * a release with no hold is a no-op, and a clock that
 * moves backwards contributes 0 rather than a negative
 * span. All state moves under one monitor — the
 * transitions arrive from several engine threads.
 */
class WakeLockMeter {

    private val monitor = Object()

    /** Guarded by [monitor]. Stamp of the current hold's
     *  start, or null when not held. */
    private var heldSinceMs: Long? = null

    /** Guarded by [monitor]. Sum of completed holds. */
    private var accumulatedMs: Long = 0

    /** The underlying hold was taken at [nowMs]. A no-op
     *  when a hold is already in progress. */
    fun noteAcquired(nowMs: Long) {
        synchronized(monitor) {
            if (heldSinceMs == null) heldSinceMs = nowMs
        }
    }

    /** The underlying hold was dropped at [nowMs]: its
     *  span joins the accumulated total. A no-op when no
     *  hold is in progress. */
    fun noteReleased(nowMs: Long) {
        synchronized(monitor) {
            val since = heldSinceMs ?: return
            accumulatedMs += (nowMs - since).coerceAtLeast(0)
            heldSinceMs = null
        }
    }

    /** Whether a hold is in progress. */
    fun isHeld(): Boolean = synchronized(monitor) { heldSinceMs != null }

    /** Completed holds plus the in-progress hold counted
     *  up to [nowMs]. */
    fun heldTotalMs(nowMs: Long): Long = synchronized(monitor) {
        val inProgress = heldSinceMs
            ?.let { (nowMs - it).coerceAtLeast(0) }
            ?: 0
        accumulatedMs + inProgress
    }
}
