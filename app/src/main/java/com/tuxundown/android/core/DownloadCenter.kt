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
            return false
        }
        task.status = TaskStatus.QUEUED
        task.error = ""
        store.save()
        val ctx = appContext!!
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        return true
    }

    /** v1.4 插队： move a queued task to the front of the queue. */
    fun moveToFrontOfQueue(task: TaskRecord): Boolean {
        val changed = QueueOrder.moveToFrontOfQueue(store.tasks, task)
        if (changed) store.save()
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
