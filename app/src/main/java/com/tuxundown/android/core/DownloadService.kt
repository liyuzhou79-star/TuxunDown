package com.tuxundown.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tuxundown.android.MainActivity

/**
 * Foreground service that drains the task queue.
 *
 * v1.4: the drain is a small manager loop — up to
 * [DownloadCenter.maxConcurrent] engines run at once, tasks with a
 * future scheduled start wait their turn, and a FAILED task is
 * retried automatically (limited, with backoff) when the user
 * enabled it. Before v1.4 the queue was strictly one-at-a-time.
 */
class DownloadService : Service() {

    @Volatile private var worker: Thread? = null
    @Volatile private var lastNotifyMs = 0L
    /** v2.1 audit (C1/C7): monitors for the two check-then-act pairs
     *  that used to race — the drain-worker start and the
     *  notification throttle gate. */
    private val workerLock = Object()
    private val notifyLock = Object()

    /** v4.4 (start-latency): the drain loop's wake-up signal
     *  (see DrainWakeSignal). Every drain wait is a timed wait
     *  on this signal instead of a plain sleep, so a start
     *  command that arrives while the drain is parked in the
     *  scheduled / gate / deferred waits wakes it to re-judge
     *  the queue at once instead of up to ~30 s late. Signalled
     *  from onStartCommand (the convergence point of EVERY
     *  service start — enqueue kicks, boot, schedule wake,
     *  notification actions) and from the connectivity
     *  callbacks when they release parked tasks. */
    private val drainWake = DrainWakeSignal()

    /** v2.5: recent completions, for the merge window (see
     *  CompletionMerge). Guarded by [completionLock]. */
    private val completionLock = Object()
    private var recentCompletions: List<CompletionMerge.Done> = emptyList()

    /**
     * v2.1 audit (round 7, C1): start the ONE drain loop, atomically.
     * onStartCommand and the connectivity callback both used to do
     * `if (worker?.isAlive != true) worker = Thread{...}` with no
     * synchronization — two callers in the same instant could both
     * pass the check and start TWO drain loops, and two loops could
     * run two engines over the same task (each loop keeps its own
     * running-map). The claim in runOne is the hard invariant; this
     * lock removes the duplicate loop itself.
     */
    private fun ensureWorker() {
        synchronized(workerLock) {
            if (worker?.isAlive == true) return
            worker = Thread { drainQueue() }.apply { isDaemon = true; start() }
        }
    }

    /** v1.5: per-task network-outage recovery attempts spent. */
    private val netAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** v4.4 (audit round 29): portal parks spent per task,
     *  bounded by the same NetRetry.MAX_ATTEMPTS as outages —
     *  a "portal" that never validates is eventually just a
     *  failure with the portal wording on the card. */
    private val portalAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * v4.4 (finalize pipeline): the serial finalize lane. When a
     * transfer finishes, the download thread hands the task to
     * this ONE lane thread — rename + whole-file hash verify +
     * zip extract are disk work, done one at a time — and frees
     * its maxConcurrent slot at once, so the next queued task
     * starts immediately instead of waiting out a multi-GB hash.
     * The lane's accounting lives in [finalizeGate] (see
     * FinalizeGate for the invariants); the task's EngineClaims
     * claim travels WITH the hand-off and is released only when
     * the lane is done, exactly as the claim's contract states.
     * Never shut down: the lane is daemon-threaded and scoped to
     * the process, like TaskStore's save executor — stopping it
     * with the service could interrupt an extract mid-file.
     */
    private val finalizeGate = FinalizeGate()
    private val finalizeExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "finalize-lane").apply { isDaemon = true }
        }

    /** Thrown inside the extract progress callback when the
     *  task's record was deleted mid-extract: ZipExtractor's own
     *  failure handling rolls back the files it already wrote,
     *  so a half-extracted tree is never left behind silently. */
    private class FinalizeAborted : RuntimeException()

    /**
     * v4.5 (queue preheat): transient preheat state (see
     * QueuePreheat for the pure guard decision). [preheatWarmed]
     * holds the once-only marks (task id -> host) of waiting
     * tasks already warmed during this wait — in-memory only,
     * never serialized: a process restart simply re-judges from
     * scratch, which is the truth (the DNS cache and keep-alive
     * pool died with the old process anyway). [preheatInFlight]
     * is the mark of the one preheat currently running, or
     * null. Guarded by [preheatLock]; the drain thread is the
     * only writer of the map besides the preheat thread's
     * in-flight release.
     */
    private val preheatLock = Object()
    private val preheatWarmed = HashMap<String, String>()
    @Volatile private var preheatInFlight: String? = null

    /** v2.8: queue link pre-check thread (see LinkPrecheck).
     *  Runs only while the service lives; the loop is all
     *  runCatching — a pre-check must never disturb a download. */
    @Volatile private var precheckRunning = false
    private var precheckThread: Thread? = null

    /** v1.5: fires when connectivity returns; un-parks deferred tasks. */
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** v1.6 dual channel: the cellular network the system granted
     *  us (null when the environment cannot provide one). */
    @Volatile private var cellularNetwork: Network? = null
    private var cellCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var dualRequested = false
    @Volatile private var dualActive = false

    /** v5.2: every dualActive change also writes the
     *  DownloadCenter mirror the summary card reads (see
     *  DownloadCenter.dualChannelActive) — the two can never
     *  drift because this is the only way dualActive changes
     *  after its initializer. */
    private fun setDualActive(v: Boolean) {
        dualActive = v
        DownloadCenter.dualChannelActive = v
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // v2.2: the crash handler also covers the service
        // process entry (idempotent — see CrashGuard.install).
        CrashGuard.install(this)
        DownloadCenter.init(this)
        // v4.9 (audit round 34): hand WakeGuard its context so
        // DownloadEngine.run() can hold a partial wake lock for
        // the length of a transfer (idempotent — see WakeGuard).
        WakeGuard.attach(this)
        // v2.5: fallback trigger for the deferred startup work
        // when the process was started by the service rather
        // than the UI (one-shot inside; the UI path is primary).
        DownloadCenter.runDeferredStartup()
        // v1.5 startup audit: channel creation is idempotent, but a
        // ROM quirk here must never take the whole service down.
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW)
            )
            // v2.5: completions get their own channel — also
            // LOW (silent by design): whether a completion makes
            // noise is the user's 震动 / 提示音 switches' call
            // (plus quiet hours), not the channel's.
            nm.createNotificationChannel(
                NotificationChannel(
                    COMPLETE_CHANNEL, "下载完成", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        registerNetworkCallback()
        startPrecheck()
    }

    override fun onDestroy() {
        precheckRunning = false
        precheckThread?.interrupt()
        precheckThread = null
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        netCallback = null
        releaseCellular()
        super.onDestroy()
    }

    // ---- v2.8: queue link pre-check (see LinkPrecheck) ----

    private fun startPrecheck() {
        if (precheckThread?.isAlive == true) return
        precheckRunning = true
        precheckThread = Thread { precheckLoop() }.apply {
            isDaemon = true
            name = "link-precheck"
            start()
        }
    }

    /**
     * One pass every [PRECHECK_INTERVAL_MS]: take the front of
     * the queue that is due a check, give each ONE light request,
     * and stamp the record. Only a 404/410 raises the card's
     * warning flag; any other outcome (alive, refused, server
     * error, no network) just refreshes the cache stamp — the
     * flag is a warning, and the task's real start re-probes
     * for itself regardless.
     */
    private fun precheckLoop() {
        while (precheckRunning) {
            runCatching {
                if (!CrashGuard.safeMode) {
                    val candidates = LinkPrecheck.pickCandidates(
                        DownloadCenter.store.tasks, System.currentTimeMillis(),
                    )
                    var changed = false
                    for (t in candidates) {
                        if (!precheckRunning) break
                        val dead = runCatching { probeLinkDead(t) }
                            .getOrDefault(false)
                        if (t.precheckDead != dead) changed = true
                        t.precheckDead = dead
                        t.precheckAtMs = System.currentTimeMillis()
                    }
                    if (changed) DownloadCenter.store.save()
                }
            }
            try {
                Thread.sleep(PRECHECK_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /** One link, one light request: HEAD, with the 1-byte Range
     *  GET fallback for servers that refuse HEAD (the LinkRouter
     *  lesson). Rides the configured proxy and carries the task's
     *  own session headers, exactly like the real probe will. */
    private fun probeLinkDead(task: TaskRecord): Boolean {
        var code = headRequestCode(task, "HEAD")
        if (code == 405 || code == 501) {
            code = headRequestCode(task, "GET")
        }
        return LinkPrecheck.isDeadCode(code)
    }

    private fun headRequestCode(task: TaskRecord, method: String): Int {
        val c = ProxyState.open(java.net.URL(task.url))
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.requestMethod = method
            c.setRequestProperty(
                "User-Agent",
                UserAgents.resolve(task.headers["User-Agent"], DownloadCenter.userAgent),
            )
            task.headers["Cookie"]?.let { c.setRequestProperty("Cookie", it) }
            task.headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
            if (method == "GET") c.setRequestProperty("Range", "bytes=0-0")
            return c.responseCode
        } finally {
            // v4.1 (step A): same keep-alive discipline as the
            // engine's releaseConnection — a HEAD reply has no
            // body and the GET fallback's body is a single byte,
            // so drain-and-close returns the socket to the pool
            // instead of the old unconditional disconnect. A body
            // too big to drain (Range ignored) or an error stream
            // that will not open is disconnected, as before.
            releaseProbeConnection(c)
        }
    }

    /** Drain up to 4 KB to EOF and close; disconnect only when
     *  the body did not end within the cap. Best-effort. */
    private fun releaseProbeConnection(c: java.net.HttpURLConnection) {
        val reusable = runCatching {
            val input = c.inputStream
            val buf = ByteArray(1024)
            var drained = 0
            var eof = false
            while (drained <= 4096) {
                val n = input.read(buf, 0, minOf(buf.size, 4096 + 1 - drained))
                if (n < 0) {
                    eof = true
                    break
                }
                drained += n
            }
            input.close()
            eof
        }.getOrDefault(false)
        if (!reusable) runCatching { c.disconnect() }
    }

    /**
     * v1.6 dual channel: ask the system for a cellular network to
     * download over ALONGSIDE WiFi. Best effort by design — if no
     * cellular network shows up within ~6 s (mobile data off, no
     * signal, carrier/ROM restrictions), [dualActive] stays false
     * and runOne tells the user plainly in the task note.
     */
    private fun acquireCellularIfWanted() {
        dualRequested = DownloadCenter.dualChannel && !DownloadCenter.wifiOnly
        setDualActive(false)
        cellularNetwork = null
        if (!dualRequested) return
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cellularNetwork = network
                    latch.countDown()
                }

                // v4.4 (audit round 29): the cellular leg can
                // also DISAPPEAR mid-session (signal lost,
                // airplane mode). Without onLost the granted
                // Network stayed in cellularNetwork for the
                // whole drain session and every NEW engine
                // kept being built with a dead extraOpener.
                // Clearing here fixes new engines; engines
                // already running captured the Network value,
                // but their per-connection attempt falls back
                // to the default network when the dead leg
                // refuses (see DownloadEngine's dual-channel
                // branch in openFollowingRedirects), and only
                // odd connections try the dead leg first.
                override fun onLost(network: Network) {
                    if (cellularNetwork == network) {
                        cellularNetwork = null
                        setDualActive(false)
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            // The framework-side timeout releases the request on its
            // own if nothing ever becomes available.
            cm.requestNetwork(request, cb, 15_000)
            cellCallback = cb
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        }
        setDualActive(cellularNetwork != null)
    }

    private fun releaseCellular() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cellCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        cellCallback = null
        cellularNetwork = null
        setDualActive(false)
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Connectivity is back: un-park every task that
                    // was deferred by an outage and make sure the
                    // drain loop is running to pick them up.
                    // Portal parks are NOT cleared here: a portal
                    // network is "available" long before its
                    // login completes (see onCapabilitiesChanged).
                    if (clearDeferredParks(portal = false)) {
                        DownloadCenter.store.save()
                        // v4.4: the parks are cleared (state
                        // visible) BEFORE the signal — wake a
                        // drain parked in the deferred wait.
                        drainWake.signal()
                        ensureWorker()
                    }
                }

                // v4.4 (audit round 29): the captive-portal
                // login signal. The portal network was already
                // "available" before login; what changes when
                // the user finishes the login page is that the
                // network gains NET_CAPABILITY_VALIDATED. That —
                // plus the drain loop's own validated poll as a
                // backstop — is what releases a portal park.
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: android.net.NetworkCapabilities,
                ) {
                    if (!caps.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                    ) return
                    if (clearDeferredParks(portal = true)) {
                        DownloadCenter.store.save()
                        drainWake.signal()
                        ensureWorker()
                    }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        }
    }

    /**
     * v4.4 (audit round 29): release parked tasks whose wait is
     * over. Outage parks ([portal] = false) release on plain
     * availability, as since v1.5. Portal parks release ONLY on
     * a validated network — the caller has already established
     * that signal (the capabilities callback, or the drain
     * loop's poll of DownloadCenter.hasValidatedNetwork).
     * Returns true when anything was released.
     */
    private fun clearDeferredParks(portal: Boolean): Boolean {
        var cleared = false
        for (t in DownloadCenter.store.tasks) {
            if (t.status != TaskStatus.QUEUED) continue
            if (t.netDeferred) {
                t.netDeferred = false
                t.note = "网络已恢复，自动继续下载。"
                cleared = true
            }
            if (portal && t.portalDeferred) {
                t.portalDeferred = false
                portalAttempts.remove(t.id)
                t.note = "网络已登录，自动继续下载。"
                cleared = true
            }
        }
        return cleared
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v2.6 recovery entries (audit round 12 — see the matrix
        // in CHECKPOINT). A null intent means START_STICKY is
        // bringing back a service the system killed mid-work:
        // QUEUED tasks resume through the drain on their own, but
        // tasks that were RUNNING reload parked as PAUSED (the
        // standing load rule) and used to stall there forever —
        // re-queue exactly those. The boot action is the same
        // recovery behind the user's (default-off) 开机恢复
        // switch. CrashGuard safe mode (a crash streak) skips
        // both: after repeated crashes the user decides what
        // runs next, not an automatic path.
        if (intent == null && !CrashGuard.safeMode) {
            DownloadCenter.recoverInterrupted()
        }
        if (intent?.action == ACTION_BOOT_RESUME &&
            DownloadCenter.bootResume && !CrashGuard.safeMode
        ) {
            DownloadCenter.recoverInterrupted()
        }
        // v3.5: every service start re-points the scheduled-
        // start backup alarm at the earliest future slot (see
        // ScheduleWake) — the catch-all arm point, since every
        // queue change either starts this service or happens
        // while it runs.
        ScheduleWake.arm(this, DownloadCenter.store.tasks)
        // v1.8: notification action buttons land here. Pause goes
        // through DownloadCenter like a card tap; cancel mirrors
        // the card's delete (a RUNNING task is cancelled through
        // the engine-owner flow so the file is deleted only after
        // the engine has fully stopped; anything else is removed
        // directly, keeping the finished file for DONE tasks).
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
                DownloadCenter.store.byId(id)?.let { DownloadCenter.pauseTask(it) }
            }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_TASK_ID)?.let { id ->
                val t = DownloadCenter.store.byId(id)
                if (t != null) {
                    // v2.1 audit (C8): a task inside its retry
                    // backoff is not RUNNING but IS owned by a live
                    // runOne — deleting its file/record directly
                    // raced that owner (it would rebuild the target
                    // and download a ghost). Claimed tasks always go
                    // through the cancel flow; the owner deletes.
                    if (t.status == TaskStatus.RUNNING || DownloadCenter.isClaimed(t.id)) {
                        DownloadCenter.cancelTask(t)
                    } else {
                        Thread {
                            if (t.status != TaskStatus.DONE) {
                                PublicStorage.deleteTarget(this, t)
                            }
                            DownloadCenter.store.remove(t)
                        }.start()
                    }
                }
            }
        }
        // v3.9 audit round 25: pass the foreground-service type
        // EXPLICITLY. The manifest has declared dataSync (and the
        // FOREGROUND_SERVICE_DATA_SYNC permission) all along, and
        // the two-arg call would inherit it — but Android 14+
        // judges the type at this call, and naming it here keeps
        // the declaration and the call from ever drifting apart.
        // The typed overload and the constant both exist since
        // API 29, which is exactly minSdk.
        startForeground(
            NOTIF_ID,
            buildNotification("准备下载…", "", 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // v4.4 (start-latency): wake the drain. EVERY service
        // start converges here — DownloadCenter.enqueue's kick
        // (both branches), BootReceiver, ScheduleReceiver, the
        // deferred-startup recovery kick, notification actions —
        // and the state each of them changed (a freshly QUEUED
        // task, a released park, the recovery re-queue above)
        // is already visible in the store at this point, which
        // is exactly the sender ordering DrainWakeSignal's
        // no-lost-signal paradigm requires: state first, signal
        // second. A drain parked in a wait re-loops and
        // re-judges at once; a drain not yet started is covered
        // by ensureWorker + its first evaluation.
        drainWake.signal()
        ensureWorker()
        return START_STICKY
    }

    /**
     * v3.9 audit round 25: Android 15 puts a cumulative runtime
     * budget on dataSync foreground services — about 6 hours in
     * any 24-hour window while the app stays in the background
     * (the counter resets when the app comes to the foreground).
     * When the budget runs out the system calls this and expects
     * the service to stop itself promptly; ignoring it gets the
     * service force-stopped with the work in an unknown state.
     * Our answer is the graceful one: pause everything through
     * the normal path (engines stop at chunk boundaries, every
     * byte of progress is already persisted, the cards explain
     * what happened) and stop. Nothing is lost — the user taps
     * 继续 to resume, exactly like a manual pause. Both overloads
     * are overridden; the system calls the two-arg form for a
     * typed service. On API < 35 neither is ever called.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        onSystemTimeout()
    }

    override fun onTimeout(startId: Int) {
        onSystemTimeout()
    }

    private fun onSystemTimeout() {
        runCatching { DownloadCenter.pauseAllForSystemTimeout() }
        runCatching { stopSelf() }
    }

    /**
     * v4.4 (start-latency): one drain wait — block on the wake
     * signal for at most [timeoutMs], against the pass's
     * snapshot [since]. Returns false ONLY on a real thread
     * interrupt, which keeps its pre-v4.4 meaning (the caller
     * breaks the loop into stopSelf, exactly as the old
     * sleeps' catches did). A signal or a natural timeout both
     * return true: either way the loop re-judges from the top.
     * The wait is a blocking monitor wait for the full timeout
     * when nothing happens — never a shortened poll.
     */
    private fun drainWait(timeoutMs: Long, since: Long): Boolean {
        return try {
            drainWake.await(timeoutMs, since)
            true
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun drainQueue() {
        val store = DownloadCenter.store
        // v1.6: dual channel is negotiated once per drain session.
        acquireCellularIfWanted()
        val running = mutableMapOf<String, Thread>()
        while (true) {
            // v4.4 (start-latency): snapshot the wake generation
            // BEFORE this pass evaluates anything (see
            // DrainWakeSignal's doc for the paradigm): a signal
            // landing anywhere in this pass — before, during or
            // after the evaluation — either is covered by what
            // the evaluation sees, or cuts the pass's wait short.
            val wakeGen = drainWake.snapshot()
            // Reap finished engine threads.
            val iter = running.entries.iterator()
            while (iter.hasNext()) {
                if (!iter.next().value.isAlive) iter.remove()
            }
            // Start eligible tasks up to the concurrency setting.
            while (running.size < DownloadCenter.maxConcurrent) {
                val task = QueueOrder.nextEligible(
                    store.tasks, System.currentTimeMillis(), running.keys
                ) ?: break
                if (DownloadCenter.wifiOnly && !DownloadCenter.isOnWifi()) {
                    task.note = "已开启「仅 WiFi 下载」，等待 WiFi 网络。"
                    store.save()
                    break
                }
                // v3.9: the charge gate (see ChargeGuard), checked
                // AFTER the Wi-Fi gate on purpose: when both are
                // shut, the card names the first closed gate
                // (Wi-Fi, re-stamped on every pass above), and
                // each gate's release is announced on its own.
                // Like Wi-Fi, this holds only tasks that have not
                // started — unplugging never interrupts a running
                // transfer.
                if (ChargeGuard.shouldPark(
                        DownloadCenter.chargeOnlyDownload,
                        DownloadCenter.isCharging(),
                    )
                ) {
                    task.chargeDeferred = true
                    task.note = "已开启「充电时才下载」：现在没接电源，" +
                        "这个任务先等着，插上电源会自动开始。"
                    store.save()
                    break
                }
                // Both gates passed: if this task was parked
                // waiting for power, say it is starting now.
                if (task.chargeDeferred) {
                    task.chargeDeferred = false
                    task.note = "已接通电源，开始下载。"
                    store.save()
                }
                // v4.5 (queue preheat): this task is really
                // starting — its once-only preheat mark goes
                // away, so if it ever queues again it may be
                // warmed again (see QueuePreheat).
                clearPreheatMark(task.id)
                // v4.8: host-rotation memory — recorded HERE, at
                // the point the task is definitely starting (the
                // thread is created just below), so a task a gate
                // (Wi-Fi / charge) turned away above does NOT
                // count as served and cannot skew the rotation.
                QueueOrder.noteServed(task)
                val th = Thread {
                    // v4.4 (finalize pipeline): when runOne handed
                    // the task to the finalize lane, the daily
                    // re-schedule decision travels with it — the
                    // lane makes it AFTER finalize completes,
                    // because finalize can still flip DONE to
                    // FAILED (target gone) and a decision made
                    // here would read a status that is not final
                    // yet (and could reset a task whose extract
                    // is still writing through its Uri).
                    val handedOff = runOne(task)
                    if (!handedOff) rescheduleDailyIfNeeded(task)
                }
                th.isDaemon = true
                running[task.id] = th
                th.start()
            }
            // v4.5 (queue preheat): judged HERE, on the drain's
            // own beat — once per pass, after this pass's starts
            // are settled. The drain's passes are already paced
            // by its waits (the 400 ms reap poll while tasks run,
            // the longer gate / deferred / scheduled waits when
            // idle), so the preheat check can never run more
            // often than the loop the service already lives by,
            // and no new polling exists anywhere. The decision
            // itself is QueuePreheat.decide (pure, all guards);
            // a hit only spawns the background warm thread.
            maybeQueuePreheat()
            val anyQueued = store.tasks.any { it.status == TaskStatus.QUEUED }
            if (running.isEmpty()) {
                // v4.4 (finalize pipeline): a task whose transfer
                // is done but whose finalize is still on the lane
                // keeps the service alive, exactly like a deferred
                // task does — falling through to stopSelf here
                // would kill a hash verify / an extract mid-file.
                // Poll briefly; the lane's completion path owns
                // everything that happens next for that task.
                if (finalizeGate.pendingCount() > 0) {
                    if (!drainWait(500, wakeGen)) break
                    continue
                }
                // v1.7 audit (B2): tasks that are eligible but blocked
                // ONLY by the 仅 WiFi switch keep the service alive on
                // a 5 s poll — when WiFi returns they start on their
                // own instead of waiting for the user to reopen the
                // app (enqueue now starts the service in this case).
                val wifiBlocked = DownloadCenter.wifiOnly && !DownloadCenter.isOnWifi() &&
                    store.tasks.any {
                        it.status == TaskStatus.QUEUED && !it.netDeferred &&
                            it.scheduledAt <= System.currentTimeMillis()
                    }
                if (wifiBlocked) {
                    notifyUpdate(
                        "等待 WiFi",
                        "已开启「仅 WiFi 下载」，连上 WiFi 后自动开始",
                        0,
                    )
                    if (!drainWait(5000, wakeGen)) break
                    continue
                }
                // v3.9: the same idle wait for the charge gate —
                // tasks parked for power keep the service alive
                // on the same 5 s poll until plugged in.
                val chargeBlocked = DownloadCenter.chargeOnlyDownload &&
                    !DownloadCenter.isCharging() &&
                    store.tasks.any {
                        it.status == TaskStatus.QUEUED && !it.netDeferred &&
                            it.scheduledAt <= System.currentTimeMillis()
                    }
                if (chargeBlocked) {
                    notifyUpdate(
                        "等待充电",
                        "已开启「充电时才下载」，插上电源后自动开始",
                        0,
                    )
                    if (!drainWait(5000, wakeGen)) break
                    continue
                }
                // v1.5: tasks parked by a network outage keep the
                // service (and its network callback) alive until
                // connectivity returns — they are QUEUED but not
                // eligible, so without this branch the service would
                // stop and nobody would ever un-park them.
                // v4.6 (audit round 31): ALL park flags count,
                // chargeDeferred included — see
                // FinalizeGate.anyParked for the race this
                // closes (a charge park whose second battery
                // read flipped used to fall through to the
                // nextAt<=0 exit below with nobody watching).
                val anyDeferred = FinalizeGate.anyParked(store.tasks)
                // v4.4 (finalize pipeline): the exit verdict goes
                // through FinalizeGate — pending finalize work
                // vetoes the exit even if this pass somehow
                // reached here without the wait above.
                if (FinalizeGate.drainMayExit(
                        anyQueued, anyDeferred, finalizeGate.pendingCount()
                    )
                ) break
                if (anyDeferred &&
                    QueueOrder.nextEligible(store.tasks, System.currentTimeMillis()) == null
                ) {
                    // v4.4 (audit round 29): backstop release
                    // for portal parks — if the capabilities
                    // callback was missed but the network IS
                    // validated now, the login is done; let
                    // the tasks go on this very pass.
                    if (store.tasks.any {
                            it.portalDeferred && it.status == TaskStatus.QUEUED
                        } && DownloadCenter.hasValidatedNetwork()
                    ) {
                        if (clearDeferredParks(portal = true)) {
                            store.save()
                            continue
                        }
                    }
                    // v5.3 (audit round 38): the wait text names
                    // the park that is actually holding the queue
                    // (NetRetry.deferredWaitText) — a portal-only
                    // park is 等待 WiFi 登录， not 等待网络恢复
                    // (the network is up; only the login is
                    // missing, exactly as the card's note says).
                    // anyDeferred above guarantees a non-null
                    // verdict here; the fallback is the old text.
                    val waitText = NetRetry.deferredWaitText(
                        hasNetParked = store.tasks.any { it.netDeferred },
                        hasPortalParked = store.tasks.any { it.portalDeferred },
                    ) ?: ("等待网络恢复" to "断网的任务已排队，网络一回来就自动继续")
                    notifyUpdate(waitText.first, waitText.second, 0)
                    if (!drainWait(5000, wakeGen)) break
                    continue
                }
                // Only future-scheduled tasks remain: wait for the
                // earliest one (polling, so a newly enqueued task or
                // a changed setting is picked up within seconds).
                val nextAt = QueueOrder.nextScheduledAt(store.tasks, System.currentTimeMillis())
                if (nextAt <= 0) {
                    // Nothing scheduled — but outage-parked tasks still
                    // need the callback alive, so wait instead of dying.
                    // v4.4: pending finalize work is the same kind of
                    // reason (defensive — the wait at the top of this
                    // branch normally catches it first).
                    if (anyDeferred || finalizeGate.pendingCount() > 0) {
                        if (!drainWait(5000, wakeGen)) break
                        continue
                    }
                    break
                }
                notifyUpdate(
                    "预约下载待机",
                    "下一个任务将在 ${Format.clockTime(nextAt)} 开始",
                    0,
                )
                // v4.4: the wait quantum itself is unchanged
                // (DrainWakeSignal.scheduledWaitMs — the same
                // 2 s..30 s clamp), but the wait is now a timed
                // signal wait: a task enqueued while the drain
                // sits here starts on the next pass, not up to
                // 30 s later when the sleep happened to expire.
                val waitMs = DrainWakeSignal.scheduledWaitMs(
                    nextAt, System.currentTimeMillis()
                )
                if (!drainWait(waitMs, wakeGen)) break
            } else {
                if (!drainWait(400, wakeGen)) break
            }
        }
        releaseCellular()
        // v2.1: settle the widget on the final state before the
        // service goes away (no more posts will come from here).
        com.tuxundown.android.widget.WidgetUpdater.refresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** v4.5 (queue preheat): drop a task's once-only mark. */
    private fun clearPreheatMark(taskId: String) {
        synchronized(preheatLock) { preheatWarmed.remove(taskId) }
    }

    /**
     * v4.5 (queue preheat): one guard judgement on the drain's
     * beat (see the call site in drainQueue) and, when
     * [QueuePreheat.decide] approves a target, its background
     * warm. Everything here is best-effort optimisation: no
     * task field is ever written, no slot / limiter / budget is
     * touched, and every failure is silent — the real start
     * re-does everything for itself regardless.
     */
    private fun maybeQueuePreheat() {
        val store = DownloadCenter.store
        // Prune marks whose wait is over (deleted / paused /
        // finished tasks): a mark only has meaning while its
        // task sits QUEUED. (A real start clears its own mark
        // in the drain's start loop; this covers the exits that
        // never pass through it.)
        synchronized(preheatLock) {
            val iter = preheatWarmed.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                val t = store.byId(e.key)
                if (t == null || t.status != TaskStatus.QUEUED) iter.remove()
            }
        }
        val warmedKeys = synchronized(preheatLock) {
            preheatWarmed.map { (id, host) -> QueuePreheat.keyFor(id, host) }.toSet()
        }
        val target = QueuePreheat.decide(
            tasks = store.tasks,
            now = System.currentTimeMillis(),
            onWifi = runCatching { DownloadCenter.isOnWifi() }.getOrDefault(false),
            dualActive = dualActive,
            // Traffic protection is ON exactly when a threshold
            // is set (DownloadCenter.trafficThresholdMB: 0 = off).
            trafficProtectionOn = DownloadCenter.trafficThresholdMB > 0,
            // v5.0 (audit round 35): the charge park as a LIVE
            // condition (same pair the drain's charge gate
            // judges) — while it holds, no queued task can
            // start, so warming one would only waste battery.
            chargeParked = ChargeGuard.shouldPark(
                DownloadCenter.chargeOnlyDownload,
                runCatching { DownloadCenter.isCharging() }.getOrDefault(true),
            ),
            proxyActive = DownloadCenter.proxySettings().active,
            alreadyWarmed = warmedKeys,
            inFlightKey = preheatInFlight,
        ) ?: return
        // Claim the one-at-a-time slot and the once-only mark
        // BEFORE spawning, so the next drain pass (400 ms later)
        // already sees both and cannot double-fire.
        synchronized(preheatLock) {
            if (preheatInFlight != null) return
            preheatInFlight = target.key
            preheatWarmed[target.taskId] = target.host
        }
        Thread { warmQueuedHost(target) }.apply {
            isDaemon = true
            name = "queue-preheat"
            start()
        }
    }

    /**
     * The queue preheat itself, on its own daemon thread:
     * DNS pre-resolve + ONE Range bytes=0-0 request for the
     * target's primary link, reusing the standing primitives —
     * the host decision is [Warmup.hostToPreResolve]'s (already
     * applied by the guard), the lookup is the same
     * getAllByName the enqueue pre-resolve uses, and the 1-byte
     * body is drained through this service's own
     * [releaseProbeConnection] discipline, so a 206 socket lands
     * in the platform keep-alive pool exactly like a probe's.
     * The task's session headers ride along (same set the
     * pre-check sends), so the warmed connection is one the
     * real probe may actually reuse.
     *
     * Anti-duplication: the host is claimed in [WarmHosts] for
     * the whole warm — if the engine's run-start warmup (the
     * task started meanwhile) or anything else is already
     * warming this host, this thread does nothing, and vice
     * versa the engine skips a host this thread holds.
     *
     * Stale-target discard: the target is re-judged
     * ([QueuePreheat.stillTarget]) after the DNS step and again
     * after the request. A target that was deleted / paused /
     * started, or lost first place in the queue, gets NO pooled
     * connection (disconnect instead) and — as everywhere in
     * preheat — no field writes of any kind. The DNS answer
     * itself lives in the platform cache and cannot be
     * un-resolved; that is the same harmless residue the
     * v4.4 enqueue-time pre-resolve already leaves, and the
     * real start never trusts it beyond a connection attempt.
     */
    private fun warmQueuedHost(target: QueuePreheat.Target) {
        try {
            if (!WarmHosts.tryClaim(target.host)) return
            try {
                runCatching { java.net.InetAddress.getAllByName(target.host) }
                val store = DownloadCenter.store
                if (!QueuePreheat.stillTarget(
                        store.tasks, System.currentTimeMillis(),
                        target.taskId, target.host,
                    )
                ) return
                val task = store.byId(target.taskId) ?: return
                var conn: java.net.HttpURLConnection? = null
                var poolIt = false
                try {
                    // Proxy was a guard veto, so this open is
                    // direct by construction; ProxyState.open is
                    // still the standing opener (it would honour
                    // a proxy configured in the meantime — in
                    // which case the pooled socket is the
                    // proxy's, equally harmless).
                    val c = ProxyState.open(java.net.URL(target.url))
                    conn = c
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty(
                        "User-Agent",
                        UserAgents.resolve(
                            task.headers["User-Agent"], DownloadCenter.userAgent
                        ),
                    )
                    c.setRequestProperty("Accept-Encoding", "identity")
                    task.headers["Cookie"]?.let {
                        c.setRequestProperty("Cookie", it)
                    }
                    task.headers["Referer"]?.let {
                        c.setRequestProperty("Referer", it)
                    }
                    c.setRequestProperty("Range", "bytes=0-0")
                    val code = c.responseCode
                    // Only a 206's 1-byte body may be pooled;
                    // anything else is disconnected by
                    // releaseProbeConnection's cap discipline
                    // or directly here. A stale target's socket
                    // is never pooled (see the doc above).
                    poolIt = code == 206 && QueuePreheat.stillTarget(
                        store.tasks, System.currentTimeMillis(),
                        target.taskId, target.host,
                    )
                    if (!poolIt) runCatching { c.disconnect() }
                } finally {
                    if (poolIt) {
                        conn?.let { releaseProbeConnection(it) }
                    } else {
                        // Non-206, stale target, or a failed
                        // request: never pooled (a second
                        // disconnect after the one above is a
                        // no-op).
                        conn?.let { runCatching { it.disconnect() } }
                    }
                }
            } finally {
                WarmHosts.release(target.host)
            }
        } catch (e: Exception) {
            // Preheat failures are silent by design (see doc).
        } finally {
            synchronized(preheatLock) {
                if (preheatInFlight == target.key) preheatInFlight = null
            }
        }
    }

    /**
     * Run one task to a terminal state, with automatic retries.
     *
     * Returns true when the task was handed to the finalize lane:
     * the EngineClaims claim then travels with the hand-off (the
     * lane releases it when finalize completes) and the caller
     * must NOT make the daily re-schedule decision — the lane
     * makes it after finalize, on the final status.
     */
    private fun runOne(task: TaskRecord): Boolean {
        // v2.1 audit (round 7, C1): claim the task for the WHOLE
        // invocation — transfer, retry backoff and finalize — so no
        // second runOne for this task can ever overlap it, whatever
        // thread or drain loop asks. A refused claim means another
        // run already owns the task; step aside silently.
        if (!DownloadCenter.claimEngine(task.id)) return false
        var handedOff = false
        try {
            handedOff = runOneClaimed(task)
        } finally {
            if (!handedOff) DownloadCenter.releaseEngine(task.id)
        }
        return handedOff
    }

    /** True only on the DONE hand-off to the finalize lane. */
    private fun runOneClaimed(task: TaskRecord): Boolean {
        val store = DownloadCenter.store
        while (true) {
            // The record may have been deleted from the UI in the
            // same instant we picked it up; never download a ghost.
            if (store.byId(task.id) == null) return false
            // v2.1 audit (C4): the user can tap pause in the instant
            // between the drain loop picking this task and this
            // thread starting — the engine is not registered yet, so
            // the pause has nothing to bite on, and the old code
            // then overwrote PAUSED with RUNNING and downloaded
            // anyway. A first entry must still find the task QUEUED
            // (the retry loop re-enters through its own QUEUED set
            // below); PAUSED here is the user's intent — honour it.
            if (task.status == TaskStatus.PAUSED) return false
            task.status = TaskStatus.RUNNING
            // v2.8: the real run supersedes any pre-check warning —
            // the engine's own probe is about to give the true
            // answer, so the stale flag must not outlive the start.
            task.precheckDead = false
            // v1.6 dual channel honesty: the card says whether the
            // second network really came up — never fake it.
            if (dualRequested && !task.note.contains("双通道")) {
                val dualNote = if (dualActive) {
                    "双通道已开启：WiFi 和流量两条网络一起下这个文件。"
                } else {
                    "双通道没开成：当前环境拿不到可用的流量网络" +
                        "（移动数据没开、没信号或运营商限制），已用单条网络下载。"
                }
                task.note = if (task.note.isBlank()) dualNote else task.note + " " + dualNote
            }
            store.save()
            val legacy = store.fileOf(task)
            val engine = DownloadEngine(
                task = task,
                targetProvider = {
                    val obtained = PublicStorage.obtainTarget(this, task)
                    // One-time copy of any <= v1.1 private partial into
                    // the new public .part; afterwards the private
                    // directory is never used for downloads again.
                    PublicStorage.migrateLegacyIfNeeded(task, obtained, legacy)
                    store.save()
                    obtained.target
                },
                onProgress = { notifyAggregate() },
                onSave = { store.save() },
                // User speed limit: v1.6 turns it into a TOTAL
                // budget shared by all running tasks (weighted by
                // priority), read live so changes rebalance at once.
                // v2.1: the task's own cap composes on top — the
                // smaller of the two limits wins (see RateCap).
                limiter = SpeedLimiter(rateBps = {
                    RateCap.effectiveBps(
                        DownloadCenter.budgetShareBps(task.id),
                        task.taskLimitKBps,
                    )
                }),
                networkGuard = {
                    !(DownloadCenter.wifiOnly && !DownloadCenter.isOnWifi())
                },
                // v1.4 traffic protection: on mobile data, an
                // oversized task pauses instead of burning data,
                // unless the user confirmed this task. v1.6: an
                // ACTIVE dual channel is that confirmation — the
                // user explicitly asked to spend mobile data.
                trafficGuard = {
                    dualActive || !TrafficGuard.shouldBlock(
                        onWifi = DownloadCenter.isOnWifi(),
                        totalSize = task.totalSize,
                        thresholdMB = DownloadCenter.trafficThresholdMB,
                        confirmed = task.mobileConfirmed,
                    )
                },
                // v2.3 space pre-check: the engine consults this
                // once the size is known. Other RUNNING tasks
                // reserve their remaining bytes, so tasks starting
                // together cannot all promise themselves the same
                // free space (see SpaceCheck for the accounting).
                spaceGuard = {
                    val needed = SpaceCheck.remainingOf(
                        task.totalSize, task.downloaded, task.singleThreadOnly,
                    )
                    val reserved = SpaceCheck.reservedOf(
                        store.tasks.filter {
                            it.id != task.id && it.status == TaskStatus.RUNNING
                        }.map {
                            SpaceCheck.remainingOf(
                                it.totalSize, it.downloaded, it.singleThreadOnly,
                            )
                        }
                    )
                    val avail = PublicStorage.availableBytes(this)
                    when (val v = SpaceCheck.assess(avail, needed, reserved)) {
                        is SpaceCheck.Verdict.Insufficient ->
                            SpaceCheck.blockMessage(task.totalSize, avail, v.missingBytes)
                        else -> null
                    }
                },
                // v1.6 dual channel: alternate connections ride the
                // cellular network the system granted us.
                // v1.9: when a proxy is configured it applies on
                // the cellular leg too (openConnection(url, proxy)).
                extraOpener = if (dualActive) {
                    cellularNetwork?.let { net ->
                        val px = DownloadCenter.proxySettings().javaProxy()
                        if (px != null) {
                            { u: java.net.URL ->
                                net.openConnection(u, px) as java.net.HttpURLConnection
                            }
                        } else {
                            { u: java.net.URL ->
                                net.openConnection(u) as java.net.HttpURLConnection
                            }
                        }
                    }
                } else null,
                // v1.7: the user's custom User-Agent setting; a UA
                // captured for THIS task still wins (UserAgents).
                customUserAgent = DownloadCenter.userAgent,
                // v1.9: the app-wide download proxy (default off).
                proxySettings = DownloadCenter.proxySettings(),
                // v5.1: the process-wide memory tier (decided once
                // in DownloadCenter); the engine snapshots its
                // budget table at construction = this run's start.
                memoryTier = DownloadCenter.memoryTier(),
                // v1.5: per-host thread memory — start known hosts at
                // their remembered optimum, remember new optima, and
                // age the memory out when a host starts failing.
                initialThreadsHint = DownloadCenter.hostThreadsHint(task.url),
                onThreadsSettled = { threads ->
                    DownloadCenter.recordHostThreads(task.url, threads)
                },
                onHostFailed = { DownloadCenter.recordHostFailure(task.url) },
                // v2.8: rate-limit memory — a remembered ceiling
                // caps this run; a run that fails with a
                // rate-limit signature teaches a new one.
                initialThreadsCap = DownloadCenter.hostThreadsCap(task.url),
                onRateLimited = { atLimit ->
                    DownloadCenter.recordHostRateLimit(task.url, atLimit)
                },
                // v3.9: the global connection cap, split live
                // across the running tasks by priority weight
                // (see ConnectionBudget / DownloadCenter).
                connectionAllowance = {
                    DownloadCenter.connectionAllowanceFor(task.id)
                },
            )
            DownloadCenter.registerEngine(task, engine)
            try {
                // v2.1 audit (C8): last-moment existence re-check —
                // a direct delete that landed while the engine was
                // being built must not leave us downloading a ghost
                // whose record (and target) are already gone. Any
                // delete arriving after this point sees the task
                // RUNNING and routes through cancelTask instead.
                if (store.byId(task.id) == null) return false
                val runResult = engine.run()
                if (DownloadCenter.consumeCancelled(task)) {
                    // Audit A7: the user deleted this task while it
                    // was running. The engine has fully stopped NOW,
                    // so it is finally safe to remove the file and
                    // the record.
                    PublicStorage.deleteTarget(this, task)
                    store.remove(task)
                    return false
                }
                // v2.1 audit (C9): decide completion from the
                // engine's RETURNED status, not the shared field —
                // a pause tap landing in the engine's final instant
                // could overwrite task.status with PAUSED after the
                // last byte landed, and the old check then never
                // finalized a byte-complete file (it sat as .part
                // until the user happened to resume). The bytes are
                // all there; DONE is the truth.
                if (runResult == TaskStatus.DONE) {
                    task.status = TaskStatus.DONE
                    netAttempts.remove(task.id)
                    portalAttempts.remove(task.id)
                    // v4.4 (finalize pipeline): the transfer is
                    // done — hand finalize (rename + verify +
                    // extract + completion side effects, the
                    // WHOLE finalizeFinished body) to the serial
                    // lane and free this download slot NOW, so the
                    // next queued task starts without waiting out
                    // a whole-file hash or a big unzip. The claim
                    // travels with the hand-off (runOne sees the
                    // true return and does not release it; the
                    // lane releases it at the end).
                    if (submitFinalize(task)) return true
                    // The lane refused the hand-off (a duplicate
                    // submit — impossible while the claim is held,
                    // kept as a defensive fallback): finalize
                    // inline, exactly as before v4.4.
                    finalizeFinished(task)
                    // v2.1 audit (C3): a delete tap that landed
                    // DURING finalize (after the check above) used
                    // to be silently dropped — the flag stayed in
                    // the cancelled set and the task survived as
                    // DONE. Honour it now: the record goes away,
                    // the finished file stays — exactly the
                    // semantics of deleting a DONE card.
                    if (DownloadCenter.consumeCancelled(task)) {
                        store.remove(task)
                    }
                    return false
                }
            } finally {
                DownloadCenter.unregisterEngine(task)
                // v1.7: an engine run reaching its end is a milestone
                // (done / paused / failed with full progress state) —
                // flush NOW instead of riding the coalesced window.
                store.saveNow()
            }
            // v4.4 (audit round 29): a failure stopped by a
            // captive portal is not the server's fault either —
            // park the task until the network reports VALIDATED
            // (the user finished the WiFi login), bounded like
            // the outage park. Only park while the network is
            // genuinely unvalidated: on a validated network an
            // HTML answer means the link itself is a web page,
            // and the engine's plain wording stays as the
            // failure. The park is judged on the engine's raw
            // verdict (task.portalHit), never on card text.
            if (task.status == TaskStatus.FAILED && task.portalHit &&
                !DownloadCenter.hasValidatedNetwork()
            ) {
                val used = portalAttempts[task.id] ?: 0
                if (NetRetry.shouldDefer(used)) {
                    portalAttempts[task.id] = used + 1
                    task.portalDeferred = true
                    task.status = TaskStatus.QUEUED
                    task.note = "${PortalDetect.PARK_NOTE_PREFIX}" +
                        "（第 ${used + 1}/${NetRetry.MAX_ATTEMPTS} 次等待）"
                    task.error = ""
                    store.save()
                    notifyUpdate(
                        "等待登录 WiFi",
                        task.fileName,
                        WidgetState.taskPercent(task),
                    )
                    return false
                }
                portalAttempts.remove(task.id)
            }
            // v1.5: a failure caused by the network going away is not
            // the server's fault and must not burn the v1.4 retry
            // budget — park the task and let the connectivity
            // callback re-queue it when the network returns (bounded
            // by NetRetry.MAX_ATTEMPTS per outage streak).
            // v4.4 (audit round 29): the judgement reads the
            // engine's RAW-failure verdict (task.netOutage). The
            // old form classified task.error as a string — dead
            // since v2.2, when Errors.human started replacing
            // every engine failure text with plain Chinese that
            // contains none of the classifier's English needles.
            if (task.status == TaskStatus.FAILED && task.netOutage) {
                val used = netAttempts[task.id] ?: 0
                if (NetRetry.shouldDefer(used)) {
                    netAttempts[task.id] = used + 1
                    task.netDeferred = true
                    task.status = TaskStatus.QUEUED
                    task.note = "网络好像断了，先排队等网络恢复后自动继续" +
                        "（第 ${used + 1}/${NetRetry.MAX_ATTEMPTS} 次）。原因：${task.error}"
                    task.error = ""
                    store.save()
                    notifyUpdate(
                        "网络断开，等待恢复",
                        task.fileName,
                        WidgetState.taskPercent(task),
                    )
                    return false
                }
                netAttempts.remove(task.id)
            }
            // v1.4: automatic whole-task retry with backoff.
            // Audit round 28: failures only the user can fix
            // (expired SAF grant, storage device gone) are never
            // auto-retried — every attempt fails the same way and
            // the retry notes bury the real reason. The task
            // stays FAILED with its reason on the card.
            val limit = DownloadCenter.autoRetryCount
            if (task.status != TaskStatus.FAILED ||
                RetryPolicy.needsUserAction(task) ||
                !RetryPolicy.shouldRetry(task.autoRetriesUsed, limit)
            ) {
                return false
            }
            val waitMs = RetryPolicy.backoffMs(task.autoRetriesUsed)
            task.autoRetriesUsed++
            task.note = "下载失败，将在 ${waitMs / 1000} 秒后自动重试" +
                "（第 ${task.autoRetriesUsed}/$limit 次）。原因：${task.error}"
            store.save()
            notifyUpdate(
                "下载失败，稍后自动重试",
                "${task.fileName} · 第 ${task.autoRetriesUsed}/$limit 次",
                WidgetState.taskPercent(task),
            )
            if (!sleepBackoff(waitMs, task)) return false
            // v2.1 audit (round 7, C2): a delete that landed DURING
            // the backoff went unnoticed here — the record is still
            // present (the service owns its deletion) and the status
            // is still FAILED, so the old code re-queued and started
            // a whole new engine run for a task the user had already
            // deleted; the cancel flag was only consumed after that
            // run ended. Honour the delete NOW: file + record go,
            // no extra download.
            if (DownloadCenter.consumeCancelled(task)) {
                PublicStorage.deleteTarget(this, task)
                store.remove(task)
                return false
            }
            // If the user took over meanwhile (paused / re-queued /
            // deleted), step aside and let the normal flow own it.
            if (store.byId(task.id) == null || task.status != TaskStatus.FAILED) return false
            task.status = TaskStatus.QUEUED
            task.error = ""
        }
    }

    /**
     * v1.8: a daily-scheduled task that reached a terminal state
     * (DONE, or FAILED after its retries ran out) re-queues itself
     * for the same wall-clock time on the next day instead of
     * ending. A PAUSED task is left alone — the user stopped it on
     * purpose. The byte state is fully reset: tomorrow's run is a
     * FRESH download of whatever the URL serves then (validators
     * cleared, target cleared so the finished file is never
     * reopened — the new copy lands as a "name (1)" sibling at
     * finalize, like any same-name download).
     */
    private fun rescheduleDailyIfNeeded(task: TaskRecord) {
        if (!task.scheduleDaily) return
        if (task.status != TaskStatus.DONE && task.status != TaskStatus.FAILED) return
        val store = DownloadCenter.store
        if (store.byId(task.id) == null) return // deleted meanwhile
        val next = ScheduleTime.advanceDaily(
            task.scheduledAt, System.currentTimeMillis()
        )
        task.chunkDone = BooleanArray(0)
        task.chunkSize = 0
        // v4.2: a fresh download gets a fresh grid — the old
        // grid's fine zone must not leak into it.
        task.fineChunkSize = 0
        task.fineChunkCount = 0
        task.downloaded = 0
        task.totalSize = -1
        task.etag = ""
        task.lastModified = ""
        task.targetUri = ""
        task.savedUri = ""
        task.savedName = ""
        task.speedBps = 0
        task.elapsedMs = 0
        task.finishedAt = 0
        task.retryCount = 0
        task.autoRetriesUsed = 0
        task.error = ""
        task.failureKind = FailureKind.UNKNOWN
        task.failureHttpCode = 0
        task.checksumState = Checksum.STATE_NONE
        task.hlsDone = BooleanArray(0)
        task.hlsTotalSegments = 0
        task.hlsFingerprint = ""
        synchronized(task.hlsSizes) { task.hlsSizes.clear() }
        // v4.6: the per-source byte accounting describes the
        // file that just finished — tomorrow's fresh download
        // starts its own ledger (the peak stats, like peakBps,
        // deliberately survive this reset; see TaskRecord).
        synchronized(task.sourceBytes) { task.sourceBytes.clear() }
        task.scheduledAt = next
        task.status = TaskStatus.QUEUED
        task.note = "每天定时：已排到下一次 ${Format.clockTime(next)} 开始。" +
            "上一次的文件已保存在下载目录里（重名时会自动改成带 (1) 的名字）。"
        store.saveNow()
        // v3.5: the slot just moved a day out — re-point the
        // backup alarm at it (see ScheduleWake).
        ScheduleWake.arm(this, store.tasks)
    }

    /** Sleep in small slices; false if the task vanished meanwhile. */
    private fun sleepBackoff(totalMs: Long, task: TaskRecord): Boolean {
        var left = totalMs
        while (left > 0) {
            try { Thread.sleep(minOf(500L, left)) } catch (e: InterruptedException) {
                return false
            }
            left -= 500
            if (DownloadCenter.store.byId(task.id) == null) return false
        }
        return true
    }

    /**
     * v4.4 (finalize pipeline): hand a DONE task to the serial
     * finalize lane. True when the lane took it — the caller then
     * returns at once (its download slot is free) and the lane
     * owns the rest of this task's lifecycle: finalize, the
     * delete-during-finalize honouring, the daily re-schedule
     * decision, the gate accounting and the claim release.
     * False when the lane refused (duplicate submit, or the
     * executor rejected the job) — the caller finalizes inline
     * instead, so a DONE task is never stranded.
     */
    private fun submitFinalize(task: TaskRecord): Boolean {
        if (!finalizeGate.submit(task.id)) return false
        val accepted = runCatching {
            finalizeExecutor.execute { finalizeLane(task) }
        }.isSuccess
        if (!accepted) {
            finalizeGate.complete(task.id)
            return false
        }
        return true
    }

    /**
     * The lane's per-task job, in the same order the inline path
     * used: finalize first, then honour a delete that landed
     * during finalize, then — and only then — the daily
     * re-schedule decision, which now reads the FINAL status
     * (finalize can flip DONE to FAILED when the target is gone).
     * The gate mark and the engine claim are released last, in
     * that order reversed: the drain may only observe "no
     * pending finalize" after every decision above is visible.
     */
    private fun finalizeLane(task: TaskRecord) {
        val store = DownloadCenter.store
        try {
            // Milestone 0: a delete that landed before the lane
            // even started removed the record (DONE-card
            // semantics: the record goes, the file stays). There
            // is nothing to finalize FOR — skip the whole body;
            // the checks below still drain a stale cancel flag
            // and the daily decision self-guards on byId.
            if (store.byId(task.id) != null) {
                finalizeFinished(task)
            }
            // v2.1 audit (C3), moved with the pipeline: a delete
            // tap that landed DURING finalize is honoured now —
            // the record goes away, the finished file stays,
            // exactly the semantics of deleting a DONE card.
            if (DownloadCenter.consumeCancelled(task)) {
                store.remove(task)
            }
            rescheduleDailyIfNeeded(task)
        } finally {
            finalizeGate.complete(task.id)
            DownloadCenter.releaseEngine(task.id)
        }
    }

    /**
     * The bytes are already in the public folder as "<name>.part";
     * finishing is just an in-place rename to the real name.
     *
     * v4.4: this whole body runs on the finalize lane (see
     * [submitFinalize]), never on a download thread — including
     * the completion side effects at its end, so they still fire
     * only after rename + verify + extract are all done. The
     * lane checks the record still exists at its milestones: a
     * delete landing mid-finalize removes the record directly
     * (DONE-card semantics), and a vanished record stops the
     * remaining work — most importantly the completion side
     * effects — instead of celebrating a card the user removed.
     */
    private fun finalizeFinished(task: TaskRecord) {
        val store = DownloadCenter.store
        try {
            val displayName = PublicStorage.finalizeTarget(this, task)
            // v4.4 milestone: the record was deleted while the
            // rename ran (DONE-card semantics — the renamed file
            // stays, as it should). Nothing remains to verify,
            // extract or celebrate for a card that is gone; the
            // flush below keeps the store at least as current
            // as the inline path ever did.
            if (store.byId(task.id) == null) {
                store.saveNow()
                return
            }
            // v2.6: with 按类型分文件夹 on, the note names the
            // category subfolder the file actually landed in.
            val subFolder = PublicStorage.categoryFolderOf(this, task)
            val where = if (DownloadCenter.downloadDirUri.isNotBlank()) {
                "已保存到你选择的文件夹"
            } else if (subFolder != null) {
                "已保存到公共「下载」目录的「$subFolder」文件夹"
            } else {
                "已保存到公共「下载」目录"
            }
            var note = "$where：$displayName"
            // v1.7: if the user attached an expected hash, stream
            // the finished file through it NOW and put the verdict
            // in the note + on the card （对上 / 对不上）.
            // v2.9: that automatic pass is a setting (default on).
            // Off = the file is not read back at all; when a hash
            // was attached the note points at the card's manual
            // 立即校验 button instead of staying silent.
            val verdict = if (DownloadCenter.autoVerifyHash) {
                runCatching { ChecksumVerifier.verify(this, task) }
                    .getOrDefault("")
            } else {
                ""
            }
            if (verdict.isNotBlank()) {
                note += " $verdict"
            } else if (!DownloadCenter.autoVerifyHash &&
                Checksum.expectedOf(task) != null
            ) {
                note += " 完整性校验：自动校验已关，可在任务详情里点「立即校验」。"
            }
            task.note = note
            // v3.0: auto-extract (setting, default OFF; .zip
            // only). Runs AFTER verification on purpose: a file
            // that FAILED its hash check is never unpacked —
            // the mismatch warning above is the last word on
            // it. A verify that did not run (switch off / no
            // hash attached) does not block extraction; the
            // check the user disabled cannot gate silently.
            // Progress and the outcome append to the note, so
            // the completion notification below reports the
            // final state of the whole job.
            if (DownloadCenter.autoExtractZip &&
                ZipExtract.isZipName(displayName)
            ) {
                if (task.checksumState == Checksum.STATE_MISMATCHED) {
                    task.note = note +
                        " 校验没对上，没有自动解压；确认文件没问题后可手动解压。"
                } else {
                    val res = ZipExtractor.extract(this, task) { count ->
                        // v4.4 milestone: the record was deleted
                        // mid-extract. Abort by throwing — the
                        // extractor's own failure path rolls back
                        // the files it already wrote through the
                        // task's Uri, so the half-extracted tree
                        // is cleaned up instead of being orphaned
                        // silently. (The progress callback fires
                        // every 25 files; a single huge entry
                        // still finishes its own stream first —
                        // the milestone after extract catches the
                        // delete before any side effect fires.)
                        if (store.byId(task.id) == null) throw FinalizeAborted()
                        task.note = "$note 正在自动解压：已解出 $count 个文件…"
                        store.save()
                    }
                    task.note = "$note ${res.text}"
                }
            }
            // v4.4 milestone (the side-effect gate): a delete
            // that landed during verify / extract removed the
            // record — the completion notification, the stats,
            // the feedback and the auto-action below must NOT
            // fire for it (see FinalizeGate). The finished file
            // stays, per DONE-card delete semantics.
            if (!FinalizeGate.mayFireCompletionSideEffects(store.byId(task.id) != null)) {
                store.saveNow()
                return
            }
            notifyCompletion(task, displayName)
            // v1.6: feed the statistics screen (local counters).
            DownloadCenter.recordCompletion(task)
            completionFeedback()
            // v2.1: the user's completion auto actions (both
            // default OFF; see runCompletionAction).
            runCompletionAction(task)
        } catch (e: PublicStorage.TargetGoneException) {
            // Audit round 28: the .part entry was deleted
            // mid-download — the bytes are gone with it, so the
            // DONE set just above would be a lie. Fail the task
            // honestly, with the plain reason on the card; a
            // resume re-checks the target and starts over (see
            // obtainTarget's vanished-target reset).
            task.status = TaskStatus.FAILED
            task.error = e.message ?: "文件在下载途中被删掉了，请重新下载。"
            // v4.5 (audit round 30): this failure is produced
            // HERE, outside the engine — record its structured
            // verdict at the same time, like the engine does.
            task.failureKind = FailureKind.TARGET_GONE
            task.note = ""
            notifyAggregate()
        } catch (e: Exception) {
            task.note = "下载完成，但改名失败（${Errors.human(e)}），" +
                "文件在下载目录里，名字带 .part 后缀，去掉后缀即可使用。"
            notifyAggregate()
        }
        store.saveNow()
    }

    /**
     * v2.1: completion auto actions, per the user's switches (both
     * default OFF). Auto-install only LAUNCHES the system installer
     * for an APK — the installer's own 「安装」 tap stays the
     * user's; nothing can bypass it and nothing here tries.
     * Auto-open hands the finished file to a viewer app. Both go
     * through the savedUri finalizeTarget just recorded (the real
     * public-folder location). A launch the system refuses
     * (background-activity limits on some ROMs, no viewer for the
     * type) is reported on the card instead of failing silently —
     * the completion notification and the card buttons remain the
     * fallback either way.
     */
    private fun runCompletionAction(task: TaskRecord) {
        if (!DownloadCenter.autoInstallApk && !DownloadCenter.autoOpenFile) return
        val uriText = task.savedUri
        if (uriText.isBlank()) return
        val name = task.savedName.ifBlank { task.fileName }
        val isApk = name.endsWith(".apk", ignoreCase = true)
        try {
            when {
                isApk && DownloadCenter.autoInstallApk -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            android.net.Uri.parse(uriText),
                            "application/vnd.android.package-archive",
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                DownloadCenter.autoOpenFile -> {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(uriText), mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            task.note += "（自动打开没成功，在卡片上点「打开 / 安装」即可。）"
        }
    }

    /** Completion vibration / sound, per the user's switches. */
    private fun completionFeedback() {
        // v2.5: quiet hours silence the feedback entirely; the
        // completion notification itself still posts (its own
        // channel is silent by design — see onCreate).
        if (DownloadCenter.quietHoursActive()) return
        if (DownloadCenter.completeVibrate) {
            runCatching {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(android.content.Context.VIBRATOR_SERVICE)
                        as android.os.Vibrator
                }
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        180, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        }
        if (DownloadCenter.completeSound) {
            runCatching {
                val uri = android.media.RingtoneManager
                    .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                android.media.RingtoneManager.getRingtone(this, uri)?.play()
            }
        }
    }

    // ---------- v2.5: completion notifications ----------

    /** Tap-anywhere intent for notifications: open the app. */
    private fun appPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Post a completion on its OWN channel + id. (The old code
     * posted "下载完成" over the foreground progress
     * notification — it vanished when the service stopped and
     * could never carry actions.) A single completion carries
     * 打开/安装 and 分享 actions for the finished file;
     * several completions inside the merge window collapse
     * into ONE summary (see CompletionMerge) so a batch
     * finishing together cannot stack up a wall of cards.
     */
    private fun notifyCompletion(task: TaskRecord, displayName: String) {
        runCatching {
            val now = System.currentTimeMillis()
            // v4.1: finished size for the notification detail.
            // Same yardstick as the statistics counter
            // (DownloadCenter.recordCompletion): the declared
            // total when known, else the bytes actually written
            // — for HLS the total is never declared, and
            // downloaded IS the merged file's size.
            val sizeBytes = if (task.totalSize > 0) task.totalSize else task.downloaded
            val merged: List<CompletionMerge.Done>
            synchronized(completionLock) {
                recentCompletions = CompletionMerge.add(
                    recentCompletions,
                    CompletionMerge.Done(displayName, task.savedUri, now, sizeBytes),
                    now,
                )
                merged = recentCompletions
            }
            val nm = getSystemService(NotificationManager::class.java)
            if (CompletionMerge.isMerged(merged)) {
                val b = NotificationCompat.Builder(this, COMPLETE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(CompletionMerge.title(merged))
                    .setContentText("都已保存到下载目录，点开查看")
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(CompletionMerge.summaryText(merged))
                    )
                    .setContentIntent(appPendingIntent())
                    .setAutoCancel(true)
                nm.notify(COMPLETE_NOTIF_ID, b.build())
            } else {
                val b = NotificationCompat.Builder(this, COMPLETE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("下载完成")
                    .setContentText(displayName)
                    .setContentIntent(appPendingIntent())
                    .setAutoCancel(true)
                // v4.1: expanded view gains the size + whole-run
                // average speed line under the file name. The
                // average is task.avgBps — the same number the
                // task card's 平均速度 row and the statistics
                // screen use (bytes over the engine's accumulated
                // RUN time; the gaps between runs, e.g. while
                // paused, are never counted — see DownloadEngine's
                // elapsedMs accumulation). It is final here: the
                // engine run has returned before finalizeFinished
                // runs. No detail (size and speed both unknown)
                // = no style, the notification stays as it was.
                val detail = CompletionMerge.detailLine(sizeBytes, task.avgBps)
                if (detail.isNotEmpty()) {
                    b.setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("$displayName\n$detail")
                    )
                }
                completionOpenIntent(task, displayName)?.let { open ->
                    val label =
                        if (displayName.endsWith(".apk", ignoreCase = true)) {
                            "安装"
                        } else {
                            "打开"
                        }
                    b.addAction(
                        android.R.drawable.ic_media_play, label,
                        PendingIntent.getActivity(
                            this, 101 * 31 + task.id.hashCode(), open,
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                completionShareIntent(task, displayName)?.let { share ->
                    b.addAction(
                        android.R.drawable.ic_menu_share, "分享",
                        PendingIntent.getActivity(
                            this, 102 * 31 + task.id.hashCode(), share,
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                nm.notify(COMPLETE_NOTIF_ID, b.build())
            }
            // The widget's "last completed" state rides along,
            // like it does on progress posts.
            com.tuxundown.android.widget.WidgetUpdater.refresh(this)
        }
    }

    /** 打开/安装 action for a finished file: the system
     *  installer for an APK, a viewer for everything else —
     *  the same intents the task card uses. */
    private fun completionOpenIntent(
        task: TaskRecord,
        displayName: String,
    ): Intent? {
        val uriText = task.savedUri
        if (uriText.isBlank()) return null
        val uri = android.net.Uri.parse(uriText)
        val mime = if (displayName.endsWith(".apk", ignoreCase = true)) {
            "application/vnd.android.package-archive"
        } else {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext) ?: "*/*"
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 分享 action: hand the finished file to the system
     *  share sheet (read permission granted for the pick). */
    private fun completionShareIntent(
        task: TaskRecord,
        displayName: String,
    ): Intent? {
        val uriText = task.savedUri
        if (uriText.isBlank()) return null
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uriText))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---------- notification: same Windows-style wording as the UI ----------

    private fun notifyAggregate() {
        // v1.4: several engines may report in the same second; the
        // notification never needs more than one post per ~second.
        // v2.1 audit (C7): the gate itself is check-then-set from
        // several engine threads at once — hold a monitor so two
        // threads cannot both slip through in the same instant.
        val now = System.currentTimeMillis()
        synchronized(notifyLock) {
            if (now - lastNotifyMs < 900) return
            lastNotifyMs = now
        }
        val store = DownloadCenter.store
        val runningTasks = store.tasks.filter { it.status == TaskStatus.RUNNING }
        if (runningTasks.isEmpty()) {
            notifyUpdate("TuxunDown", "准备下载…", 0)
            return
        }
        val active = store.tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }
        // v2.6 audit round 12: the headline numbers (count +
        // total %) come from WidgetState — the ONE aggregation
        // the widget and the home summary card also use — so
        // the three surfaces cannot drift apart arithmetically.
        val agg = WidgetState.summary(store.tasks)
        val totalPct = agg.totalPct
        val totalSpeed = runningTasks.sumOf { it.speedBps }
        if (runningTasks.size == 1 && active.size == 1) {
            val running = runningTasks.first()
            val eta = running.etaSeconds
            val text = buildString {
                append(Format.speed(running.speedBps))
                if (eta > 0) append(" · 还剩 ${Format.eta(eta)}")
                append(" · 已完成 ${WidgetState.taskPercent(running)}%")
            }
            // v1.8: a single running task earns pause/cancel
            // buttons right on the notification.
            notifyUpdate(
                "正在下载 · ${running.fileName}", text,
                WidgetState.taskPercent(running),
                actionTask = running,
            )
        } else {
            // v4.9: the multi-task text gains the aggregate ETA
            // the single-task text above already had — the v4.4
            // WidgetState.etaSeconds口径 over the same store.tasks
            // list WidgetState.summary just consumed (remaining
            // bytes of the whole active set / the running tasks'
            // total speed). -1 means no honest answer (an active
            // task's size unknown, nothing running, or no speed):
            // the text is then left as it was, no fake precision.
            val eta = WidgetState.etaSeconds(store.tasks)
            val text = buildString {
                append("总速度 ${Format.speed(totalSpeed)}")
                append(" · 已完成 $totalPct%")
                if (eta > 0) append(" · 全部还剩 ${Format.eta(eta)}")
            }
            // v1.8: several tasks — the expanded notification
            // lists each one's own progress instead of buttons
            // that could only ever name one of them.
            val lines = active.take(5).joinToString("\n") { t ->
                val name = t.fileName.ifBlank { t.url }
                val pct = WidgetState.taskPercent(t)
                if (t.status == TaskStatus.RUNNING) {
                    "$name · $pct% · ${Format.speed(t.speedBps)}"
                } else {
                    "$name · $pct% · 排队中"
                }
            } + if (active.size > 5) "\n…还有 ${active.size - 5} 个任务" else ""
            notifyUpdate("正在下载 ${agg.activeCount} 个任务", text, totalPct, bigText = lines)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        pct: Int,
        actionTask: TaskRecord? = null,
        bigText: String? = null,
    ): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, pct, false)
        if (bigText != null) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        if (actionTask != null) {
            b.addAction(
                android.R.drawable.ic_media_pause, "暂停",
                actionPendingIntent(ACTION_PAUSE, actionTask.id, 1),
            )
            b.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "取消",
                actionPendingIntent(ACTION_CANCEL, actionTask.id, 2),
            )
        }
        return b.build()
    }

    /** v1.8: a service PendingIntent carrying one notification action. */
    private fun actionPendingIntent(action: String, taskId: String, code: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getService(
            this, code * 31 + taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyUpdate(
        title: String,
        text: String,
        pct: Int,
        actionTask: TaskRecord? = null,
        bigText: String? = null,
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text, pct, actionTask, bigText))
        // v2.1: the home-screen widget rides the same posts (the
        // aggregate path is already throttled to ~1/s; the other
        // callers are state transitions). Best-effort inside.
        com.tuxundown.android.widget.WidgetUpdater.refresh(this)
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        // v2.5: completion notifications live apart from the
        // foreground progress notification.
        private const val COMPLETE_CHANNEL = "download_complete"
        private const val COMPLETE_NOTIF_ID = 2
        // v1.8: notification action buttons.
        const val ACTION_PAUSE = "com.tuxundown.android.action.PAUSE_TASK"
        const val ACTION_CANCEL = "com.tuxundown.android.action.CANCEL_TASK"
        // v2.6: explicit start from BootReceiver (setting-gated there
        // and re-checked in onStartCommand).
        const val ACTION_BOOT_RESUME = "com.tuxundown.android.action.BOOT_RESUME"
        // v3.5: explicit start from ScheduleReceiver — the
        // backup alarm found a scheduled task due (see
        // ScheduleWake). No special handling: the drain loop
        // picks due tasks up by itself; the action exists so
        // the entry point is auditable.
        const val ACTION_SCHEDULE_WAKE = "com.tuxundown.android.action.SCHEDULE_WAKE"
        const val EXTRA_TASK_ID = "taskId"
        /** v2.8: pre-check pass interval (see LinkPrecheck). */
        private const val PRECHECK_INTERVAL_MS = 30_000L
    }
}
