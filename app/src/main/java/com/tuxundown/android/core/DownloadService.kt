package com.tuxundown.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

    /** v1.5: fires when connectivity returns; un-parks deferred tasks. */
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** v1.6 dual channel: the cellular network the system granted
     *  us (null when the environment cannot provide one). */
    @Volatile private var cellularNetwork: Network? = null
    private var cellCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var dualRequested = false
    @Volatile private var dualActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // v2.2: the crash handler also covers the service
        // process entry (idempotent — see CrashGuard.install).
        CrashGuard.install(this)
        DownloadCenter.init(this)
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
    }

    override fun onDestroy() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        netCallback = null
        releaseCellular()
        super.onDestroy()
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
        dualActive = false
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
        dualActive = cellularNetwork != null
    }

    private fun releaseCellular() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cellCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        cellCallback = null
        cellularNetwork = null
        dualActive = false
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Connectivity is back: un-park every task that
                    // was deferred by an outage and make sure the
                    // drain loop is running to pick them up.
                    var cleared = false
                    for (t in DownloadCenter.store.tasks) {
                        if (t.netDeferred && t.status == TaskStatus.QUEUED) {
                            t.netDeferred = false
                            t.note = "网络已恢复，自动继续下载。"
                            cleared = true
                        }
                    }
                    if (cleared) {
                        DownloadCenter.store.save()
                        ensureWorker()
                    }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        }
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
        startForeground(NOTIF_ID, buildNotification("准备下载…", "", 0))
        ensureWorker()
        return START_STICKY
    }

    private fun drainQueue() {
        val store = DownloadCenter.store
        // v1.6: dual channel is negotiated once per drain session.
        acquireCellularIfWanted()
        val running = mutableMapOf<String, Thread>()
        while (true) {
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
                val th = Thread {
                    runOne(task)
                    rescheduleDailyIfNeeded(task)
                }
                th.isDaemon = true
                running[task.id] = th
                th.start()
            }
            val anyQueued = store.tasks.any { it.status == TaskStatus.QUEUED }
            if (running.isEmpty()) {
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
                    try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                    continue
                }
                // v1.5: tasks parked by a network outage keep the
                // service (and its network callback) alive until
                // connectivity returns — they are QUEUED but not
                // eligible, so without this branch the service would
                // stop and nobody would ever un-park them.
                val anyDeferred = store.tasks.any {
                    it.netDeferred && it.status == TaskStatus.QUEUED
                }
                if (!anyQueued && !anyDeferred) break
                if (anyDeferred &&
                    QueueOrder.nextEligible(store.tasks, System.currentTimeMillis()) == null
                ) {
                    notifyUpdate(
                        "等待网络恢复",
                        "断网的任务已排队，网络一回来就自动继续",
                        0,
                    )
                    try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                    continue
                }
                // Only future-scheduled tasks remain: wait for the
                // earliest one (polling, so a newly enqueued task or
                // a changed setting is picked up within seconds).
                val nextAt = QueueOrder.nextScheduledAt(store.tasks, System.currentTimeMillis())
                if (nextAt <= 0) {
                    // Nothing scheduled — but outage-parked tasks still
                    // need the callback alive, so wait instead of dying.
                    if (anyDeferred) {
                        try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                        continue
                    }
                    break
                }
                notifyUpdate(
                    "预约下载待机",
                    "下一个任务将在 ${Format.clockTime(nextAt)} 开始",
                    0,
                )
                val waitMs = (nextAt - System.currentTimeMillis()).coerceIn(2_000, 30_000)
                try { Thread.sleep(waitMs) } catch (e: InterruptedException) { break }
            } else {
                try { Thread.sleep(400) } catch (e: InterruptedException) { break }
            }
        }
        releaseCellular()
        // v2.1: settle the widget on the final state before the
        // service goes away (no more posts will come from here).
        com.tuxundown.android.widget.WidgetUpdater.refresh(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Run one task to a terminal state, with automatic retries. */
    private fun runOne(task: TaskRecord) {
        // v2.1 audit (round 7, C1): claim the task for the WHOLE
        // invocation — transfer, retry backoff and finalize — so no
        // second runOne for this task can ever overlap it, whatever
        // thread or drain loop asks. A refused claim means another
        // run already owns the task; step aside silently.
        if (!DownloadCenter.claimEngine(task.id)) return
        try {
            runOneClaimed(task)
        } finally {
            DownloadCenter.releaseEngine(task.id)
        }
    }

    private fun runOneClaimed(task: TaskRecord) {
        val store = DownloadCenter.store
        while (true) {
            // The record may have been deleted from the UI in the
            // same instant we picked it up; never download a ghost.
            if (store.byId(task.id) == null) return
            // v2.1 audit (C4): the user can tap pause in the instant
            // between the drain loop picking this task and this
            // thread starting — the engine is not registered yet, so
            // the pause has nothing to bite on, and the old code
            // then overwrote PAUSED with RUNNING and downloaded
            // anyway. A first entry must still find the task QUEUED
            // (the retry loop re-enters through its own QUEUED set
            // below); PAUSED here is the user's intent — honour it.
            if (task.status == TaskStatus.PAUSED) return
            task.status = TaskStatus.RUNNING
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
                // v1.5: per-host thread memory — start known hosts at
                // their remembered optimum, remember new optima, and
                // age the memory out when a host starts failing.
                initialThreadsHint = DownloadCenter.hostThreadsHint(task.url),
                onThreadsSettled = { threads ->
                    DownloadCenter.recordHostThreads(task.url, threads)
                },
                onHostFailed = { DownloadCenter.recordHostFailure(task.url) },
            )
            DownloadCenter.registerEngine(task, engine)
            try {
                // v2.1 audit (C8): last-moment existence re-check —
                // a direct delete that landed while the engine was
                // being built must not leave us downloading a ghost
                // whose record (and target) are already gone. Any
                // delete arriving after this point sees the task
                // RUNNING and routes through cancelTask instead.
                if (store.byId(task.id) == null) return
                val runResult = engine.run()
                if (DownloadCenter.consumeCancelled(task)) {
                    // Audit A7: the user deleted this task while it
                    // was running. The engine has fully stopped NOW,
                    // so it is finally safe to remove the file and
                    // the record.
                    PublicStorage.deleteTarget(this, task)
                    store.remove(task)
                    return
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
                    return
                }
            } finally {
                DownloadCenter.unregisterEngine(task)
                // v1.7: an engine run reaching its end is a milestone
                // (done / paused / failed with full progress state) —
                // flush NOW instead of riding the coalesced window.
                store.saveNow()
            }
            // v1.5: a failure caused by the network going away is not
            // the server's fault and must not burn the v1.4 retry
            // budget — park the task and let the connectivity
            // callback re-queue it when the network returns (bounded
            // by NetRetry.MAX_ATTEMPTS per outage streak).
            if (task.status == TaskStatus.FAILED && NetRetry.isNetworkError(task.error)) {
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
                        (task.progress * 100).toInt(),
                    )
                    return
                }
                netAttempts.remove(task.id)
            }
            // v1.4: automatic whole-task retry with backoff.
            val limit = DownloadCenter.autoRetryCount
            if (task.status != TaskStatus.FAILED ||
                !RetryPolicy.shouldRetry(task.autoRetriesUsed, limit)
            ) {
                return
            }
            val waitMs = RetryPolicy.backoffMs(task.autoRetriesUsed)
            task.autoRetriesUsed++
            task.note = "下载失败，将在 ${waitMs / 1000} 秒后自动重试" +
                "（第 ${task.autoRetriesUsed}/$limit 次）。原因：${task.error}"
            store.save()
            notifyUpdate(
                "下载失败，稍后自动重试",
                "${task.fileName} · 第 ${task.autoRetriesUsed}/$limit 次",
                (task.progress * 100).toInt(),
            )
            if (!sleepBackoff(waitMs, task)) return
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
                return
            }
            // If the user took over meanwhile (paused / re-queued /
            // deleted), step aside and let the normal flow own it.
            if (store.byId(task.id) == null || task.status != TaskStatus.FAILED) return
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
        task.checksumState = Checksum.STATE_NONE
        task.hlsDone = BooleanArray(0)
        task.hlsTotalSegments = 0
        task.hlsFingerprint = ""
        synchronized(task.hlsSizes) { task.hlsSizes.clear() }
        task.scheduledAt = next
        task.status = TaskStatus.QUEUED
        task.note = "每天定时：已排到下一次 ${Format.clockTime(next)} 开始。" +
            "上一次的文件已保存在下载目录里（重名时会自动改成带 (1) 的名字）。"
        store.saveNow()
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
     * The bytes are already in the public folder as "<name>.part";
     * finishing is just an in-place rename to the real name.
     */
    private fun finalizeFinished(task: TaskRecord) {
        val store = DownloadCenter.store
        try {
            val displayName = PublicStorage.finalizeTarget(this, task)
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
            val verdict = runCatching { ChecksumVerifier.verify(this, task) }
                .getOrDefault("")
            if (verdict.isNotBlank()) note += " $verdict"
            task.note = note
            notifyCompletion(task, displayName)
            // v1.6: feed the statistics screen (local counters).
            DownloadCenter.recordCompletion(task)
            completionFeedback()
            // v2.1: the user's completion auto actions (both
            // default OFF; see runCompletionAction).
            runCompletionAction(task)
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
            val merged: List<CompletionMerge.Done>
            synchronized(completionLock) {
                recentCompletions = CompletionMerge.add(
                    recentCompletions,
                    CompletionMerge.Done(displayName, task.savedUri, now),
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
                            .bigText(CompletionMerge.lines(merged))
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
                append(" · 已完成 ${(running.progress * 100).toInt()}%")
            }
            // v1.8: a single running task earns pause/cancel
            // buttons right on the notification.
            notifyUpdate(
                "正在下载 · ${running.fileName}", text,
                (running.progress * 100).toInt(),
                actionTask = running,
            )
        } else {
            val text = buildString {
                append("总速度 ${Format.speed(totalSpeed)}")
                append(" · 已完成 $totalPct%")
            }
            // v1.8: several tasks — the expanded notification
            // lists each one's own progress instead of buttons
            // that could only ever name one of them.
            val lines = active.take(5).joinToString("\n") { t ->
                val name = t.fileName.ifBlank { t.url }
                val pct = (t.progress * 100).toInt()
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
        const val EXTRA_TASK_ID = "taskId"
    }
}
