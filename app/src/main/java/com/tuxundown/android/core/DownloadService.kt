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
        DownloadCenter.init(this)
        // v1.5 startup audit: channel creation is idempotent, but a
        // ROM quirk here must never take the whole service down.
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW)
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
                        if (worker?.isAlive != true) {
                            worker = Thread { drainQueue() }.apply {
                                isDaemon = true; start()
                            }
                        }
                    }
                }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("准备下载…", "", 0))
        if (worker?.isAlive != true) {
            worker = Thread { drainQueue() }.apply { isDaemon = true; start() }
        }
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
                val th = Thread { runOne(task) }
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Run one task to a terminal state, with automatic retries. */
    private fun runOne(task: TaskRecord) {
        val store = DownloadCenter.store
        while (true) {
            // The record may have been deleted from the UI in the
            // same instant we picked it up; never download a ghost.
            if (store.byId(task.id) == null) return
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
                limiter = SpeedLimiter(rateBps = {
                    DownloadCenter.budgetShareBps(task.id)
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
                // v1.6 dual channel: alternate connections ride the
                // cellular network the system granted us.
                extraOpener = if (dualActive) {
                    cellularNetwork?.let { net ->
                        { u: java.net.URL ->
                            net.openConnection(u) as java.net.HttpURLConnection
                        }
                    }
                } else null,
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
                engine.run()
                if (DownloadCenter.consumeCancelled(task)) {
                    // Audit A7: the user deleted this task while it
                    // was running. The engine has fully stopped NOW,
                    // so it is finally safe to remove the file and
                    // the record.
                    PublicStorage.deleteTarget(this, task)
                    store.remove(task)
                    return
                }
                if (task.status == TaskStatus.DONE) {
                    netAttempts.remove(task.id)
                    finalizeFinished(task)
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
            // If the user took over meanwhile (paused / re-queued /
            // deleted), step aside and let the normal flow own it.
            if (store.byId(task.id) == null || task.status != TaskStatus.FAILED) return
            task.status = TaskStatus.QUEUED
            task.error = ""
        }
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
            val where = if (DownloadCenter.downloadDirUri.isNotBlank()) {
                "已保存到你选择的文件夹"
            } else {
                "已保存到公共「下载」目录"
            }
            task.note = "$where：$displayName"
            notifyUpdate("下载完成", displayName, 100)
            // v1.6: feed the statistics screen (local counters).
            DownloadCenter.recordCompletion(task)
            completionFeedback()
        } catch (e: Exception) {
            task.note = "下载完成，但改名失败（${e.message ?: "未知错误"}），" +
                "文件在下载目录里，名字带 .part 后缀，去掉后缀即可使用。"
            notifyAggregate()
        }
        store.saveNow()
    }

    /** Completion vibration / sound, per the user's switches. */
    private fun completionFeedback() {
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

    // ---------- notification: same Windows-style wording as the UI ----------

    private fun notifyAggregate() {
        // v1.4: several engines may report in the same second; the
        // notification never needs more than one post per ~second.
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 900) return
        lastNotifyMs = now
        val store = DownloadCenter.store
        val runningTasks = store.tasks.filter { it.status == TaskStatus.RUNNING }
        if (runningTasks.isEmpty()) {
            notifyUpdate("TuxunDown", "准备下载…", 0)
            return
        }
        val active = store.tasks.filter {
            it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED
        }
        val totalAll = active.filter { it.totalSize > 0 }.sumOf { it.totalSize }
        val doneAll = active.filter { it.totalSize > 0 }.sumOf { it.downloaded }
        val totalPct = if (totalAll > 0) (doneAll * 100 / totalAll).toInt() else 0
        val totalSpeed = runningTasks.sumOf { it.speedBps }
        if (runningTasks.size == 1 && active.size == 1) {
            val running = runningTasks.first()
            val eta = running.etaSeconds
            val text = buildString {
                append(Format.speed(running.speedBps))
                if (eta > 0) append(" · 还剩 ${Format.eta(eta)}")
                append(" · 已完成 ${(running.progress * 100).toInt()}%")
            }
            notifyUpdate("正在下载 · ${running.fileName}", text, (running.progress * 100).toInt())
        } else {
            val text = buildString {
                append("总速度 ${Format.speed(totalSpeed)}")
                append(" · 已完成 $totalPct%")
            }
            notifyUpdate("正在下载 ${active.size} 个任务", text, totalPct)
        }
    }

    private fun buildNotification(title: String, text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .build()
    }

    private fun notifyUpdate(title: String, text: String, pct: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(title, text, pct))
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
    }
}
