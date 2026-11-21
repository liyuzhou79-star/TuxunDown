package com.tuxundown.android.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat

/** App-wide task registry: one store, engines reachable for pause/resume. */
object DownloadCenter {
    lateinit var store: TaskStore
        private set
    private var appContext: Context? = null
    private val engines = mutableMapOf<String, DownloadEngine>()

    // v2.1 audit (round 7, C1): one-task-one-engine claims, held by
    // the service for the whole runOne invocation. See EngineClaims.
    private val engineClaims = EngineClaims()

    /** Take the run claim for [taskId]; false = another run owns it. */
    fun claimEngine(taskId: String): Boolean = engineClaims.claim(taskId)

    fun releaseEngine(taskId: String) = engineClaims.release(taskId)

    fun isClaimed(taskId: String): Boolean = engineClaims.isClaimed(taskId)

    var wifiOnly: Boolean
        get() = pBool("wifiOnly", false)
        set(v) { prefs().edit().putBoolean("wifiOnly", v).apply() }

    /** Custom download folder (SAF tree Uri string); "" = the public Download folder root itself. */
    var downloadDirUri: String
        get() = pString("downloadDirUri", "")
        set(v) { prefs().edit().putString("downloadDirUri", v).apply() }

    var downloadDirName: String
        get() = pString("downloadDirName", "")
        set(v) { prefs().edit().putString("downloadDirName", v).apply() }

    /** Windows-style "详细信息" expanded state for task cards; remembered globally. */
    var detailsExpanded: Boolean
        get() = pBool("detailsExpanded", true)
        set(v) { prefs().edit().putBoolean("detailsExpanded", v).apply() }

    /** Global per-task speed limit in KB/s (v1.3); 0 = unlimited. */
    var speedLimitKBps: Int
        get() = pInt("speedLimitKBps", 0)
        set(v) { prefs().edit().putInt("speedLimitKBps", v).apply() }

    /**
     * v2.1: when a download finishes, auto-launch the system
     * installer for an APK task. This only OPENS the installer —
     * the installer's own 「安装」 tap stays the user's; nothing
     * here can or should bypass it. Default off.
     */
    var autoInstallApk: Boolean
        get() = pBool("autoInstallApk", false)
        set(v) { prefs().edit().putBoolean("autoInstallApk", v).apply() }

    /**
     * v2.1: when a download finishes, auto-open the file with a
     * viewer app. An APK task prefers the installer above when
     * both switches are on. Default off.
     */
    var autoOpenFile: Boolean
        get() = pBool("autoOpenFile", false)
        set(v) { prefs().edit().putBoolean("autoOpenFile", v).apply() }

    /** Completion feedback switches (v1.3). */
    var completeVibrate: Boolean
        get() = pBool("completeVibrate", true)
        set(v) { prefs().edit().putBoolean("completeVibrate", v).apply() }

    var completeSound: Boolean
        get() = pBool("completeSound", false)
        set(v) { prefs().edit().putBoolean("completeSound", v).apply() }

    /** versionName the full-screen onboarding guide was last shown for (v1.3). */
    var guideSeenVersion: String
        get() = pString("guideSeenVersion", "")
        set(v) { prefs().edit().putString("guideSeenVersion", v).apply() }

    /** Last clipboard link the user was prompted about (don't nag twice). */
    var clipboardPrompted: String
        get() = pString("clipboardPrompted", "")
        set(v) { prefs().edit().putString("clipboardPrompted", v).apply() }

    /** v1.4: how many tasks may download at the same time (1–5). */
    var maxConcurrent: Int
        get() = pInt("maxConcurrent", 1).coerceIn(1, 5)
        set(v) { prefs().edit().putInt("maxConcurrent", v.coerceIn(1, 5)).apply() }

    /** v1.4: whole-task automatic retries after a failure (0 = off). */
    var autoRetryCount: Int
        get() = pInt("autoRetryCount", 0).coerceIn(0, 5)
        set(v) { prefs().edit().putInt("autoRetryCount", v.coerceIn(0, 5)).apply() }

    /** v1.4: mobile-data protection threshold in MB (0 = off). */
    var trafficThresholdMB: Int
        get() = pInt("trafficThresholdMB", 0).coerceAtLeast(0)
        set(v) { prefs().edit().putInt("trafficThresholdMB", v.coerceAtLeast(0)).apply() }

    /** v1.4: pages sniffed recently (newest first, max 10), for one-tap re-entry. */
    var sniffHistory: List<String>
        get() = pString("sniffHistory", "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        set(v) {
            prefs().edit().putString(
                "sniffHistory",
                v.distinct().take(10).joinToString("\n"),
            ).apply()
        }

    fun rememberSniffedPage(url: String) {
        if (url.isBlank()) return
        sniffHistory = listOf(url) + sniffHistory.filter { it != url }
    }

    /**
     * v1.6: WiFi+mobile dual channel (best effort). Default OFF.
     * When on, the service asks the system for a cellular network
     * and the engine alternates connections between the two; when
     * the environment cannot provide one, tasks say so in their
     * note instead of pretending. Turning it on IS the user's
     * traffic confirmation (the traffic guard steps aside), but
     * 仅 WiFi still wins when both are enabled.
     */
    var dualChannel: Boolean
        get() = pBool("dualChannel", false)
        set(v) { prefs().edit().putBoolean("dualChannel", v).apply() }

    /**
     * v1.7: the user's custom User-Agent setting ("" = built-in
     * default). A UA captured for a specific task (sniffer /
     * browser) still wins over this — see UserAgents.resolve.
     */
    var userAgent: String
        get() = pString("userAgent", "")
        set(v) { prefs().edit().putString("userAgent", v).apply() }

    /** v1.7: AMOLED pure-black dark theme (OLED screens). */
    var amoledBlack: Boolean
        get() = pBool("amoledBlack", false)
        set(v) { prefs().edit().putBoolean("amoledBlack", v).apply() }

    // ---- v1.9: download proxy (engine + sniffer; ProxySettings) ----
    // Every setter re-syncs the runtime mirror the sniff layer
    // reads (ProxyState) and the SOCKS authenticator.

    var proxyEnabled: Boolean
        get() = pBool("proxyEnabled", false)
        set(v) { prefs().edit().putBoolean("proxyEnabled", v).apply(); syncProxyState() }

    var proxyType: Int
        get() = pInt("proxyType", ProxySettings.TYPE_HTTP)
        set(v) { prefs().edit().putInt("proxyType", v).apply(); syncProxyState() }

    var proxyHost: String
        get() = pString("proxyHost", "")
        set(v) { prefs().edit().putString("proxyHost", v).apply(); syncProxyState() }

    var proxyPort: Int
        get() = pInt("proxyPort", 0)
        set(v) { prefs().edit().putInt("proxyPort", v).apply(); syncProxyState() }

    var proxyUser: String
        get() = pString("proxyUser", "")
        set(v) { prefs().edit().putString("proxyUser", v).apply(); syncProxyState() }

    var proxyPass: String
        get() = pString("proxyPass", "")
        set(v) { prefs().edit().putString("proxyPass", v).apply(); syncProxyState() }

    fun proxySettings(): ProxySettings = ProxySettings(
        enabled = proxyEnabled,
        type = proxyType,
        host = proxyHost,
        port = proxyPort,
        username = proxyUser,
        password = proxyPass,
    )

    private fun syncProxyState() {
        if (appContext == null) return
        ProxyState.settings = proxySettings()
        ProxyState.installAuthenticator()
    }

    // ---- v1.8: full backup — settings snapshot / apply ----
    // The backup carries every user setting EXCEPT the download
    // directory: a SAF tree Uri (and its permission) names a
    // folder on one specific device and cannot transfer.

    fun settingsSnapshotJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("wifiOnly", wifiOnly)
        o.put("speedLimitKBps", speedLimitKBps)
        o.put("maxConcurrent", maxConcurrent)
        o.put("autoRetryCount", autoRetryCount)
        o.put("trafficThresholdMB", trafficThresholdMB)
        o.put("dualChannel", dualChannel)
        o.put("userAgent", userAgent)
        o.put("amoledBlack", amoledBlack)
        o.put("completeVibrate", completeVibrate)
        o.put("completeSound", completeSound)
        o.put("autoInstallApk", autoInstallApk)
        o.put("autoOpenFile", autoOpenFile)
        o.put("detailsExpanded", detailsExpanded)
        o.put("sniffHistory", org.json.JSONArray(sniffHistory))
        // v1.9: proxy settings travel with the backup EXCEPT the
        // password — a credential does not belong in a file the
        // user may store or send anywhere. It must be re-entered
        // after a restore (the settings copy says so).
        o.put("proxyEnabled", proxyEnabled)
        o.put("proxyType", proxyType)
        o.put("proxyHost", proxyHost)
        o.put("proxyPort", proxyPort)
        o.put("proxyUser", proxyUser)
        return o
    }

    /** Apply a settings object from a backup; absent keys keep their current values. */
    fun applySettingsJson(o: org.json.JSONObject) {
        if (o.has("wifiOnly")) wifiOnly = o.optBoolean("wifiOnly", wifiOnly)
        if (o.has("speedLimitKBps")) speedLimitKBps = o.optInt("speedLimitKBps", speedLimitKBps)
        if (o.has("maxConcurrent")) maxConcurrent = o.optInt("maxConcurrent", maxConcurrent)
        if (o.has("autoRetryCount")) autoRetryCount = o.optInt("autoRetryCount", autoRetryCount)
        if (o.has("trafficThresholdMB")) {
            trafficThresholdMB = o.optInt("trafficThresholdMB", trafficThresholdMB)
        }
        if (o.has("dualChannel")) dualChannel = o.optBoolean("dualChannel", dualChannel)
        if (o.has("userAgent")) userAgent = o.optString("userAgent", userAgent)
        if (o.has("amoledBlack")) amoledBlack = o.optBoolean("amoledBlack", amoledBlack)
        if (o.has("completeVibrate")) {
            completeVibrate = o.optBoolean("completeVibrate", completeVibrate)
        }
        if (o.has("completeSound")) completeSound = o.optBoolean("completeSound", completeSound)
        if (o.has("autoInstallApk")) autoInstallApk = o.optBoolean("autoInstallApk", autoInstallApk)
        if (o.has("autoOpenFile")) autoOpenFile = o.optBoolean("autoOpenFile", autoOpenFile)
        if (o.has("detailsExpanded")) {
            detailsExpanded = o.optBoolean("detailsExpanded", detailsExpanded)
        }
        val hist = o.optJSONArray("sniffHistory")
        if (hist != null) {
            sniffHistory = (0 until hist.length())
                .map { hist.optString(it) }
                .filter { it.isNotBlank() }
        }
        // v1.9: proxy (password deliberately not in backups).
        if (o.has("proxyEnabled")) proxyEnabled = o.optBoolean("proxyEnabled", proxyEnabled)
        if (o.has("proxyType")) proxyType = o.optInt("proxyType", proxyType)
        if (o.has("proxyHost")) proxyHost = o.optString("proxyHost", proxyHost)
        if (o.has("proxyPort")) proxyPort = o.optInt("proxyPort", proxyPort)
        if (o.has("proxyUser")) proxyUser = o.optString("proxyUser", proxyUser)
    }

    /** Build a task record, flagging HLS (.m3u8) links automatically. */
    fun newTask(url: String, headers: Map<String, String> = emptyMap()): TaskRecord {
        val t = TaskRecord(url = url, headers = headers.toMutableMap())
        if (DownloadEngine.isHlsUrl(url)) t.isHls = true
        return t
    }

    // ---- v1.6: download statistics (local only, never uploaded) ----

    fun recordCompletion(task: TaskRecord) {
        val bytes = if (task.totalSize > 0) task.totalSize else task.downloaded
        val e = prefs().edit()
        if (bytes > 0) {
            e.putLong("statsTotalBytes", pLong("statsTotalBytes", 0) + bytes)
            val now = System.currentTimeMillis()
            val today = StatsMath.todayBytes(
                pString("statsTodayKey", ""), pLong("statsTodayBytes", 0), now
            )
            e.putString("statsTodayKey", StatsMath.dayKey(now))
            e.putLong("statsTodayBytes", today + bytes)
        }
        e.putInt("statsDoneCount", pInt("statsDoneCount", 0) + 1)
        if (task.avgBps > 0) {
            e.putLong("statsSpeedSum", pLong("statsSpeedSum", 0) + task.avgBps)
            e.putInt("statsSpeedCount", pInt("statsSpeedCount", 0) + 1)
        }
        e.apply()
    }

    fun statsTotalBytes(): Long = pLong("statsTotalBytes", 0)

    fun statsTodayBytes(): Long = StatsMath.todayBytes(
        pString("statsTodayKey", ""), pLong("statsTodayBytes", 0),
        System.currentTimeMillis(),
    )

    fun statsDoneCount(): Int = pInt("statsDoneCount", 0)

    fun statsAvgBps(): Long {
        val count = pInt("statsSpeedCount", 0)
        return if (count > 0) pLong("statsSpeedSum", 0) / count else 0
    }

    fun clearStats() {
        prefs().edit()
            .remove("statsTotalBytes").remove("statsDoneCount")
            .remove("statsTodayKey").remove("statsTodayBytes")
            .remove("statsSpeedSum").remove("statsSpeedCount")
            .apply()
    }

    /**
     * v1.6: this task's live share of the user's global speed
     * budget. The 全局限速 setting is a TOTAL across all running
     * tasks, split by priority weight (see BandwidthBudget); 0
     * means unlimited. Read on every limiter tick, so changing the
     * setting — or another task starting/finishing — rebalances
     * immediately.
     */
    fun budgetShareBps(forTaskId: String): Long {
        val total = speedLimitKBps.toLong() * 1024
        if (total <= 0) return 0
        val running = store.tasks.filter { it.status == TaskStatus.RUNNING }
        if (running.isEmpty()) return total
        val idx = running.indexOfFirst { it.id == forTaskId }
        if (idx < 0) return total
        val weights = running.map { BandwidthBudget.weightOf(it.priority) }
        return BandwidthBudget.shares(total, weights)[idx]
    }

    // ---- cancellation (audit A7) ----
    // Deleting a RUNNING task used to race the engine: the UI deleted
    // the .part file while worker threads were still writing into it.
    // Now the UI marks the task cancelled; the service, which owns
    // the engine thread, deletes the file and the record only AFTER
    // the engine has fully stopped.
    private val cancelled = mutableSetOf<String>()

    @Synchronized
    fun cancelTask(task: TaskRecord) {
        cancelled.add(task.id)
        engines[task.id]?.pause()
    }

    @Synchronized
    fun consumeCancelled(task: TaskRecord): Boolean = cancelled.remove(task.id)

    private fun prefs() = appContext!!.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // v1.5 startup-safety audit: a SharedPreferences value stored
    // under the wrong type by an older build makes the typed getter
    // throw ClassCastException — on a first-frame settings read that
    // is a launch crash (the v1.3.0 lesson: never again). Every read
    // below therefore goes through a runCatching helper with the
    // documented default as the fallback. Writes stay direct.
    private fun pBool(key: String, def: Boolean): Boolean =
        runCatching { prefs().getBoolean(key, def) }.getOrDefault(def)

    private fun pInt(key: String, def: Int): Int =
        runCatching { prefs().getInt(key, def) }.getOrDefault(def)

    private fun pLong(key: String, def: Long): Long =
        runCatching { prefs().getLong(key, def) }.getOrDefault(def)

    private fun pString(key: String, def: String): String =
        runCatching { prefs().getString(key, def) }.getOrDefault(def) ?: def

    // ---- v1.5: per-host optimal thread memory (see HostMemory) ----

    private fun hostEntries(): List<HostMemory.Entry> =
        HostMemory.parse(pString("hostThreads", ""))

    private fun saveHostEntries(entries: List<HostMemory.Entry>) {
        prefs().edit().putString("hostThreads", HostMemory.serialize(entries)).apply()
    }

    /** Remembered thread count for this URL's host, 0 = unknown. */
    fun hostThreadsHint(url: String): Int =
        HostMemory.lookup(hostEntries(), HostMemory.hostOf(url), System.currentTimeMillis())

    fun recordHostThreads(url: String, threads: Int) {
        saveHostEntries(
            HostMemory.recordSuccess(
                hostEntries(), HostMemory.hostOf(url), threads, System.currentTimeMillis()
            )
        )
    }

    fun recordHostFailure(url: String) {
        saveHostEntries(
            HostMemory.recordFailure(hostEntries(), HostMemory.hostOf(url), System.currentTimeMillis())
        )
    }

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = TaskStore(context.applicationContext)
        store.load()
        syncProxyState()
    }

    fun isOnWifi(): Boolean {
        val cm = appContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Queue a task and kick the foreground service. Returns false if blocked. */
    fun enqueue(task: TaskRecord): Boolean {
        task.autoRetriesUsed = 0
        task.netDeferred = false // v1.5: user action always wins over the outage park
        // v1.4: pressing 继续 on a paused task while on mobile data,
        // when the traffic guard would block it, IS the user's
        // explicit confirmation for this one task.
        if (task.status == TaskStatus.PAUSED && !task.mobileConfirmed &&
            TrafficGuard.shouldBlock(
                onWifi = isOnWifi(),
                totalSize = task.totalSize,
                thresholdMB = trafficThresholdMB,
                confirmed = false,
            )
        ) {
            task.mobileConfirmed = true
        }
        if (wifiOnly && !isOnWifi()) {
            task.note = "已开启「仅 WiFi 下载」，当前不在 WiFi 网络，任务已排队等待。"
            task.status = TaskStatus.QUEUED
            store.save()
            // v1.7 audit (B2): STILL start the service. It used to
            // return here without starting anything, and the drain
            // loop exited when the only queued tasks were
            // wifi-blocked — so the task sat forever until the user
            // reopened the app, even after WiFi came back. Now the
            // service's drain loop waits on WiFi (5 s poll) and the
            // task starts by itself when WiFi returns.
            val ctx = appContext!!
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            return false
        }
        task.status = TaskStatus.QUEUED
        task.error = ""
        store.save()
        val ctx = appContext!!
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        return true
    }

    /** v1.4 插队： move a queued task to the front of the queue.
     *  v1.6: a jumped task also gains bandwidth-budget weight.
     *  v2.1 audit (C5): the reorder is a compound indexOf/remove/add
     *  over the shared list — hold the list's monitor so a drain-loop
     *  or composition iteration never observes the half-moved state. */
    fun moveToFrontOfQueue(task: TaskRecord): Boolean {
        val changed = synchronized(store.tasks) {
            QueueOrder.moveToFrontOfQueue(store.tasks, task)
        }
        if (changed) {
            if (task.priority == 0) task.priority = 1
            store.save()
        }
        return changed
    }

    @Synchronized
    fun registerEngine(task: TaskRecord, engine: DownloadEngine) {
        engines[task.id] = engine
    }

    @Synchronized
    fun unregisterEngine(task: TaskRecord) {
        engines.remove(task.id)
    }

    fun pauseTask(task: TaskRecord) {
        synchronized(this) { engines[task.id] }?.pause()
        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
            task.status = TaskStatus.PAUSED
        }
        store.save()
    }

    fun nextQueued(): TaskRecord? =
        QueueOrder.nextEligible(store.tasks, System.currentTimeMillis())

    fun hasActiveWork(): Boolean =
        store.tasks.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
}
