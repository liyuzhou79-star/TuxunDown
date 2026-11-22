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
     * v3.0: the speed-limit schedule (see SpeedSchedule). One
     * daily window in which [speedSchedLimitKBps] replaces
     * [speedLimitKBps] as the global limit — the headline
     * shape is "unlimited at night" (window limit 0). Default
     * OFF; the default window mirrors quiet hours' 23:00–07:00
     * but the two settings share nothing else.
     */
    var speedSchedEnabled: Boolean
        get() = pBool("speedSchedEnabled", false)
        set(v) { prefs().edit().putBoolean("speedSchedEnabled", v).apply() }

    var speedSchedStartMin: Int
        get() = pInt("speedSchedStartMin", SpeedSchedule.DEFAULT_START_MIN).coerceIn(0, 1439)
        set(v) { prefs().edit().putInt("speedSchedStartMin", v.coerceIn(0, 1439)).apply() }

    var speedSchedEndMin: Int
        get() = pInt("speedSchedEndMin", SpeedSchedule.DEFAULT_END_MIN).coerceIn(0, 1439)
        set(v) { prefs().edit().putInt("speedSchedEndMin", v.coerceIn(0, 1439)).apply() }

    /** The global limit inside the schedule window (KB/s; 0 = unlimited). */
    var speedSchedLimitKBps: Int
        get() = pInt("speedSchedLimitKBps", 0).coerceAtLeast(0)
        set(v) { prefs().edit().putInt("speedSchedLimitKBps", v.coerceAtLeast(0)).apply() }

    /**
     * The global limit in force RIGHT NOW: the schedule's
     * window limit inside the window, [speedLimitKBps]
     * otherwise. [budgetShareBps] reads this on every limiter
     * tick, so a running download crosses the window edge
     * live — no restart, no re-queue.
     */
    fun effectiveSpeedLimitKBps(nowMs: Long = System.currentTimeMillis()): Int =
        SpeedSchedule.effectiveLimitKBpsAt(
            speedSchedEnabled, speedSchedStartMin, speedSchedEndMin,
            speedSchedLimitKBps, speedLimitKBps, nowMs,
        )

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

    /**
     * v2.9: when a task carries an expected hash, verify the
     * finished file against it automatically at completion
     * (the standing v1.7 behaviour, now switchable). Default
     * ON. OFF = manual only, via the card's 立即校验 button;
     * verification re-reads the whole file once, which on a
     * multi-GB file is real work the user may not want at
     * completion time.
     */
    var autoVerifyHash: Boolean
        get() = pBool("autoVerifyHash", true)
        set(v) { prefs().edit().putBoolean("autoVerifyHash", v).apply() }

    /**
     * v3.0: when a .zip task finishes, extract it automatically
     * into a same-named folder next to the archive (see
     * ZipExtractor). Default OFF — an archive unpacking itself
     * is a behaviour the user opts into. The original .zip is
     * always kept; a task whose hash check FAILED is never
     * extracted (a mismatched file is suspect, not a gift).
     * Only .zip: other archive formats are not touched.
     */
    var autoExtractZip: Boolean
        get() = pBool("autoExtractZip", false)
        set(v) { prefs().edit().putBoolean("autoExtractZip", v).apply() }

    /** Completion feedback switches (v1.3). */
    var completeVibrate: Boolean
        get() = pBool("completeVibrate", true)
        set(v) { prefs().edit().putBoolean("completeVibrate", v).apply() }

    var completeSound: Boolean
        get() = pBool("completeSound", false)
        set(v) { prefs().edit().putBoolean("completeSound", v).apply() }

    /**
     * v2.5: notification quiet hours. Inside the window a
     * completion still posts its notification (with 打开 /
     * 分享 actions), but the phone neither vibrates nor rings
     * for it. Default OFF; the default window is 23:00-07:00.
     */
    var notifyQuietEnabled: Boolean
        get() = pBool("notifyQuietEnabled", false)
        set(v) { prefs().edit().putBoolean("notifyQuietEnabled", v).apply() }

    var notifyQuietStartMin: Int
        get() = pInt("notifyQuietStartMin", QuietHours.DEFAULT_START_MIN).coerceIn(0, 1439)
        set(v) { prefs().edit().putInt("notifyQuietStartMin", v.coerceIn(0, 1439)).apply() }

    var notifyQuietEndMin: Int
        get() = pInt("notifyQuietEndMin", QuietHours.DEFAULT_END_MIN).coerceIn(0, 1439)
        set(v) { prefs().edit().putInt("notifyQuietEndMin", v.coerceIn(0, 1439)).apply() }

    /** True when completion feedback should stay silent now. */
    fun quietHoursActive(nowMs: Long = System.currentTimeMillis()): Boolean =
        notifyQuietEnabled &&
            QuietHours.isQuietAt(notifyQuietStartMin, notifyQuietEndMin, nowMs)

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
     * v3.1: the sniff result dialog's remembered filter — the
     * kind and minimum size picked last time are offered again
     * on the next sniff instead of resetting to "everything"
     * (the dialog writes these on every filter change and
     * restores them when a new result list opens).
     * [sniffFilterMinMB] is whole MB, 0 = no minimum; the byte
     * conversion lives with the filter (SniffFilter).
     */
    var sniffFilterKind: String
        get() = pString("sniffFilterKind", "")
        set(v) { prefs().edit().putString("sniffFilterKind", v).apply() }

    var sniffFilterMinMB: Int
        get() = pInt("sniffFilterMinMB", 0).coerceIn(0, 102_400)
        set(v) { prefs().edit().putInt("sniffFilterMinMB", v.coerceIn(0, 102_400)).apply() }

    /**
     * v3.3: home list view — group the tasks by site instead
     * of one flat list. A view preference; rides backups like
     * the other UI settings (round-17 completeness rule).
     */
    var taskGroupBySite: Boolean
        get() = pBool("taskGroupBySite", false)
        set(v) { prefs().edit().putBoolean("taskGroupBySite", v).apply() }

    /**
     * v3.3: site groups the user folded shut, remembered
     * across launches (host names, one per line — a host can
     * never contain a newline, so the encoding is exact).
     */
    var collapsedSites: Set<String>
        get() = pString("collapsedSites", "")
            .lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        set(v) {
            prefs().edit().putString(
                "collapsedSites",
                v.filter { it.isNotBlank() }.joinToString("\n"),
            ).apply()
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

    /**
     * v2.6: after a reboot, automatically pick the unfinished
     * queue back up (interrupted transfers re-queue, queued tasks
     * start) instead of waiting for the user to open the app.
     * Default OFF — an app starting downloads by itself after a
     * reboot is the user's call, not ours. The guards all still
     * apply: 仅 WiFi keeps a task waiting for WiFi, the traffic
     * guard still blocks unconfirmed mobile downloads (see
     * DownloadService / BootReceiver).
     */
    var bootResume: Boolean
        get() = pBool("bootResume", false)
        set(v) { prefs().edit().putBoolean("bootResume", v).apply() }

    /**
     * v2.6: sort NEW finished files into category subfolders of
     * the public Download folder （视频/音频/图片/文档/压缩包/APK，
     * see FileCategory). Default OFF — with it off, files land in
     * the Download root itself, the standing rule the user set;
     * turning it on never moves files that are already down, and
     * it does not apply inside a custom SAF folder (the user
     * picked that folder themselves; the settings copy says so).
     */
    var sortSubfolders: Boolean
        get() = pBool("sortSubfolders", false)
        set(v) { prefs().edit().putBoolean("sortSubfolders", v).apply() }

    /** v2.4: auto-archive completed records older than this many
     *  days into the 已归档 filter (0 = off). Records only — the
     *  downloaded files are never touched. */
    var archiveAfterDays: Int
        get() = pInt("archiveAfterDays", 0).coerceIn(0, 3650)
        set(v) { prefs().edit().putInt("archiveAfterDays", v.coerceIn(0, 3650)).apply() }

    /** Run the archive sweep against the loaded store; persists
     *  when anything changed. Returns how many were archived. */
    fun applyArchiveSweep(): Int {
        val n = ArchivePolicy.sweep(
            store.tasks, System.currentTimeMillis(), archiveAfterDays
        )
        if (n > 0) store.saveNow()
        return n
    }

    /**
     * v3.4: delete DONE / FAILED records whose terminal time is
     * at least [days] days old (the selection rule is the pure
     * [RecordCleanup]). RECORDS ONLY — downloaded files and
     * unfinished .part files are never touched (a leftover
     * .part belongs to the orphan cleaner in the same settings
     * group).
     *
     * Safety choice — automatic backup, not just a confirm
     * dialog: a full backup (settings + tasks, the standard
     * export shape) is written into the download folder FIRST,
     * and the cleanup ABORTS (throws) when that write fails.
     * A dialog only guards the tap itself; a backup file makes
     * the deletion actually recoverable afterwards — it
     * re-imports through 设置 → 导入备份 like any other backup
     * (the records come back as records; files were never
     * deleted, so nothing else needs restoring). Returns
     * (removedCount, backupFileName); (0, "") when nothing was
     * eligible — and no backup is written in that case either.
     */
    fun cleanupOldRecords(days: Int): Pair<Int, String> {
        val ctx = appContext ?: return 0 to ""
        val now = System.currentTimeMillis()
        val victims = RecordCleanup.select(store.tasks, now, days)
        if (victims.isEmpty()) return 0 to ""
        val backupText = Backup.build(
            store.exportJson(), settingsSnapshotJson(), now,
        )
        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmm", java.util.Locale.US,
        ).format(java.util.Date(now))
        val backupName = PublicStorage.saveTextFile(
            ctx, "tuxundown-backup-before-cleanup-$stamp.json", backupText,
        )
        store.removeAll(victims)
        return victims.size to backupName
    }

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

    /**
     * v2.3 audit round 9: the proxy password is stored ONLY as a
     * Keystore ciphertext (see SecretStore). A legacy plain value
     * from <= v2.2 is migrated the first time it is read; if the
     * device Keystore refuses to encrypt, the plain form is kept
     * (no worse than v2.2) and the migration retries next read.
     */
    var proxyPass: String
        get() {
            val raw = pString("proxyPass", "")
            if (raw.isBlank()) return ""
            if (SecretStore.isEncrypted(raw)) return SecretStore.decrypt(raw)
            SecretStore.encrypt(raw)?.let { enc ->
                prefs().edit().putString("proxyPass", enc).apply()
            }
            return raw
        }
        set(v) {
            val stored = if (v.isBlank()) "" else SecretStore.encrypt(v) ?: v
            prefs().edit().putString("proxyPass", stored).apply()
            syncProxyState()
        }

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
        o.put("speedSchedEnabled", speedSchedEnabled)
        o.put("speedSchedStartMin", speedSchedStartMin)
        o.put("speedSchedEndMin", speedSchedEndMin)
        o.put("speedSchedLimitKBps", speedSchedLimitKBps)
        o.put("maxConcurrent", maxConcurrent)
        o.put("autoRetryCount", autoRetryCount)
        o.put("trafficThresholdMB", trafficThresholdMB)
        o.put("dualChannel", dualChannel)
        o.put("userAgent", userAgent)
        o.put("amoledBlack", amoledBlack)
        o.put("completeVibrate", completeVibrate)
        o.put("completeSound", completeSound)
        o.put("notifyQuietEnabled", notifyQuietEnabled)
        o.put("notifyQuietStartMin", notifyQuietStartMin)
        o.put("notifyQuietEndMin", notifyQuietEndMin)
        o.put("autoInstallApk", autoInstallApk)
        o.put("autoOpenFile", autoOpenFile)
        o.put("autoVerifyHash", autoVerifyHash)
        o.put("autoExtractZip", autoExtractZip)
        o.put("bootResume", bootResume)
        o.put("sortSubfolders", sortSubfolders)
        o.put("archiveAfterDays", archiveAfterDays)
        o.put("detailsExpanded", detailsExpanded)
        o.put("sniffHistory", org.json.JSONArray(sniffHistory))
        o.put("sniffFilterKind", sniffFilterKind)
        o.put("sniffFilterMinMB", sniffFilterMinMB)
        o.put("taskGroupBySite", taskGroupBySite)
        o.put("collapsedSites", org.json.JSONArray(collapsedSites))
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
        if (o.has("speedSchedEnabled")) {
            speedSchedEnabled = o.optBoolean("speedSchedEnabled", speedSchedEnabled)
        }
        if (o.has("speedSchedStartMin")) {
            speedSchedStartMin = o.optInt("speedSchedStartMin", speedSchedStartMin)
        }
        if (o.has("speedSchedEndMin")) {
            speedSchedEndMin = o.optInt("speedSchedEndMin", speedSchedEndMin)
        }
        if (o.has("speedSchedLimitKBps")) {
            speedSchedLimitKBps = o.optInt("speedSchedLimitKBps", speedSchedLimitKBps)
        }
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
        if (o.has("notifyQuietEnabled")) {
            notifyQuietEnabled = o.optBoolean("notifyQuietEnabled", notifyQuietEnabled)
        }
        if (o.has("notifyQuietStartMin")) {
            notifyQuietStartMin = o.optInt("notifyQuietStartMin", notifyQuietStartMin)
        }
        if (o.has("notifyQuietEndMin")) {
            notifyQuietEndMin = o.optInt("notifyQuietEndMin", notifyQuietEndMin)
        }
        if (o.has("autoInstallApk")) autoInstallApk = o.optBoolean("autoInstallApk", autoInstallApk)
        if (o.has("autoOpenFile")) autoOpenFile = o.optBoolean("autoOpenFile", autoOpenFile)
        if (o.has("autoVerifyHash")) {
            autoVerifyHash = o.optBoolean("autoVerifyHash", autoVerifyHash)
        }
        if (o.has("autoExtractZip")) {
            autoExtractZip = o.optBoolean("autoExtractZip", autoExtractZip)
        }
        if (o.has("bootResume")) bootResume = o.optBoolean("bootResume", bootResume)
        if (o.has("sortSubfolders")) {
            sortSubfolders = o.optBoolean("sortSubfolders", sortSubfolders)
        }
        if (o.has("archiveAfterDays")) {
            archiveAfterDays = o.optInt("archiveAfterDays", archiveAfterDays)
        }
        if (o.has("detailsExpanded")) {
            detailsExpanded = o.optBoolean("detailsExpanded", detailsExpanded)
        }
        val hist = o.optJSONArray("sniffHistory")
        if (hist != null) {
            sniffHistory = (0 until hist.length())
                .map { hist.optString(it) }
                .filter { it.isNotBlank() }
        }
        if (o.has("sniffFilterKind")) {
            sniffFilterKind = o.optString("sniffFilterKind", sniffFilterKind)
        }
        if (o.has("sniffFilterMinMB")) {
            sniffFilterMinMB = o.optInt("sniffFilterMinMB", sniffFilterMinMB)
        }
        if (o.has("taskGroupBySite")) {
            taskGroupBySite = o.optBoolean("taskGroupBySite", taskGroupBySite)
        }
        val sites = o.optJSONArray("collapsedSites")
        if (sites != null) {
            collapsedSites = (0 until sites.length())
                .map { sites.optString(it) }
                .filter { it.isNotBlank() }
                .toSet()
        }
        // v1.9: proxy (password deliberately not in backups).
        if (o.has("proxyEnabled")) proxyEnabled = o.optBoolean("proxyEnabled", proxyEnabled)
        if (o.has("proxyType")) proxyType = o.optInt("proxyType", proxyType)
        if (o.has("proxyHost")) proxyHost = o.optString("proxyHost", proxyHost)
        if (o.has("proxyPort")) proxyPort = o.optInt("proxyPort", proxyPort)
        if (o.has("proxyUser")) proxyUser = o.optString("proxyUser", proxyUser)
    }

    /**
     * v2.8: set (or clear) the user's own note on a task. The
     * text is sanitized here (trim + length cap) so every writer
     * gets the same rule, then persisted with the record — it
     * rides the shared serializer into backups automatically.
     */
    fun setUserNote(task: TaskRecord, raw: String) {
        task.userNote = LinkSchemes.sanitizeUserNote(raw)
        store.save()
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
            // v2.7: the per-day series stores each day's TOTAL
            // (this same number), so today is seeded whole on
            // the first completion after upgrading — see
            // DaySeries' honest-scope note.
            e.putString(
                "statsDays",
                DaySeries.serialize(
                    DaySeries.recordDayTotal(
                        DaySeries.parse(pString("statsDays", "")),
                        StatsMath.dayKey(now),
                        today + bytes,
                    )
                ),
            )
            // v2.3: per-host totals for the statistics Top 5.
            val hostEntries = HostStats.record(
                HostStats.parse(pString("hostStats", "")),
                HostMemory.hostOf(task.url),
                bytes,
            )
            e.putString("hostStats", HostStats.serialize(hostEntries))
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

    /** v2.3: the hosts the user downloads the most from, by bytes. */
    fun hostStatsTop(n: Int = 5): List<HostStats.Entry> =
        HostStats.top(HostStats.parse(pString("hostStats", "")), n)

    /** v2.7: the last [n] days of download totals, oldest first,
     *  zero-filled — the statistics screen's bar chart data. */
    fun statsLastDays(n: Int = 7): List<DaySeries.Day> =
        DaySeries.lastDays(
            DaySeries.parse(pString("statsDays", "")),
            n, System.currentTimeMillis(),
        )

    fun clearStats() {
        prefs().edit()
            .remove("statsTotalBytes").remove("statsDoneCount")
            .remove("statsTodayKey").remove("statsTodayBytes")
            .remove("statsSpeedSum").remove("statsSpeedCount")
            .remove("hostStats")
            .remove("statsDays")
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
        // v3.0: the budget is the limit in force NOW — the
        // schedule's window limit inside its window (see
        // effectiveSpeedLimitKBps), the standing limit else.
        val total = effectiveSpeedLimitKBps().toLong() * 1024
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

    /** v2.8: remembered rate-limit ceiling for this URL's host
     *  (0 = none) — the engine caps its thread count there. */
    fun hostThreadsCap(url: String): Int =
        HostMemory.capOf(hostEntries(), HostMemory.hostOf(url), System.currentTimeMillis())

    /** v2.8: remember that this URL's host rejected parallel
     *  load at [threadsAtLimit] connections (see RateLimit). */
    fun recordHostRateLimit(url: String, threadsAtLimit: Int) {
        saveHostEntries(
            HostMemory.recordRateLimit(
                hostEntries(), HostMemory.hostOf(url), threadsAtLimit,
                System.currentTimeMillis(),
            )
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

    private val deferredStartupRan =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * v2.5 cold-start round 2: work the FIRST FRAME does not
     * need, run once per process on a daemon thread after the
     * UI is up (MainActivity calls this from a LaunchedEffect;
     * the service calls it on create as a fallback for
     * service-first process starts). Today the bucket holds
     * only the archive sweep (v2.4 put it in init: it walks
     * every record and may rewrite tasks.json — real work on
     * the launch path for zero first-frame benefit).
     *
     * What deliberately stays synchronous in init, and why:
     * store.load() IS the first frame (the home screen is the
     * task list), and syncProxyState is six pref reads feeding
     * a volatile mirror the engine reads at download start.
     * Everything else was already lazy before this round —
     * stats counters, the per-host thread memory and the
     * sniff history are parsed on first USE, never at launch;
     * the clipboard read has been post-first-frame since v1.3.
     *
     * CrashGuard alignment: in safe mode the deferred bucket
     * is SKIPPED for the launch (and the one-shot is spent) —
     * the rescue path runs the minimum possible, and by
     * construction nothing in the bucket is ever essential.
     */
    fun runDeferredStartup() {
        if (!deferredStartupRan.compareAndSet(false, true)) return
        if (CrashGuard.safeMode) return
        Thread {
            runCatching { applyArchiveSweep() }
        }.apply {
            isDaemon = true
            name = "deferred-startup"
            start()
        }
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
        // v2.8: a manual （重新）排队 also retires a stale pre-check
        // warning — the user has decided to try this link again.
        task.precheckDead = false
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
            refreshWidget()
            return false
        }
        task.status = TaskStatus.QUEUED
        task.error = ""
        store.save()
        val ctx = appContext!!
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        refreshWidget()
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
            // v3.1: 插队 also raises the task to the 高 tier
            // (priority 1 IS 高 — the same value v1.6 wrote).
            if (task.priority == 0) task.priority = 1
            store.save()
        }
        return changed
    }

    /**
     * v3.1: set a task's priority tier （高/中/低， see
     * TaskPriority). The drain order and the bandwidth split
     * both read the record live, so the change applies to a
     * queued task's next start and to a running task's budget
     * share on the next limiter tick.
     */
    fun setPriority(task: TaskRecord, level: Int) {
        val v = TaskPriority.clamp(level)
        if (task.priority == v) return
        task.priority = v
        store.save()
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
        refreshWidget()
    }

    /**
     * v2.7: 全部暂停 / 全部继续 — the bulk counterparts of
     * [pauseTask] / [enqueue], driven by the pure [BulkControl]
     * target filters (the same semantics as the multi-select
     * bar). Shared by the home summary card and the
     * quick-settings tile. Each resume is guarded individually:
     * starting the foreground service can be refused when the
     * caller is a background-bound tile, and one refusal must
     * not stop the rest — the task is already marked QUEUED
     * and saved by then, so it drains the next time the
     * service runs.
     */
    fun pauseAll(): Int {
        val targets = BulkControl.pauseTargets(store.tasks)
        for (t in targets) pauseTask(t)
        return targets.size
    }

    fun resumeAll(): Int {
        val targets = BulkControl.resumeTargets(store.tasks)
        for (t in targets) runCatching { enqueue(t) }
        return targets.size
    }

    /**
     * v2.1: keep the home-screen widget honest after changes that
     * may not wake the service (a pause of the last task, an
     * enqueue that returns early). Best-effort — the updater
     * swallows every failure and no-ops when no widget exists.
     */
    private fun refreshWidget() {
        val ctx = appContext ?: return
        com.tuxundown.android.widget.WidgetUpdater.refresh(ctx)
    }

    /**
     * v2.6: re-queue the tasks the SYSTEM interrupted (see
     * Recovery) — the persisted RUNNING records load() parked as
     * PAUSED with wasRunning set. Called from the boot path (only
     * when the user's 开机恢复 switch is on) and from the
     * service's START_STICKY restart, where resuming is plain
     * continuity: the service was already running these tasks
     * when the process died. A task the user paused is never
     * touched. The re-queued tasks go through the normal drain,
     * so 仅 WiFi / traffic / schedule rules all still apply.
     * Returns how many were re-queued.
     */
    fun recoverInterrupted(): Int {
        val hit = Recovery.interrupted(store.tasks)
        if (hit.isEmpty()) return 0
        for (t in hit) {
            t.wasRunning = false
            t.status = TaskStatus.QUEUED
            t.error = ""
            t.note = "上次下载被系统中断（重启或进程被杀），已自动重新排队继续。"
        }
        store.saveNow()
        return hit.size
    }

    fun nextQueued(): TaskRecord? =
        QueueOrder.nextEligible(store.tasks, System.currentTimeMillis())

    fun hasActiveWork(): Boolean =
        store.tasks.any { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
}
