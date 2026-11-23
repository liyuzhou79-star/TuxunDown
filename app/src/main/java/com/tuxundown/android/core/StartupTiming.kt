package com.tuxundown.android.core

import android.content.Context
import android.os.SystemClock

/**
 * v5.1 (Step C): last cold-start-to-first-frame time, for the
 * diagnostics page — measured in-app, stored ONLY on this
 * device, never uploaded and never backed up.
 *
 * What is measured (the 口径, kept honest):
 * - START: the process start time, android.os.Process
 *   .getStartUptimeMillis() (available since API 29, this
 *   app's minSdk). That clock starts when the process is
 *   forked, so the number covers class loading and
 *   Application/activity setup before onCreate — the user's
 *   real wait. FALLBACK: if the platform value is unreadable
 *   (<= 0), the start is the moment MainActivity.onCreate was
 *   entered (a SystemClock.uptimeMillis() read taken as the
 *   first statement there). The fallback number therefore
 *   UNDERSTATES the real wait by the pre-onCreate time; it is
 *   still labelled 约 (approximate) on screen.
 * - END: the first frame actually being drawn — a one-shot
 *   ViewTreeObserver.OnDrawListener on the window's decor
 *   view, removed the first time it fires. onResume is NOT
 *   used: it fires before composition/draw, so it would
 *   flatter the number.
 * Both clocks are uptimeMillis-based (monotonic, sleep time
 * excluded), so a wall-clock change mid-launch cannot skew
 * the difference.
 *
 * What counts as a cold start (see [shouldRecord]):
 * - Only the FIRST MainActivity creation in a process may
 *   record. A process-level claim ([ColdStartGate]) is spent
 *   by that first creation, so a configuration-change
 *   recreation, a rotation, or a warm relaunch into the
 *   still-alive process can never overwrite the stored value
 *   (and a later genuine cold start — a new process — records
 *   its own value over the old one: the page shows the LAST
 *   cold start).
 * - A launch that lands on the crash-report page is NOT a
 *   normal cold start: the first frame then is the report,
 *   and how long the user sits on it is their reading time,
 *   not startup time. Such launches are skipped entirely
 *   (pending crash report OR CrashGuard safe mode — the two
 *   always travel together in practice, both are checked).
 *
 * Cost discipline (the measurement must not slow the thing it
 * measures): before the first frame the only added work is
 * two clock reads and the one-shot listener registration —
 * no IO, no allocation-heavy work. The single write happens
 * AFTER the first frame has been drawn, as one apply() (async
 * disk flush by the platform) of one long into a tiny
 * dedicated preferences file.
 *
 * Storage discipline: the value lives in its OWN
 * SharedPreferences file ("startup_timing"), NOT in
 * DownloadCenter's "settings" file. DownloadCenter
 * .settingsSnapshotJson() is an explicit per-key whitelist
 * (every entry is a hand-written o.put(...) call — verified
 * by reading it: no whole-table export anywhere), and Backup
 * only ever wraps the snapshots it is handed
 * (settingsSnapshotJson / statsSnapshotJson /
 * hostMemorySnapshot), so a value in a separate file cannot
 * ride either backup shape (v1.8 backup or v3.6 migration
 * pack). It is likewise absent from the statistics snapshot
 * and from TaskStore records: it never migrates, never
 * leaves the device.
 */
object StartupTiming {

    private const val PREFS = "startup_timing"
    private const val KEY_LAST_COLD_START_MS = "last_cold_start_ms"

    // ---------- pure decisions (unit-tested) ----------

    /**
     * The difference, or null when it cannot be trusted:
     * an unreadable start (<= 0) or an end BEFORE the start
     * (clock mix-up between the two sources) records nothing
     * rather than a negative or zero fake number.
     */
    fun elapsedMs(startUptimeMs: Long, endUptimeMs: Long): Long? {
        if (startUptimeMs <= 0) return null
        if (endUptimeMs < startUptimeMs) return null
        return endUptimeMs - startUptimeMs
    }

    /** May this launch's measurement be recorded at all? */
    fun shouldRecord(
        isFirstActivityInProcess: Boolean,
        safeMode: Boolean,
        hasPendingCrash: Boolean,
    ): Boolean = isFirstActivityInProcess && !safeMode && !hasPendingCrash

    /**
     * The value to keep after a launch: a recordable launch
     * with a valid measurement replaces the old value (the
     * page shows the LAST cold start); anything else — warm
     * start, recreation, crash-report launch, unmeasurable —
     * keeps the existing value untouched.
     */
    fun nextStoredValue(existingMs: Long, candidateMs: Long?, recordable: Boolean): Long =
        if (recordable && candidateMs != null && candidateMs > 0) candidateMs else existingMs

    /** The on-screen value text, or null when there is no
     *  honest number to show (first install, never measured)
     *  — the caller hides the whole row on null instead of
     *  showing 0 or a made-up figure. */
    fun displayText(storedMs: Long): String? =
        if (storedMs > 0) "约 $storedMs 毫秒" else null

    /**
     * Process-level one-shot claim: exactly the first caller
     * in a process gets true. Extracted as its own tiny class
     * so the cold/warm/recreation decision is unit-testable
     * without Android.
     */
    class ColdStartGate {
        private var claimed = false

        @Synchronized
        fun claim(): Boolean {
            if (claimed) return false
            claimed = true
            return true
        }
    }

    // ---------- process state (Android-facing, thin) ----------

    private val gate = ColdStartGate()

    /** Start of the in-flight cold-start measurement for this
     *  process; 0 = this process is not measuring (warm,
     *  recreation, crash-report launch, or already done). */
    @Volatile
    private var pendingStartMs: Long = 0

    /**
     * Called from MainActivity.onCreate (after CrashGuard
     * .install, so safeMode / pending-crash are already known
     * for this launch). [onCreateEntryUptimeMs] is the
     * fallback start, read by the caller as onCreate's first
     * statement. Pure clock reads only — no IO before the
     * first frame (CrashGuard.pendingCrash in the normal case
     * is one in-memory boolean read on the already-loaded
     * crash_guard prefs; only on an actual crash launch does
     * it read the small log file, and that launch is not
     * measured anyway).
     */
    fun beginColdStart(context: Context, onCreateEntryUptimeMs: Long) {
        val first = gate.claim()
        val recordable = shouldRecord(
            isFirstActivityInProcess = first,
            safeMode = CrashGuard.safeMode,
            hasPendingCrash = first && CrashGuard.pendingCrash(context) != null,
        )
        if (!recordable) {
            pendingStartMs = 0
            return
        }
        val processStart = runCatching {
            android.os.Process.getStartUptimeMillis()
        }.getOrDefault(0L)
        pendingStartMs = if (processStart > 0) processStart else onCreateEntryUptimeMs
    }

    /**
     * The first frame has just been drawn (called once by the
     * one-shot draw listener in MainActivity): finish the
     * measurement and persist it. No-ops when this process is
     * not measuring. The write is a single apply() into the
     * tiny dedicated prefs file — after the first frame, so
     * it can never delay it.
     */
    fun onFirstFrameDrawn(context: Context) {
        val start = pendingStartMs
        if (start <= 0) return
        pendingStartMs = 0
        val candidate = elapsedMs(start, SystemClock.uptimeMillis()) ?: return
        val existing = lastColdStartMs(context)
        val next = nextStoredValue(existing, candidate, recordable = true)
        if (next != existing) {
            runCatching {
                prefs(context).edit().putLong(KEY_LAST_COLD_START_MS, next).apply()
            }
        }
    }

    // ---------- storage ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored last cold-start time in ms; 0 = none. */
    fun lastColdStartMs(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_LAST_COLD_START_MS, 0) }
            .getOrDefault(0)
}
