package com.tuxundown.android.core

import android.content.Context
import android.os.PowerManager

/**
 * Screen-off speed keeping (v4.9, audit round 34).
 *
 * The gap this closes: nothing in the app held a wake lock.
 * What carries a download with the screen off today is the
 * dataSync foreground service — but an FGS only buys process
 * IMPORTANCE, it does not keep the CPU awake. In suspend /
 * Doze the CPU sleeps between packet bursts, so the engine's
 * worker threads and its 1 s sampler threads (Thread.sleep
 * runs on the uptime clock, which pauses in suspend) get
 * scheduled late and throughput sags on exactly the workload
 * this app exists for: a big file downloading with the screen
 * off. PARTIAL_WAKE_LOCK is the textbook answer: the screen
 * may sleep, the CPU may not — while a transfer is running.
 *
 * Lifecycle — why "only while transferring" holds by
 * construction, not by discipline:
 * - [DownloadEngine.run] is the single transfer funnel:
 *   segmented, single-stream, HLS and FTP all pass through
 *   it, and it is the only place that acquires (at run start,
 *   released in its finally). If no engine is running, no
 *   lock is held.
 * - Queued / parked tasks hold nothing: charge-park, WiFi-
 *   park, schedule standby and system-timeout pause all wait
 *   in the service WITHOUT an engine run in flight.
 * - Retry backoff holds nothing: the engine has returned by
 *   then; the service's sleepBackoff lives outside run().
 * - The finalize lane holds nothing: submitFinalize is a
 *   separate lane that starts after the engine exits.
 * - Process death releases the lock with the process (a wake
 *   lock is not persisted state), so a killed app can never
 *   leak a held lock across restarts.
 * - CrashGuard safe mode starts no engines, so it holds
 *   nothing either.
 *
 * Failure posture: a wake lock must never be able to break a
 * download. Before [attach] (or if the PowerManager hand-off
 * fails for any reason) every entry point is a silent no-op,
 * and every Android call is wrapped in runCatching.
 *
 * The counting itself is pure JVM logic ([WakeLockCounter]
 * behind the injectable [WakeLockHandle]) so it is unit-
 * tested with a fake; the Android side below only builds the
 * one real lock, lazily, and hands it over.
 */

/** The underlying platform lock, reduced to the two calls the
 *  counter needs. A fake stands in for it in unit tests. */
interface WakeLockHandle {
    fun acquire()
    fun release()
}

/**
 * Reference-counting holder over one [WakeLockHandle]:
 * - the underlying lock is acquired on the 0 -> 1 transition
 *   only and released on the 1 -> 0 transition only, so any
 *   number of overlapping runs share ONE underlying hold and
 *   the lock stays held until the LAST run releases;
 * - the count never goes negative: [release] with no matching
 *   acquire is a no-op;
 * - all state moves under one monitor — engine runs live on
 *   several service threads, so every method is synchronized.
 *
 * The settings policy is an input, not a read: [acquireIf]
 * takes the switch value as a parameter (the caller reads
 * DownloadCenter), which keeps this class free of Android /
 * prefs coupling and lets tests pin "switch off = never
 * acquire" directly. [acquireIf] returns whether THIS call
 * took a counted hold, so the caller releases exactly when it
 * acquired — a switch flipped off mid-run can never make one
 * run's release eat another run's hold.
 */
class WakeLockCounter(
    private val handle: WakeLockHandle,
    /**
     * v7.6 (audit round 61, Step C): bookkeeping taps for
     * the diagnostics page's 功耗 section — invoked ONLY on
     * the underlying lock's transitions: [onFirstAcquire]
     * after a successful 0 -> 1 acquire, [onLastRelease]
     * after a successful 1 -> 0 release. They observe the
     * transitions; they never influence them (defaults are
     * no-ops, so tests and any other holder are unaffected;
     * production passes PowerStats' meter — see WakeGuard).
     */
    private val onFirstAcquire: () -> Unit = {},
    private val onLastRelease: () -> Unit = {},
) {

    private val monitor = Object()

    /** Guarded by [monitor]. Number of counted holds. */
    private var count = 0

    /** Current counted holds (tests / diagnostics). */
    val activeCount: Int
        get() = synchronized(monitor) { count }

    /**
     * Take one counted hold if [enabled]. Returns true when a
     * hold was taken (the caller must later [release]); false
     * — and nothing counted, nothing acquired — when the
     * switch is off.
     */
    fun acquireIf(enabled: Boolean): Boolean {
        if (!enabled) return false
        synchronized(monitor) {
            val first = count == 0
            if (first) handle.acquire()
            count++
            // v7.6: the 0 -> 1 transition just happened (the
            // acquire above succeeded — a throw leaves the
            // count untouched and never reaches this line).
            if (first) onFirstAcquire()
        }
        return true
    }

    /** Drop one counted hold; a no-op when none is counted. */
    fun release() {
        synchronized(monitor) {
            if (count == 0) return
            count--
            if (count == 0) {
                handle.release()
                // v7.6: the 1 -> 0 transition just happened.
                onLastRelease()
            }
        }
    }

    /**
     * v6.1 (audit round 46, item A46-2): bring ONE caller's hold
     * in line with the CURRENT switch value, mid-run. The switch
     * used to be read only at acquire time, so turning
     * 「下载中保持运行」 off during a long transfer kept the CPU
     * awake until that run ended — against the setting the user
     * had just changed. Now the engine reconciles on its
     * existing 1 s sampler tick (no new timer, no new polling):
     * - enabled && !holding -> take a hold, return true;
     * - !enabled && holding -> drop it, return false (the
     *   underlying lock is released when the LAST holding run
     *   reconciles or finishes — other runs' holds are never
     *   eaten, because each run reconciles only its own flag);
     * - otherwise the flag already matches: return it unchanged
     *   and touch nothing.
     * The caller stores the returned flag and releases in its
     * finally only when it is true, exactly like [acquireIf] —
     * so the acquire/release pairing stays per-run and
     * structural. If the underlying acquire throws, the count
     * is unchanged (pinned by WakeGuardTest) and the exception
     * propagates to WakeGuard's runCatching wrapper, which
     * keeps the caller's previous flag.
     */
    fun reconcile(enabled: Boolean, holding: Boolean): Boolean {
        if (enabled == holding) return holding
        return if (enabled) {
            acquireIf(true)
        } else {
            release()
            false
        }
    }
}

/** Android handle over the one real [PowerManager.WakeLock].
 *  The lock itself is created with setReferenceCounted(false)
 *  — [WakeLockCounter] owns the count, the platform must not
 *  keep a second one. The isHeld guards make a stray double
 *  call harmless on top of that.
 *
 *  The acquire is deliberately the NO-TIMEOUT variant (audit
 *  round 46, item A46-1, judged v6.1): a timed acquire would
 *  need a bound that covers the longest legitimate run, and
 *  no such bound exists — a multi-GB download on a slow link
 *  runs for hours, and any timeout short enough to act as a
 *  backstop would expire mid-run and silently drop the very
 *  protection this lock exists for (the run would then sag in
 *  suspend with no signal anywhere). The release guarantee
 *  does not need the backstop: round 46 verified the pairing
 *  is structural (one acquire site, one finally release site,
 *  every path inside the try, process death releases with the
 *  process). Recorded as a deliberate non-change in the v6.1
 *  checkpoint chapter. */
private class AndroidWakeLockHandle(
    private val lock: PowerManager.WakeLock,
) : WakeLockHandle {
    override fun acquire() {
        if (!lock.isHeld) lock.acquire()
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}

/**
 * The app-wide guard the engine talks to. [attach] is called
 * once from DownloadService.onCreate (idempotent); the real
 * lock is created lazily on the first transfer that wants it,
 * so merely starting the service costs nothing.
 */
object WakeGuard {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var counter: WakeLockCounter? = null

    /** Store the application context. Idempotent: the first
     *  attach wins, later calls change nothing. */
    fun attach(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    /** The counter over the real lock, built on first use.
     *  Null when unattached or when the PowerManager hand-off
     *  failed — callers treat null as "no lock available" and
     *  carry on without one. */
    private fun counterOrNull(): WakeLockCounter? {
        counter?.let { return it }
        val ctx = appContext ?: return null
        synchronized(this) {
            counter?.let { return it }
            val created = runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return@runCatching null
                val lock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TuxunDown::TransferWakeLock",
                )
                // WakeGuard owns the count (see WakeLockCounter);
                // a platform-side count would only double-book it.
                lock.setReferenceCounted(false)
                WakeLockCounter(
                    AndroidWakeLockHandle(lock),
                    // v7.6 (Step C): the 功耗 meter taps — the
                    // counter reports its underlying 0 -> 1 /
                    // 1 -> 0 transitions and nothing else, so
                    // PowerStats measures the ONE real hold.
                    onFirstAcquire = { PowerStats.noteWakeLockAcquired() },
                    onLastRelease = { PowerStats.noteWakeLockReleased() },
                )
            }.getOrNull()
            counter = created
            return created
        }
    }

    /**
     * Called at the start of DownloadEngine.run(): take a
     * counted hold when the 下载中保持运行 switch is on
     * (DownloadCenter.keepCpuAwake, default on). Returns
     * whether a hold was taken — the caller releases in its
     * finally only when this returned true. Never throws.
     */
    fun acquireTransfer(): Boolean = runCatching {
        counterOrNull()?.acquireIf(DownloadCenter.keepCpuAwake) ?: false
    }.getOrDefault(false)

    /** Paired release for a true return from [acquireTransfer].
     *  Never throws; a release with nothing held is a no-op. */
    fun releaseTransfer() {
        runCatching { counter?.release() }
    }

    /**
     * v6.1 (A46-2): mid-run counterpart of [acquireTransfer] —
     * re-reads the CURRENT 下载中保持运行 switch and reconciles
     * the calling run's hold with it (see
     * [WakeLockCounter.reconcile]). Called by the engine on its
     * existing 1 s sampler tick, so a switch flipped mid-run
     * takes effect within about a second: off releases the hold
     * (the platform lock drops when the last holding run lets
     * go), on takes one. Returns the run's new holding flag —
     * the caller keeps releasing in its finally only on true.
     * Never throws; when no lock is available the flag is
     * returned unchanged.
     */
    fun reconcileTransfer(holding: Boolean): Boolean = runCatching {
        counterOrNull()?.reconcile(DownloadCenter.keepCpuAwake, holding)
            ?: holding
    }.getOrDefault(holding)
}
