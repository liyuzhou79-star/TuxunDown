package com.tuxundown.android.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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

    /** v7.4 (audit round 59, Step C): how many engine claims
     *  are held right now — read-only, for diagnostics. */
    fun claimedCount(): Int = engineClaims.size()

    var wifiOnly: Boolean
        get() = pBool("wifiOnly", false)
        set(v) { prefs().edit().putBoolean("wifiOnly", v).apply() }

    /**
     * v3.9: charge-only downloads (see ChargeGuard) — while
     * unplugged, tasks that have not started yet park in the
     * queue; plugging power in starts them on their own.
     * Default off, like the other protective switches.
     */
    var chargeOnlyDownload: Boolean
        get() = pBool("chargeOnlyDownload", false)
        set(v) { prefs().edit().putBoolean("chargeOnlyDownload", v).apply() }

    /**
     * v4.9 (audit round 34): keep the CPU awake while a
     * transfer is actually running (see WakeGuard), so
     * screen-off / Doze suspend cannot sag throughput.
     * Default ON — deliberately unlike chargeOnlyDownload
     * above (a protective gate, default off): this switch
     * guards the app's core promise (speed with the screen
     * off), and its honest cost (more battery) is spelled out
     * on the switch itself. Only real transfer time holds
     * the lock — queueing, parking and backoff never do.
     */
    var keepCpuAwake: Boolean
        get() = pBool("keepCpuAwake", true)
        set(v) { prefs().edit().putBoolean("keepCpuAwake", v).apply() }

    /**
     * v7.8 (Step A): tail duplication (see TailRace) — in the
     * endgame, the last unsplittable span is fetched by two
     * connections at once and the first finisher wins. Default
     * OFF: the first-finisher-wins accounting is unit-pinned,
     * but the live wiring has no real-device run yet, so the
     * user opts in explicitly. Duplicate bytes are booked
     * separately and never inflate speed or progress either way.
     */
    var tailRaceEnabled: Boolean
        get() = pBool("tailRaceEnabled", false)
        set(v) { prefs().edit().putBoolean("tailRaceEnabled", v).apply() }

    /**
     * v3.9: is external power connected right now? Read from
     * the sticky ACTION_BATTERY_CHANGED broadcast — querying it
     * with a null receiver returns the last broadcast without
     * registering anything and needs no permission. A state
     * that cannot be read counts as charging: a gate that
     * cannot tell must never strand the queue.
     */
    fun isCharging(): Boolean {
        val ctx = appContext ?: return true
        val sticky = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return ChargeGuard.isCharging(status)
    }

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

    // ---- v3.7: optional task-card detail rows (see CardFields
    // for the catalogue). Defaults reproduce the pre-3.7 card:
    // the rows that existed default ON, the new ones OFF. ----
    var cardShowAvgSpeed: Boolean
        get() = pBool("cardShowAvgSpeed", CardFields.defaultOf(CardFields.AVG_SPEED))
        set(v) { prefs().edit().putBoolean("cardShowAvgSpeed", v).apply() }
    var cardShowRetryCount: Boolean
        get() = pBool("cardShowRetryCount", CardFields.defaultOf(CardFields.RETRY_COUNT))
        set(v) { prefs().edit().putBoolean("cardShowRetryCount", v).apply() }
    var cardShowCreatedAt: Boolean
        get() = pBool("cardShowCreatedAt", CardFields.defaultOf(CardFields.CREATED_AT))
        set(v) { prefs().edit().putBoolean("cardShowCreatedAt", v).apply() }
    var cardShowHost: Boolean
        get() = pBool("cardShowHost", CardFields.defaultOf(CardFields.HOST))
        set(v) { prefs().edit().putBoolean("cardShowHost", v).apply() }
    var cardShowChunks: Boolean
        get() = pBool("cardShowChunks", CardFields.defaultOf(CardFields.CHUNKS))
        set(v) { prefs().edit().putBoolean("cardShowChunks", v).apply() }
    var cardShowServerProfile: Boolean
        get() = pBool("cardShowServerProfile", CardFields.defaultOf(CardFields.SERVER_PROFILE))
        set(v) { prefs().edit().putBoolean("cardShowServerProfile", v).apply() }

    /** v3.7: key-based access to the card-field switches — the
     *  settings screen iterates CardFields.FIELDS and the task
     *  card asks per key, so the key→property mapping lives in
     *  exactly one place. */
    fun cardFieldShown(key: String): Boolean = when (key) {
        CardFields.AVG_SPEED -> cardShowAvgSpeed
        CardFields.RETRY_COUNT -> cardShowRetryCount
        CardFields.CREATED_AT -> cardShowCreatedAt
        CardFields.HOST -> cardShowHost
        CardFields.CHUNKS -> cardShowChunks
        CardFields.SERVER_PROFILE -> cardShowServerProfile
        else -> CardFields.defaultOf(key)
    }

    fun setCardField(key: String, v: Boolean) {
        when (key) {
            CardFields.AVG_SPEED -> cardShowAvgSpeed = v
            CardFields.RETRY_COUNT -> cardShowRetryCount = v
            CardFields.CREATED_AT -> cardShowCreatedAt = v
            CardFields.HOST -> cardShowHost = v
            CardFields.CHUNKS -> cardShowChunks = v
            CardFields.SERVER_PROFILE -> cardShowServerProfile = v
        }
    }

    fun cardFieldsSnapshot(): Map<String, Boolean> =
        CardFields.FIELDS.associate { it.key to cardFieldShown(it.key) }

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
     * v5.8: when a task is created, also try the website's
     * sidecar checksum files ("<url>.sha256" / "<url>.md5",
     * see SidecarChecksum) and fill the expected hash from
     * there. Default OFF — with it off, task creation makes
     * ZERO extra network requests. When on, the fetch is
     * asynchronous, bounded and silent on every failure, and
     * never overwrites a hand-typed hash.
     */
    var autoReadChecksumFile: Boolean
        get() = pBool("autoReadChecksumFile", false)
        set(v) { prefs().edit().putBoolean("autoReadChecksumFile", v).apply() }

    /**
     * v5.8: the sidecar fetch itself, installed as the store's
     * creation hook (see TaskStore.onTaskAdded — every creation
     * entry ends in store.add, so this is the one funnel).
     * Cheap gate first: with the switch off, or for an
     * ineligible task, it returns without touching the network
     * or even starting a thread. Otherwise ONE daemon thread
     * tries the sidecars in order (sha256, then md5), each
     * bounded to [SidecarChecksum.TIMEOUT_MS] and
     * [SidecarChecksum.MAX_BODY_BYTES], through ProxyState
     * (the configured proxy) with the task's own session
     * headers — the same stack the precheck uses. The first
     * body that parses for this task fills it (hand-typed
     * priority is re-checked at fill time in
     * SidecarChecksum.applyTo) and persists via the coalesced
     * save. Every failure is silent by design.
     */
    /**
     * v7.7 (Step B3): creation-moment queue-preheat hook,
     * registered by DownloadService while it lives (the preheat
     * machinery — its warmed marks, its one-at-a-time slot, the
     * warm thread — is the service's). Invoked from the
     * onTaskAdded funnel above with the created task's id; the
     * service judges it through QueuePreheat.decideAtCreation
     * under the FULL v4.5 guard set. Null while no service is
     * running — in that case nobody can be RUNNING either, so
     * the guard would veto anyway, and the drain's standing
     * beat judges the task when the service next runs.
     */
    @Volatile
    var creationPreheatHook: ((String) -> Unit)? = null

    internal fun maybeFetchSidecarChecksum(task: TaskRecord) {
        if (!SidecarChecksum.shouldFetch(autoReadChecksumFile, task)) return
        val targetName = SidecarChecksum.targetFileName(task)
        val urls = SidecarChecksum.sidecarUrls(task.url)
        Thread {
            runCatching {
                for (sidecarUrl in urls) {
                    // The task may have been deleted, or the user
                    // may have typed a hash meanwhile.
                    if (store.byId(task.id) == null) return@Thread
                    if (task.expectedSha256.isNotBlank() ||
                        task.expectedMd5.isNotBlank()
                    ) {
                        return@Thread
                    }
                    val body = fetchSidecarBody(task, sidecarUrl) ?: continue
                    val parsed = SidecarChecksum.parse(body, targetName) ?: continue
                    if (SidecarChecksum.applyTo(task, parsed)) {
                        store.save()
                    }
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "sidecar-checksum"
            start()
        }
    }

    /** One bounded sidecar GET; null on any non-200 / error. */
    private fun fetchSidecarBody(task: TaskRecord, sidecarUrl: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            // v8.2 audit round 67: redirects are followed MANUALLY
            // (see AuxRedirect). This fetch carries the task's
            // Cookie; a platform-followed hop would re-send it to
            // whatever host the Location names. Each hop re-asks
            // SessionScope against the task's original url, so the
            // Cookie only ever reaches the task's own host.
            var current = sidecarUrl
            var redirects = 0
            var result: String? = null
            var done = false
            while (!done) {
                val c = ProxyState.open(java.net.URL(current))
                conn = c
                c.connectTimeout = SidecarChecksum.TIMEOUT_MS
                c.readTimeout = SidecarChecksum.TIMEOUT_MS
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty(
                    "User-Agent",
                    UserAgents.resolve(task.headers["User-Agent"], userAgent),
                )
                SessionScope.cookieFor(task.headers["Cookie"], task.url, current)
                    ?.let { c.setRequestProperty("Cookie", it) }
                task.headers["Referer"]?.let { c.setRequestProperty("Referer", it) }
                val code = c.responseCode
                if (AuxRedirect.isRedirect(code)) {
                    val next = AuxRedirect.nextUrl(
                        current, c.getHeaderField("Location"), redirects,
                    )
                    if (next != null) {
                        runCatching { c.disconnect() }
                        conn = null
                        current = next
                        redirects++
                        continue
                    }
                }
                if (code == 200) {
                    result = c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        // Read at most the cap + 1 byte; anything past
                        // the cap is discarded (parse judges the head).
                        var remaining = SidecarChecksum.MAX_BODY_BYTES + 1
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, remaining))
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        val bytes = out.toByteArray()
                        val bounded = if (bytes.size > SidecarChecksum.MAX_BODY_BYTES) {
                            bytes.copyOf(SidecarChecksum.MAX_BODY_BYTES)
                        } else {
                            bytes
                        }
                        String(bounded, Charsets.UTF_8)
                    }
                }
                done = true
            }
            result
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

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

    /**
     * v6.3: session-scoped companion of [clipboardPrompted] —
     * every clipboard link prompted about (or dismissed) during
     * THIS process run. Memory only, never persisted: the set
     * dies with the process. It closes the single slot's one
     * hole (A -> B -> A re-prompted A within a session, because
     * the slot only holds the most recent link) WITHOUT
     * weakening the slot's cross-session suppression — the
     * verdict is slot-hit OR session-hit (see ClipboardPrompt
     * for the full caliber). Guarded by its own monitor: the
     * prompt decision and the marks both happen on the UI
     * thread today, but the set costs nothing to keep honest.
     */
    private val clipboardSessionSeen = mutableSetOf<String>()

    /** The one prompt verdict: persisted slot OR session set. */
    fun shouldPromptClipboard(url: String): Boolean =
        ClipboardPrompt.shouldPrompt(
            url,
            clipboardPrompted,
            synchronized(clipboardSessionSeen) { clipboardSessionSeen.toSet() },
        )

    /**
     * Record that [url] was prompted about — shown and answered,
     * confirmed, or dismissed all count the same (the user has
     * seen it; asking again this session would be nagging).
     * Writes BOTH layers: the session set (this run) and the
     * persisted slot (its existing cross-session job).
     */
    fun markClipboardPrompted(url: String) {
        if (url.isBlank()) return
        synchronized(clipboardSessionSeen) { clipboardSessionSeen.add(url) }
        clipboardPrompted = url
    }

    // ---- v7.3 (audit round 58, B1): tasks.json recovery event ----
    // The latest load that had to self-heal (fell back to .bak,
    // merged records back from .bak, dropped records, or found
    // both files bad) is persisted as one small JSON object under
    // "tasksRecoveryEvent" in the settings prefs: Step C's
    // diagnostics section reads it, and the home banner shows it
    // once per event. A clean load never overwrites it.

    /** One persisted tasks.json recovery event. */
    data class TasksRecoveryEvent(
        val atMs: Long,
        val source: String,
        val droppedRecords: Int,
        val bakMergedRecords: Int,
        val doubleBad: Boolean,
    )

    /** Record [outcome] if it describes an actual recovery/loss;
     *  a clean MAIN load with no drops is not an event. */
    fun recordTasksRecovery(outcome: LoadOutcome) {
        val isEvent = outcome.doubleBad ||
            outcome.source == LoadSource.BAK ||
            outcome.droppedRecords > 0 ||
            outcome.bakMergedRecords > 0
        if (!isEvent) return
        val o = org.json.JSONObject()
        o.put("atMs", System.currentTimeMillis())
        o.put("source", outcome.source.name)
        o.put("dropped", outcome.droppedRecords)
        o.put("merged", outcome.bakMergedRecords)
        o.put("doubleBad", outcome.doubleBad)
        prefs().edit().putString("tasksRecoveryEvent", o.toString()).apply()
    }

    /** The persisted latest recovery event, or null when none. */
    fun tasksRecoveryEvent(): TasksRecoveryEvent? {
        val raw = pString("tasksRecoveryEvent", "")
        if (raw.isBlank()) return null
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        return TasksRecoveryEvent(
            atMs = o.optLong("atMs", 0),
            source = o.optString("source", ""),
            droppedRecords = o.optInt("dropped", 0),
            bakMergedRecords = o.optInt("merged", 0),
            doubleBad = o.optBoolean("doubleBad", false),
        )
    }

    /** The event the home banner still owes the user: an event
     *  exists and has not been dismissed yet (dismissal records
     *  the event's own timestamp, so the NEXT event shows again
     *  while this one never repeats). */
    fun pendingTasksRecoveryEvent(): TasksRecoveryEvent? {
        val e = tasksRecoveryEvent() ?: return null
        if (e.atMs <= 0) return null
        return if (pLong("tasksRecoveryEventShownAt", 0) == e.atMs) null else e
    }

    /** Dismiss the banner for the event stamped [atMs]. */
    fun markTasksRecoveryEventShown(atMs: Long) {
        prefs().edit().putLong("tasksRecoveryEventShownAt", atMs).apply()
    }

    /** Plain-language banner line for one recovery event. */
    fun tasksRecoveryEventText(e: TasksRecoveryEvent): String = when {
        e.doubleBad ->
            "任务列表文件已损坏且备份也无法读取，已把坏文件原样保留为 tasks.json.corrupt，本次以空列表启动。"
        e.source == LoadSource.BAK.name ->
            "任务列表文件已损坏，已从备份恢复全部任务。"
        e.bakMergedRecords > 0 ->
            "任务列表有 ${e.droppedRecords} 条记录损坏，已从备份补回 ${e.bakMergedRecords} 条。"
        else ->
            "任务列表有 ${e.droppedRecords} 条记录损坏，已跳过损坏记录，其余任务完好。"
    }

    /** v1.4: how many tasks may download at the same time (1–5). */
    var maxConcurrent: Int
        get() = pInt("maxConcurrent", 1).coerceIn(1, 5)
        set(v) { prefs().edit().putInt("maxConcurrent", v.coerceIn(1, 5)).apply() }

    /**
     * v3.9: the global connection cap — the TOTAL number of
     * connections all running tasks may hold at once (see
     * ConnectionBudget). Default 32, which is exactly what one
     * task could already open on its own before this setting
     * existed, so upgrading changes nothing until a second
     * task runs alongside. Range 8–128 (v4.2: was 8–64).
     */
    var globalConnectionCap: Int
        get() = pInt("globalConnectionCap", ConnectionBudget.DEFAULT_CAP)
            .coerceIn(ConnectionBudget.MIN_CAP, ConnectionBudget.MAX_CAP)
        set(v) {
            prefs().edit().putInt(
                "globalConnectionCap", ConnectionBudget.clampCap(v)
            ).apply()
        }

    /**
     * v3.9: this task's current allowance under the global
     * connection cap, split across the RUNNING tasks by
     * priority weight (see ConnectionBudget.allocate). Read
     * live by the segmented engine on every scaler tick, so a
     * task starting or finishing rebalances everyone within
     * seconds. A task that is not (yet) RUNNING gets the whole
     * cap — the split only governs live contention.
     */
    fun connectionAllowanceFor(taskId: String): Int {
        val cap = globalConnectionCap
        val running = store.tasks.filter { it.status == TaskStatus.RUNNING }
        if (running.isEmpty()) return cap
        val demands = running.map {
            ConnectionBudget.Demand(
                it.id, BandwidthBudget.weightOf(it.priority),
                connectionDesireOf(it),
            )
        }
        return ConnectionBudget.allocate(cap, demands)[taskId] ?: cap
    }

    /** How many connections a task could use with no global
     *  cap in the way. HLS fetches segments up to
     *  HLS_MAX_PARALLEL at a time (v4.3: the adaptive width's
     *  ceiling — the desire is what the task COULD use, so the
     *  accounting reserves for the max, not the fixed 4 it
     *  used before), single-stream runs hold exactly one, and
     *  FTP runs segment up to FTP_MAX_CONNECTIONS (v4.8). The
     *  when order matters: a singleThreadOnly FTP task still
     *  hits the 1 branch first. An FTP task whose probe has not
     *  decided yet is briefly reserved at the FTP ceiling; a
     *  fallback run stamps singleThreadOnly and the desire
     *  self-corrects to 1 on the next read.
     *
     *  v7.9 (audit round 64): the SEGMENTED desire is the
     *  task's LIVE scaler target (TaskRecord.threads — the
     *  engine keeps it equal to the target, never pressed by
     *  the allowance, so there is no ratchet), not the static
     *  MAX_THREADS ceiling it used before. With the ceiling as
     *  the desire, a task whose scaler had settled at 8 still
     *  reserved an equal split of the cap (16 of 32 in a
     *  two-task split) and the unused half of its share never
     *  flowed to a hungrier task for the whole run; the live
     *  target lets ConnectionBudget.allocate hand the leftover
     *  over on the very next tick, and it makes the detail
     *  line's desire the same number the engine actually
     *  wants. Before the scaler exists (the probe window) the
     *  persisted target stands in and self-corrects the moment
     *  the engine stamps the real one. HLS / FTP keep the
     *  reserve-for-max form: the HLS width is not stored
     *  pre-gate anywhere, and FTP's count is fixed at start. */
    private fun connectionDesireOf(t: TaskRecord): Int = when {
        t.isHls -> DownloadEngine.HLS_MAX_PARALLEL
        t.singleThreadOnly -> 1
        FtpClient.isFtpUrl(t.url) -> DownloadEngine.FTP_MAX_CONNECTIONS
        else -> t.threads.coerceIn(1, DownloadEngine.MAX_THREADS)
    }

    /** v5.9: the per-task connection ceiling behind
     *  [connectionDesireOf] — the most this task's engine could
     *  ever open on its own (HLS width / FTP's 8 / MAX_THREADS;
     *  a single-stream run's ceiling is the 1 it holds). Same
     *  branch order as the desire, so desire <= ceiling always
     *  holds for a real record. */
    fun connectionCeilingOf(t: TaskRecord): Int = when {
        t.isHls -> DownloadEngine.HLS_MAX_PARALLEL
        t.singleThreadOnly -> 1
        FtpClient.isFtpUrl(t.url) -> DownloadEngine.FTP_MAX_CONNECTIONS
        else -> DownloadEngine.MAX_THREADS
    }

    /**
     * v5.9: the task detail's 「连接预算」 row (see
     * ConnectionBudgetLine for the verdict rules). Gathers the
     * live snapshot the pure verdict needs — the allowance and
     * desire are recomputed on the spot exactly as the engine
     * reads them, the host cap is the same lookup the server
     * profile uses, and the live count is the record's
     * displayConnections. Null for any non-RUNNING task: with
     * no run there is no current allowance to speak of, and
     * the row stays absent instead of printing a stale figure.
     * Called from the detail card, which the existing 1 s
     * liveTick already recomposes — no new polling.
     */
    fun connectionBudgetLineFor(task: TaskRecord): String? {
        if (task.status != TaskStatus.RUNNING) return null
        val othersRunning =
            store.tasks.count { it.status == TaskStatus.RUNNING } > 1
        return ConnectionBudgetLine.judge(
            allowance = connectionAllowanceFor(task.id),
            desire = connectionDesireOf(task),
            hostCap = hostThreadsCap(task.url),
            taskCeiling = connectionCeilingOf(task),
            globalCap = globalConnectionCap,
            liveConnections = task.displayConnections,
            othersRunning = othersRunning,
        ).line
    }

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
        // v5.7: the ordering/dedup/cap rule lives in the pure
        // SniffHistory (unit-tested); this stays the only writer.
        sniffHistory = SniffHistory.afterSuccessfulSniff(sniffHistory, url)
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
     * v5.2: UI-readable mirror of the service's private
     * dualActive (same pattern as the ProxyState mirror —
     * syncProxyState). True ONLY while the cellular leg has
     * actually been granted; the service writes it at every
     * point where its own dualActive changes (acquire, leg
     * lost, release). The summary card reads this volatile on
     * its standing 1 s tick — no polling or callback is added
     * for it. A stale-true after a process death is impossible:
     * the mirror lives and dies with the process, like the
     * service state it mirrors.
     */
    @Volatile
    var dualChannelActive: Boolean = false
        internal set

    /**
     * v7.6 (audit round 61, Step C): UI-readable mirrors of
     * the service's two long-lived loops, for the
     * diagnostics page's 功耗 poll-source count (see
     * PowerStats for the pinned caliber). Same pattern as
     * [dualChannelActive]: DownloadService writes each
     * mirror true at its loop's entry and false when the
     * loop's thread exits, and writes nothing else. Null
     * means the service has not reported in this process —
     * the diagnostics page treats that as UNREADABLE and
     * drops the whole poll-source figure rather than
     * guessing. The mirrors live and die with the process,
     * like the loops they mirror.
     */
    @Volatile
    var drainLoopAlive: Boolean? = null
        internal set

    /** See [drainLoopAlive] — the link-precheck loop's
     *  mirror. */
    @Volatile
    var precheckLoopAlive: Boolean? = null
        internal set

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
        onRecordsRemoved(victims)
        return victims.size to backupName
    }

    /**
     * v7.5 (audit round 60, Step C): the ONE funnel every UI
     * record-deletion path reports through AFTER the records
     * are gone (MainActivity.deleteTask, this cleanup, the
     * downloaded-files page, duplicate deletion, reconcile
     * dispositions). It hands the removed ids to
     * [DownloadService.onRecordsDeleted], which prunes the
     * notification merge lists and cancels the failure
     * notification when its last entry goes — same process,
     * direct call, no new IPC. No-ops until [init] has run
     * (no context, nothing to cancel through).
     */
    fun onRecordsRemoved(removed: List<TaskRecord>) {
        if (removed.isEmpty()) return
        val ctx = appContext ?: return
        DownloadService.onRecordsDeleted(ctx, removed.map { it.id }.toSet())
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

    /**
     * v7.2 (audit round 57, Step C): the proxy password's
     * storage MODE for the diagnostics 「安全现状」 section,
     * classified from the raw stored form's shape only
     * (see SecurityStatus.proxyPassStorageOf). This reads
     * the stored value's prefix / blankness and nothing
     * else — it never decrypts it and never hands the
     * value (or any part of it) to the caller.
     */
    fun proxyPassStorageStatus(): SecurityStatus.ProxyPassStorage =
        SecurityStatus.proxyPassStorageOf(pString("proxyPass", ""))

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
        o.put("chargeOnlyDownload", chargeOnlyDownload)
        o.put("keepCpuAwake", keepCpuAwake)
        o.put("speedLimitKBps", speedLimitKBps)
        o.put("speedSchedEnabled", speedSchedEnabled)
        o.put("speedSchedStartMin", speedSchedStartMin)
        o.put("speedSchedEndMin", speedSchedEndMin)
        o.put("speedSchedLimitKBps", speedSchedLimitKBps)
        o.put("maxConcurrent", maxConcurrent)
        o.put("globalConnectionCap", globalConnectionCap)
        o.put("autoRetryCount", autoRetryCount)
        o.put("trafficThresholdMB", trafficThresholdMB)
        o.put("dualChannel", dualChannel)
        o.put("tailRaceEnabled", tailRaceEnabled)
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
        o.put("autoReadChecksumFile", autoReadChecksumFile)
        o.put("autoExtractZip", autoExtractZip)
        o.put("bootResume", bootResume)
        o.put("sortSubfolders", sortSubfolders)
        o.put("archiveAfterDays", archiveAfterDays)
        o.put("detailsExpanded", detailsExpanded)
        o.put("cardShowAvgSpeed", cardShowAvgSpeed)
        o.put("cardShowRetryCount", cardShowRetryCount)
        o.put("cardShowCreatedAt", cardShowCreatedAt)
        o.put("cardShowHost", cardShowHost)
        o.put("cardShowChunks", cardShowChunks)
        o.put("cardShowServerProfile", cardShowServerProfile)
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
        if (o.has("chargeOnlyDownload")) {
            chargeOnlyDownload = o.optBoolean("chargeOnlyDownload", chargeOnlyDownload)
        }
        if (o.has("keepCpuAwake")) {
            keepCpuAwake = o.optBoolean("keepCpuAwake", keepCpuAwake)
        }
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
        if (o.has("globalConnectionCap")) {
            globalConnectionCap = o.optInt("globalConnectionCap", globalConnectionCap)
        }
        if (o.has("autoRetryCount")) autoRetryCount = o.optInt("autoRetryCount", autoRetryCount)
        if (o.has("trafficThresholdMB")) {
            trafficThresholdMB = o.optInt("trafficThresholdMB", trafficThresholdMB)
        }
        if (o.has("dualChannel")) dualChannel = o.optBoolean("dualChannel", dualChannel)
        if (o.has("tailRaceEnabled")) {
            tailRaceEnabled = o.optBoolean("tailRaceEnabled", tailRaceEnabled)
        }
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
        if (o.has("autoReadChecksumFile")) {
            autoReadChecksumFile = o.optBoolean("autoReadChecksumFile", autoReadChecksumFile)
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
        if (o.has("cardShowAvgSpeed")) {
            cardShowAvgSpeed = o.optBoolean("cardShowAvgSpeed", cardShowAvgSpeed)
        }
        if (o.has("cardShowRetryCount")) {
            cardShowRetryCount = o.optBoolean("cardShowRetryCount", cardShowRetryCount)
        }
        if (o.has("cardShowCreatedAt")) {
            cardShowCreatedAt = o.optBoolean("cardShowCreatedAt", cardShowCreatedAt)
        }
        if (o.has("cardShowHost")) {
            cardShowHost = o.optBoolean("cardShowHost", cardShowHost)
        }
        if (o.has("cardShowChunks")) {
            cardShowChunks = o.optBoolean("cardShowChunks", cardShowChunks)
        }
        if (o.has("cardShowServerProfile")) {
            cardShowServerProfile = o.optBoolean("cardShowServerProfile", cardShowServerProfile)
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

    // ---- v3.6: migration-pack sections — statistics + host
    // learning. The plain v1.8 backup does NOT carry these; the
    // migration pack (Backup.buildMigration) does, so a phone
    // move keeps the accumulated totals and the per-host thread
    // knowledge instead of starting both from zero. Neither
    // section contains credentials: statistics are byte counts
    // and day keys; host learning is host names + thread numbers.

    /** Snapshot of every statistics counter (raw stored values,
     *  so the day-rollover logic keeps working after a restore). */
    fun statsSnapshotJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("totalBytes", pLong("statsTotalBytes", 0))
        o.put("doneCount", pInt("statsDoneCount", 0))
        o.put("todayKey", pString("statsTodayKey", ""))
        o.put("todayBytes", pLong("statsTodayBytes", 0))
        o.put("speedSum", pLong("statsSpeedSum", 0))
        o.put("speedCount", pInt("statsSpeedCount", 0))
        o.put("hostStats", pString("hostStats", ""))
        o.put("days", pString("statsDays", ""))
        return o
    }

    /** Apply a statistics object from a migration pack; absent
     *  keys keep their current values (same rule as settings). */
    fun applyStatsJson(o: org.json.JSONObject) {
        val e = prefs().edit()
        if (o.has("totalBytes")) e.putLong("statsTotalBytes", o.optLong("totalBytes"))
        if (o.has("doneCount")) e.putInt("statsDoneCount", o.optInt("doneCount"))
        if (o.has("todayKey")) e.putString("statsTodayKey", o.optString("todayKey"))
        if (o.has("todayBytes")) e.putLong("statsTodayBytes", o.optLong("todayBytes"))
        if (o.has("speedSum")) e.putLong("statsSpeedSum", o.optLong("speedSum"))
        if (o.has("speedCount")) e.putInt("statsSpeedCount", o.optInt("speedCount"))
        if (o.has("hostStats")) e.putString("hostStats", o.optString("hostStats"))
        if (o.has("days")) e.putString("statsDays", o.optString("days"))
        e.apply()
    }

    /** Per-host learning data in HostMemory's serialized form. */
    fun hostMemorySnapshot(): String = pString("hostThreads", "")

    /** v5.5: how many [HostMemory] entries are currently stored
     *  (the diagnostics 一键体检's 学习数据 row — a count of the
     *  existing parse result, nothing new is measured). */
    fun hostMemoryCount(): Int = hostEntries().size

    /** v5.5: how many [HostStats] entries are currently stored
     *  (same row as [hostMemoryCount]). */
    fun hostStatsCount(): Int {
        val (entries, dropped) = HostStats.parseWithLoss(pString("hostStats", ""))
        noteSubStoreDropped(dropped)
        return entries.size
    }

    /** Restore per-host learning data. The text is parsed and
     *  re-serialized before storing, so malformed lines from a
     *  hand-edited file drop out instead of being stored
     *  verbatim (HostMemory.parse is tolerant by design). */
    fun applyHostMemory(text: String) {
        val (entries, dropped) = HostMemory.parseWithLoss(text)
        noteSubStoreDropped(dropped)
        saveHostEntries(entries)
    }

    // ---- v4.1: speed-test history — LOCAL ONLY. The key below
    // is deliberately absent from settingsSnapshotJson AND
    // statsSnapshotJson, so neither backup shape (v1.8 backup /
    // v3.6 migration pack) ever carries it: Backup only wraps
    // the snapshots it is handed. A speed measurement describes
    // this device on this network; it does not migrate.

    /** The stored quick-test history, newest first (max 10). */
    fun speedHistory(): List<SpeedHistory.Entry> =
        SpeedHistory.parse(pString("speedHistory", ""))

    /** Record one completed quick test (see SpeedHistory for
     *  what counts as completed; the caller checks the verdict). */
    fun recordSpeedTest(entry: SpeedHistory.Entry) {
        val next = SpeedHistory.add(speedHistory(), entry)
        prefs().edit().putString("speedHistory", SpeedHistory.serialize(next)).apply()
    }

    /** One-tap clear from the speed-test screen. */
    fun clearSpeedHistory() {
        prefs().edit().remove("speedHistory").apply()
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
            val (dayEntries, dayDropped) =
                DaySeries.parseWithLoss(pString("statsDays", ""))
            noteSubStoreDropped(dayDropped)
            e.putString(
                "statsDays",
                DaySeries.serialize(
                    DaySeries.recordDayTotal(
                        dayEntries,
                        StatsMath.dayKey(now),
                        today + bytes,
                    )
                ),
            )
            // v2.3: per-host totals for the statistics Top 5.
            val (storedHostEntries, hostDropped) =
                HostStats.parseWithLoss(pString("hostStats", ""))
            noteSubStoreDropped(hostDropped)
            val hostEntries = HostStats.record(
                storedHostEntries,
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
    fun hostStatsTop(n: Int = 5): List<HostStats.Entry> {
        val (entries, dropped) = HostStats.parseWithLoss(pString("hostStats", ""))
        noteSubStoreDropped(dropped)
        return HostStats.top(entries, n)
    }

    /** v2.7: the last [n] days of download totals, oldest first,
     *  zero-filled — the statistics screen's bar chart data. */
    fun statsLastDays(n: Int = 7): List<DaySeries.Day> {
        val (entries, dropped) = DaySeries.parseWithLoss(pString("statsDays", ""))
        noteSubStoreDropped(dropped)
        return DaySeries.lastDays(entries, n, System.currentTimeMillis())
    }

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
        // v3.5: the record goes away in the service; re-point
        // the backup alarm now — and if the slot still shows
        // up, the receiver's fire-time re-check self-heals.
        refreshScheduleAlarm()
    }

    @Synchronized
    fun consumeCancelled(task: TaskRecord): Boolean = cancelled.remove(task.id)

    /**
     * v7.4 (audit round 59, C-4): drop a cancelled mark WITHOUT
     * acting on it. runOne's not-handed-off finally calls this so
     * exits that never reach a consumeCancelled check (the
     * loop-head early returns, a backoff sleep interrupted by the
     * record vanishing) cannot leave the id in the set forever —
     * the set used to grow by one UUID per such occurrence for
     * the whole process lifetime.
     */
    @Synchronized
    fun discardCancelled(taskId: String) {
        cancelled.remove(taskId)
    }

    private fun prefs() = appContext!!.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // v1.5 startup-safety audit: a SharedPreferences value stored
    // under the wrong type by an older build makes the typed getter
    // throw ClassCastException — on a first-frame settings read that
    // is a launch crash (the v1.3.0 lesson: never again). Every read
    // below therefore goes through a runCatching helper with the
    // documented default as the fallback. Writes stay direct.
    // v7.3 (audit round 58, B5): every fallback above used to be
    // invisible. Now a fallen-back read records its KEY NAME (never
    // a value) into a process-local set, and the sub-store parsers'
    // dropped-line counts accumulate alongside — both feed the
    // diagnostics 存储健康 section (Step C renders; this step only
    // collects). Process-local by design: the trace answers "did
    // anything silently degrade since this process started".
    private val prefFallbackKeySet =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val subStoreDroppedCount =
        java.util.concurrent.atomic.AtomicInteger(0)

    private fun notePrefFallback(key: String) {
        prefFallbackKeySet.add(key)
    }

    /** B5 reading port for Step C: key names whose typed read
     *  fell back to its default in this process. */
    fun prefFallbackKeys(): Set<String> =
        synchronized(prefFallbackKeySet) { prefFallbackKeySet.toSet() }

    internal fun noteSubStoreDropped(n: Int) {
        if (n > 0) subStoreDroppedCount.addAndGet(n)
    }

    /** B5 reading port for Step C: total sub-store lines the
     *  loss-reporting parsers dropped in this process. */
    fun subStoreDroppedLines(): Int = subStoreDroppedCount.get()

    private fun pBool(key: String, def: Boolean): Boolean =
        try {
            prefs().getBoolean(key, def)
        } catch (e: Exception) {
            notePrefFallback(key); def
        }

    private fun pInt(key: String, def: Int): Int =
        try {
            prefs().getInt(key, def)
        } catch (e: Exception) {
            notePrefFallback(key); def
        }

    private fun pLong(key: String, def: Long): Long =
        try {
            prefs().getLong(key, def)
        } catch (e: Exception) {
            notePrefFallback(key); def
        }

    private fun pString(key: String, def: String): String =
        try {
            prefs().getString(key, def) ?: def
        } catch (e: Exception) {
            notePrefFallback(key); def
        }

    // ---- v1.5: per-host optimal thread memory (see HostMemory) ----

    // v5.1 audit round 36: the serialized table is parsed ONCE
    // per actual change, not on every read — hostThreadsCap is
    // read from the task card's composition (1 s tick), and the
    // old parse-per-read made every visible expanded card redo
    // the full parse every second. Writers all funnel through
    // saveHostEntries / clearHostMemory, which change the raw
    // string, so the cache can never serve a stale table.
    private val hostEntriesCache = HostMemoryParseCache()

    // v7.3 (B5): the cache re-parses only when the raw text
    // changes; accumulate its dropped-line count exactly once
    // per re-parse (tracked by the cache's version counter), so
    // the per-second UI reads of the same table never multiply
    // the trace.
    private var hostCacheCountedVersion = 0

    private fun hostEntries(): List<HostMemory.Entry> {
        val entries = hostEntriesCache.get(pString("hostThreads", ""))
        val v = hostEntriesCache.version
        if (v != hostCacheCountedVersion) {
            hostCacheCountedVersion = v
            noteSubStoreDropped(hostEntriesCache.lastDropped)
        }
        return entries
    }

    /** v5.1 audit round 36: bumps whenever the host table is
     *  re-parsed (i.e. actually changed) — UI callers key a
     *  `remember` on it so cached aggregations over the table
     *  refresh exactly when the table moved. Reading it syncs
     *  the cache first (a string compare against the pref; a
     *  re-parse only when the table really changed), so a
     *  change written by another thread is seen at once. */
    val hostMemoryVersion: Int
        get() {
            hostEntries()
            return hostEntriesCache.version
        }

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

    /** v4.3: the user picked [threads] for this URL's host in
     *  the diagnostics connection-count comparison — remember it
     *  as that host's starting count (see HostMemory.recordManual
     *  for the cap/fails rules). */
    fun applyHostThreads(url: String, threads: Int) {
        saveHostEntries(
            HostMemory.recordManual(
                hostEntries(), HostMemory.hostOf(url), threads, System.currentTimeMillis()
            )
        )
    }

    /** v4.3: forget every per-host learned optimum and rate-limit
     *  ceiling; the next download from each host re-probes. */
    fun clearHostMemory() {
        prefs().edit().putString("hostThreads", "").apply()
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

    // ---- v5.1 (Step B): memory tier, decided ONCE per process.
    // The pure decision is MemoryTiers.tierFor; this is the thin
    // Android-facing layer that reads the two platform signals.
    // Cached at first use (init warms it); a running task never
    // re-reads it — engines snapshot the budget at construction.
    @Volatile
    private var cachedMemoryTier: MemoryTier? = null

    fun memoryTier(): MemoryTier {
        cachedMemoryTier?.let { return it }
        synchronized(this) {
            cachedMemoryTier?.let { return it }
            val ctx = appContext
            val tier = if (ctx == null) {
                MemoryTier.STANDARD
            } else {
                runCatching {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                    MemoryTiers.tierFor(am.memoryClass, am.isLowRamDevice)
                }.getOrDefault(MemoryTier.STANDARD)
            }
            cachedMemoryTier = tier
            return tier
        }
    }

    /** The budget table for this process's memory tier. */
    fun memoryBudget(): MemoryTiers.Budget = MemoryTiers.budgetFor(memoryTier())

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        store = TaskStore(context.applicationContext)
        store.load()
        // v7.3 (B1): persist a self-healed load as the latest
        // tasks.json recovery event (banner + Step C diagnostics).
        store.lastLoadOutcome?.let { recordTasksRecovery(it) }
        // v5.8: creation funnel for sidecar checksums (see
        // maybeFetchSidecarChecksum / TaskStore.onTaskAdded).
        // v7.7 (Step B3): the same funnel also fires the
        // creation-moment preheat hook (see creationPreheatHook)
        // — the newcomer is judged for a warm host at the
        // instant it is created, not only on the drain's beat.
        store.onTaskAdded = { t ->
            maybeFetchSidecarChecksum(t)
            runCatching { creationPreheatHook?.invoke(t.id) }
        }
        syncProxyState()
        refreshScheduleAlarm()
        // v5.1: fix the memory tier for this process now that a
        // context exists (two cheap system reads, no IO).
        memoryTier()
    }

    /**
     * v3.5: keep the scheduled-start backup alarm pointed at
     * the earliest future slot (see ScheduleWake). Cheap and
     * idempotent; called wherever the queue's schedule shape
     * changes (init, enqueue, pause, cancel). Never fails
     * the caller — ScheduleWake.arm swallows everything.
     */
    fun refreshScheduleAlarm() {
        val ctx = appContext ?: return
        if (!::store.isInitialized) return
        ScheduleWake.arm(ctx, store.tasks)
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
            // v3.9 audit round 25: Android 15 forbids a
            // BOOT_COMPLETED receiver from starting a dataSync
            // foreground service at all, so on those devices the
            // boot receiver's kick silently fails (it is
            // runCatching-wrapped) and 开机恢复 would never fire.
            // The in-rules fallback: when the user's 开机恢复
            // switch is ON, the first process start after the
            // reboot — the user opening the app — performs the
            // same recovery and starts the service from a
            // foreground context, which is allowed. The tasks
            // were never lost either way; this only decides
            // whether they resume at boot or at first open.
            // Switch OFF keeps the standing rule untouched:
            // interrupted tasks stay paused for the user.
            runCatching {
                if (bootResume && recoverInterrupted() > 0) {
                    val ctx = appContext
                    if (ctx != null) {
                        ContextCompat.startForegroundService(
                            ctx, Intent(ctx, DownloadService::class.java)
                        )
                    }
                }
            }
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

    /**
     * v4.4 (audit round 29): has the system actually VALIDATED
     * the active network — i.e. traffic really flows? A
     * captive portal's network is "available" and has INTERNET
     * long before the user logs in; VALIDATED is the signal
     * that the login (or the portal's disappearance) completed.
     * The portal park keys its release on this, never on
     * availability alone.
     */
    fun hasValidatedNetwork(): Boolean {
        val cm = appContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * v5.2: the summary card's network status line, collected
     * fresh on the card's standing 1 s tick (see
     * SummaryNetStatus for the pure wording rules). ONE
     * getNetworkCapabilities read answers the whole network
     * segment — type AND validated — instead of re-querying
     * per segment: no active network, or one without the
     * INTERNET capability, is NONE (honest 无网络, never
     * misreported as 流量 the way a plain !isOnWifi() would).
     * A non-WiFi internet network counts as 流量, the same
     * binary the traffic guard has always used.
     *
     * Gate evidence: the held-task subject is a QUEUED task
     * whose scheduled time has arrived and which no outage /
     * portal park is holding (the drain's own eligibility,
     * QueueOrder) — running tasks are never gate-held. The
     * traffic segment's evidence is per-task: a held task that
     * TrafficGuard.shouldBlock judges blocked under exactly
     * the inputs the service hands the engine (its lambda also
     * treats an ACTIVE dual channel as the user's confirmation,
     * hence the !dualChannelActive term) — never the threshold
     * switch alone.
     */
    fun summaryNetLine(tasks: List<TaskRecord>): String =
        SummaryNetStatus.line(summaryNetSnapshot(tasks))

    /**
     * v5.5: the [SummaryNetStatus.Snapshot] behind
     * [summaryNetLine], exposed so the diagnostics 一键体检
     * judges the SAME evidence (see HealthCheck.networkVerdict)
     * instead of re-reading the network and gates its own way.
     * The collection below is exactly what summaryNetLine used
     * to do inline — one capabilities read, the drain's own
     * held-task eligibility, per-task traffic evidence.
     */
    fun summaryNetSnapshot(tasks: List<TaskRecord>): SummaryNetStatus.Snapshot {
        val ctx = appContext
        val caps = if (ctx == null) null else runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()
        val hasInternet = caps?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true
        val kind = when {
            caps == null || !hasInternet -> SummaryNetStatus.NetKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                SummaryNetStatus.NetKind.WIFI
            else -> SummaryNetStatus.NetKind.MOBILE
        }
        val now = System.currentTimeMillis()
        val held = tasks.filter { t ->
            t.status == TaskStatus.QUEUED && !t.netDeferred && !t.portalDeferred &&
                !(t.scheduledAt > 0 && t.scheduledAt > now)
        }
        val trafficBlocked = !dualChannelActive && held.any { t ->
            TrafficGuard.shouldBlock(
                onWifi = kind == SummaryNetStatus.NetKind.WIFI,
                totalSize = t.totalSize,
                thresholdMB = trafficThresholdMB,
                confirmed = t.mobileConfirmed,
            )
        }
        return SummaryNetStatus.Snapshot(
            network = kind,
            validated = caps?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true,
            dualActive = dualChannelActive,
            wifiOnlyOn = wifiOnly,
            chargeOnlyOn = chargeOnlyDownload,
            charging = isCharging(),
            hasQueuedDueTask = held.isNotEmpty(),
            trafficBlockedTask = trafficBlocked,
        )
    }

    /**
     * v4.4 (start-latency): fire-and-forget DNS pre-resolve for
     * a freshly enqueued task's host, on a short-lived daemon
     * thread — enqueue itself is called on the UI thread and
     * must never block on a lookup. The decision (proxy active
     * -> skip, http/https only) is Warmup.hostToPreResolve;
     * every failure is silent, because this is pure
     * optimisation: the engine's warmup and probe resolve for
     * real regardless. A task that ends up waiting (scheduled,
     * gated, queued behind others) may outlive the cached
     * answer — harmless: the worst case is the lookup happens
     * again later, exactly as before v4.4.
     */
    private fun preResolveHost(url: String) {
        val host = Warmup.hostToPreResolve(url, proxySettings().active) ?: return
        Thread {
            runCatching { java.net.InetAddress.getAllByName(host) }
        }.apply {
            isDaemon = true
            name = "dns-preresolve"
            start()
        }
    }

    /** Queue a task and kick the foreground service. Returns false if blocked. */
    fun enqueue(task: TaskRecord): Boolean {
        // v7.4 (audit round 59, C-1): the funnel itself now
        // guards the transition. A manual (re-)queue accepts
        // only QUEUED (legal re-entry — multi-select resume and
        // the wifi-only branch below depend on it), PAUSED and
        // FAILED. RUNNING already has an owner and DONE's only
        // re-queue is the daily path, which never comes through
        // here; both are refused AND counted (see
        // IllegalTransitions), never thrown into the UI — the
        // caller just sees "not queued", exactly like a block.
        if (!StateMachine.canEnqueue(task.status)) {
            IllegalTransitions.record(
                task.id, task.status,
                if (task.status == TaskStatus.DONE) StateEvent.RETRY else StateEvent.RESUME,
                "enqueue",
            )
            return false
        }
        // v7.4 (audit round 59, C-3): a manual (re-)queue also
        // retires wasRunning. It used to be cleared ONLY by
        // recoverInterrupted, so a task the user resumed and
        // then hand-paused again could be auto-requeued by a
        // later same-process recovery call — violating
        // Recovery's own "a user pause is an order" contract.
        task.wasRunning = false
        // v7.1 (audit round 56): this funnel head is THE stamp
        // point for every manual (re-)queue — both exits below
        // (the wifi-only branch and the normal one) inherit this
        // one assignment, so the task's continuous QUEUED wait
        // (QueueOrder aging) restarts here, beside the park-flag
        // clearing that makes it a fresh start.
        task.queuedAt = System.currentTimeMillis()
        task.autoRetriesUsed = 0
        task.netDeferred = false // v1.5: user action always wins over the outage park
        task.chargeDeferred = false // v3.9: same for the charge park
        // v4.4: same for the portal park + the raw-failure
        // verdicts — a manual （重新）排队 is a fresh start.
        task.portalDeferred = false
        task.portalHit = false
        task.netOutage = false
        // v4.4 (start-latency): warm the DNS cache for this
        // task's host NOW, in parallel with the service kick
        // below (see preResolveHost). Placed before the
        // wifi-only branch so both enqueue exits benefit.
        preResolveHost(task.url)
        // v2.8: a manual （重新）排队 also retires a stale pre-check
        // warning — the user has decided to try this link again.
        task.precheckDead = false
        // v7.6 (audit round 61, Step B-2): wake the link
        // pre-check loop — this funnel is the one cheap hook
        // every manual (re-)queue passes through, and the wake
        // keeps a fresh candidate's first pre-check prompt even
        // while the loop sits in its empty-pass backoff (see
        // LinkPrecheck.awaitInterval). A no-op notify when the
        // service (and its loop) is not running.
        LinkPrecheck.noteQueueChanged()
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
            refreshScheduleAlarm()
            return false
        }
        task.status = TaskStatus.QUEUED
        task.error = ""
        store.save()
        val ctx = appContext!!
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        refreshWidget()
        refreshScheduleAlarm()
        return true
    }

    /**
     * v3.7: batch "retry differently" (see RetryStrategy for the
     * pure decisions). Applies the chosen changes to every
     * FAILED task in [tasks], then re-queues each through the
     * normal enqueue (which clears the error and resets the
     * auto-retry budget). Downloaded progress is untouched.
     * [uaPreset] null = keep each task's UA; "" = the 默认 preset
     * (drop the task-level UA header); [uaLabel] is the preset's
     * display name for the note. Returns how many tasks were
     * actually retried.
     */
    fun retryWithStrategy(
        tasks: List<TaskRecord>,
        uaPreset: String?,
        uaLabel: String,
        singleThread: Boolean,
    ): Int {
        var n = 0
        val changes = mutableListOf<String>()
        if (uaPreset != null) changes += "浏览器标识换成「$uaLabel」"
        if (singleThread) changes += "改用单线程"
        for (t in tasks) {
            if (!RetryStrategy.eligible(t.status)) continue
            if (uaPreset != null) {
                val newHeaders = RetryStrategy.headersWithUa(t.headers, uaPreset)
                t.headers.clear()
                t.headers.putAll(newHeaders)
            }
            t.singleThreadOnly = RetryStrategy.singleThreadAfter(t.singleThreadOnly, singleThread)
            enqueue(t)
            // enqueue may replace the note (the wifi-only wait
            // message); stamp ours only over a note that is not
            // that more-urgent explanation.
            if (changes.isNotEmpty() && !t.note.startsWith("已开启")) {
                t.note = "已换个方式重试：" + changes.joinToString("、") +
                    "。已下载的进度保留，不会白下。"
            }
            n++
        }
        if (n > 0) store.saveNow()
        return n
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
            // v4.8: stamp the jump itself — inside its tier a
            // jumped task is picked before host rotation applies
            // (see QueueOrder). A no-op jump (changed == false)
            // sets nothing, exactly like the priority raise.
            task.jumpedAt = System.currentTimeMillis()
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

    /**
     * v6.6 (audit round 51): a snapshot of the engines running
     * right now — the service's default-network-lost fast-fail
     * walks it (see DownloadService's main network callback).
     * A copy, so the caller iterates without holding this
     * object's monitor while it cuts connections.
     */
    @Synchronized
    fun runningEngines(): List<DownloadEngine> = engines.values.toList()

    fun pauseTask(task: TaskRecord) {
        synchronized(this) { engines[task.id] }?.pause()
        // v7.4 (audit round 59): routed through the state-machine
        // guard — PAUSE is legal exactly from RUNNING / QUEUED,
        // the same pair this site always checked by hand. The
        // hand check stays as the outer gate so pausing an
        // already-paused / finished card stays the silent no-op
        // it always was, not a counted illegal transition.
        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
            StateMachine.commit(task, StateEvent.PAUSE, "pauseTask")
        }
        store.save()
        refreshWidget()
        // v3.5: a paused scheduled task must not keep the
        // backup alarm pointed at its (now moot) slot.
        refreshScheduleAlarm()
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
     * v3.9 audit round 25: Android 15 caps dataSync foreground
     * services at roughly 6 hours per 24-hour window in the
     * background; when the budget runs out the system gives the
     * service a moment (Service.onTimeout) to stop itself. The
     * graceful stop is a plain pause of everything in flight —
     * same targets as 全部暂停， engines halt at chunk
     * boundaries and all progress is already on disk — plus a
     * card note that tells the truth about WHO stopped it and
     * how to carry on, so the pause is never mistaken for a
     * crash or a finished download. Returns how many were paused.
     */
    fun pauseAllForSystemTimeout(): Int {
        val targets = BulkControl.pauseTargets(store.tasks)
        for (t in targets) pauseTask(t)
        var stamped = 0
        for (t in targets) {
            if (t.status == TaskStatus.PAUSED) {
                t.note = "安卓 15 对后台下载服务有每天约 6 小时的时长上限，" +
                    "刚才额度用满、系统把服务停了，任务已自动暂停，进度都在。" +
                    "点「继续」就能接着下；把 App 打开到前台，这个计时会重新算。"
                stamped++
            }
        }
        if (stamped > 0) store.saveNow()
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
            // v7.1: recovery IS a fresh queue entry — the aging
            // wait restarts here (the interrupted run's wait
            // ended when the process died with it).
            t.queuedAt = System.currentTimeMillis()
            // v7.4 (audit round 59): through the guard —
            // DEATH_RECOVERY is legal exactly from the PAUSED
            // status Recovery.interrupted filtered for.
            StateMachine.commit(t, StateEvent.DEATH_RECOVERY, "recoverInterrupted")
            t.error = ""
            t.note = "上次下载被系统中断（重启或进程被杀），已自动重新排队继续。"
        }
        store.saveNow()
        return hit.size
    }

}
