package com.tuxundown.android.core

/**
 * v5.5: the diagnostics page's 一键体检 (one-tap health check)
 * verdicts — pure aggregation ONLY. Every input is a fact some
 * existing self-check or store already produced (the signing
 * fingerprint the diagnostics page already reads, [MemoryTiers],
 * [StartupTiming]'s stored value, [PublicStorage]'s free-space
 * read, [SummaryNetStatus]' snapshot, the diagnostics page's own
 * proxy socket probe and DNS lookups, the [HostMemory] /
 * [HostStats] tables, and [TaskStore] + [AbnormalFilter]); this
 * file invents NO new probe and NO new measurement. It only
 * decides, per item: the three-level verdict （正常 / 注意 /
 * 异常） and the plain-language value text shown next to it.
 *
 * Threshold discipline — every number below is either aligned
 * with an existing verdict in the codebase or, where no prior
 * verdict exists, stated here as this page's own and pinned by
 * HealthCheckTest:
 * - free space: < 1 GB 异常， < 5 GB 注意， >= 5 GB 正常. An
 *   unreadable volume (a custom SAF folder — [SpaceCheck]'s
 *   standing rule is that an unmeasurable volume NEVER blocks)
 *   is 注意 with an honest "读不到", never 异常.
 * - cold start: no prior threshold exists anywhere in the app
 *   (the diagnostics page only ever displayed the raw number),
 *   so this page's own bands are: <= 1500 ms 正常，
 *   <= 3000 ms 注意， > 3000 ms 异常； no stored value at all
 *   (first install / never measured) is 注意 — it is missing
 *   information, not a fault.
 * - memory tier: LOW is 注意 (the budgets are already tightened
 *   automatically — the user should know, nothing is broken);
 *   STANDARD / HIGH are 正常. Mirrors [MemoryTiers.tierLabel]'s
 *   own framing.
 * - network + gates: no network at all is 异常； a gate
 *   currently holding a queued task ([SummaryNetStatus.gateOf])
 *   is 注意 — the gate is doing exactly what the user asked,
 *   but it IS what is standing in the way right now.
 * - abnormal tasks: any abnormal task （异常 tab's own
 *   [AbnormalFilter] definition) is 注意， never 异常 — a failed
 *   or parked task needs the user's attention, it is not proof
 *   the app itself is broken.
 *
 * The copyable text ([buildCopyText]) follows the
 * [TaskDiagnostics] redaction discipline: every value passes
 * through [sanitizeForCopy] before it can leave the page, so a
 * URL's query string, userinfo, a proxy credential, a Keystore
 * ciphertext blob or a Basic-auth value can never ride along
 * even if a future caller hands one in by mistake.
 */
object HealthCheck {

    /** Three verdict levels; the UI shows BOTH a coloured dot
     *  and this word — colour alone never carries the meaning. */
    enum class Level(val label: String) {
        OK("正常"),
        WARN("注意"),
        BAD("异常"),
    }

    /** The check items, in page order. PROXY is only ever
     *  listed when a proxy is configured AND active (see
     *  [itemsFor]) — the page never probes a proxy that was
     *  never configured. */
    enum class Item(val label: String) {
        VERSION("版本与签名"),
        MEMORY("内存档位"),
        STARTUP("上次启动耗时"),
        SPACE("下载空间"),
        NETWORK("网络与开关门"),
        PROXY("代理连通"),
        DNS("域名解析"),
        LEARNING("学习数据"),
        TASKS("任务状态"),
    }

    data class Result(val item: Item, val level: Level, val value: String)

    /** One DNS lookup's outcome, in the diagnostics page's own
     *  shape (same two hosts, same timeout — gathered there,
     *  judged here). */
    data class DnsProbe(val host: String, val ok: Boolean, val detail: String)

    /** The items to show/run for a given proxy state: PROXY
     *  appears only when a proxy is configured and active. */
    fun itemsFor(proxyActive: Boolean): List<Item> =
        Item.values().filter { it != Item.PROXY || proxyActive }

    // ---------- per-item verdicts ----------

    /** Version + signing fingerprint. [fingerprint8] is
     *  [Diagnostics.fingerprint8]'s output (first 8 hex chars) —
     *  the same口径 the diagnostics page and the About page use. */
    fun versionVerdict(versionLabel: String, fingerprint8: String): Result {
        val v = versionLabel.trim()
        val fp = fingerprint8.trim()
        return when {
            v.isBlank() -> Result(Item.VERSION, Level.BAD, "版本号读不到")
            fp.isBlank() -> Result(
                Item.VERSION, Level.BAD, "版本 v$v，签名指纹读不到",
            )
            else -> Result(
                Item.VERSION, Level.OK, "版本 v$v，签名指纹 $fp…",
            )
        }
    }

    /** Memory tier, judged by [MemoryTiers]' own framing. */
    fun memoryVerdict(tier: MemoryTier): Result = when (tier) {
        MemoryTier.LOW -> Result(
            Item.MEMORY, Level.WARN, MemoryTiers.tierLabel(tier),
        )
        MemoryTier.STANDARD, MemoryTier.HIGH -> Result(
            Item.MEMORY, Level.OK, MemoryTiers.tierLabel(tier),
        )
    }

    /** This page's own cold-start bands (see the file doc). */
    const val STARTUP_OK_MAX_MS = 1500L
    const val STARTUP_WARN_MAX_MS = 3000L

    /** Last cold start, from [StartupTiming]'s stored value
     *  (0 = never measured). The value text reuses
     *  [StartupTiming.displayText]'s wording. */
    fun startupVerdict(storedMs: Long): Result {
        val text = StartupTiming.displayText(storedMs)
            ?: return Result(
                Item.STARTUP, Level.WARN,
                "还没有记录（刚装好、还没冷启动过时是这样）",
            )
        val level = when {
            storedMs <= STARTUP_OK_MAX_MS -> Level.OK
            storedMs <= STARTUP_WARN_MAX_MS -> Level.WARN
            else -> Level.BAD
        }
        return Result(Item.STARTUP, level, "上次冷启动$text")
    }

    /** This page's free-space bands (see the file doc). */
    const val SPACE_BAD_BELOW_BYTES = 1024L * 1024 * 1024
    const val SPACE_WARN_BELOW_BYTES = 5L * 1024 * 1024 * 1024

    /**
     * Download-dir free space, [PublicStorage.availableBytes]'
     *  read: -1 = unmeasurable (a custom SAF folder gives no
     *  honest number — [SpaceCheck]'s Unknown rule), reported
     *  as 注意 with the reason, never as a made-up figure.
     */
    fun spaceVerdict(availableBytes: Long, customDir: Boolean): Result {
        if (availableBytes < 0) {
            return Result(
                Item.SPACE, Level.WARN,
                if (customDir) {
                    "读不到（用的是自定义文件夹，它的可用空间系统查不到）"
                } else {
                    "读不到（可用空间查不到）"
                },
            )
        }
        val level = when {
            availableBytes < SPACE_BAD_BELOW_BYTES -> Level.BAD
            availableBytes < SPACE_WARN_BELOW_BYTES -> Level.WARN
            else -> Level.OK
        }
        return Result(
            Item.SPACE, level, "下载目录可用 ${Format.size(availableBytes)}",
        )
    }

    /**
     * Current network + gate state, from [SummaryNetStatus]'s
     *  snapshot — the value text IS that line, so the体检 and
     *  the home summary card can never tell two stories.
     */
    fun networkVerdict(s: SummaryNetStatus.Snapshot): Result {
        val line = SummaryNetStatus.line(s)
        val level = when {
            s.network == SummaryNetStatus.NetKind.NONE -> Level.BAD
            SummaryNetStatus.gateOf(s) != null -> Level.WARN
            else -> Level.OK
        }
        return Result(Item.NETWORK, level, line)
    }

    /**
     * Proxy reachability — null when no proxy is active, so the
     *  item simply does not exist (the page must never connect
     *  to a proxy that was never configured). [label] is
     *  [ProxySettings.label] (host:port only — never a
     *  credential; the password never reaches this function).
     */
    fun proxyVerdict(active: Boolean, reachable: Boolean, label: String): Result? {
        if (!active) return null
        return if (reachable) {
            Result(Item.PROXY, Level.OK, "代理服务器 $label 能连上")
        } else {
            Result(
                Item.PROXY, Level.BAD,
                "代理服务器 $label 连不上，下载会报这个错，不会偷偷直连",
            )
        }
    }

    /** DNS: every probe resolved 正常； some 注意； none 异常. */
    fun dnsVerdict(probes: List<DnsProbe>): Result {
        if (probes.isEmpty()) {
            return Result(Item.DNS, Level.BAD, "没有可解析的目标")
        }
        val okCount = probes.count { it.ok }
        val level = when {
            okCount == probes.size -> Level.OK
            okCount > 0 -> Level.WARN
            else -> Level.BAD
        }
        val detail = probes.joinToString("；") { it.detail }
        return Result(Item.DNS, level, detail)
    }

    /**
     * Learning-data entry counts ([HostMemory] rows +
     *  [HostStats] rows, the existing parse results). Having
     *  none is a normal state (fresh install, or the user
     *  cleared them), so this item is 正常 either way — it is
     *  reported for information, not judged as a fault. A
     *  negative count cannot come from a real parse; it reads
     *  as 注意 rather than being displayed as-is.
     */
    fun learningVerdict(hostMemoryCount: Int, hostStatsCount: Int): Result {
        if (hostMemoryCount < 0 || hostStatsCount < 0) {
            return Result(Item.LEARNING, Level.WARN, "学习数据条数读不准")
        }
        return Result(
            Item.LEARNING, Level.OK,
            "站点记忆 $hostMemoryCount 条 · 下载统计 $hostStatsCount 条",
        )
    }

    /**
     * Task total + abnormal count ([AbnormalFilter]'s own
     *  definition, the same number the 异常 tab shows). Any
     *  abnormal task is 注意 (see the file doc), never 异常.
     */
    fun tasksVerdict(total: Int, abnormal: Int): Result {
        val t = total.coerceAtLeast(0)
        val a = abnormal.coerceIn(0, t)
        return if (a > 0) {
            Result(Item.TASKS, Level.WARN, "共 $t 个任务，其中 $a 个异常")
        } else {
            Result(Item.TASKS, Level.OK, "共 $t 个任务，没有异常")
        }
    }

    // ---------- copyable text (redacted) ----------

    private val urlToken = Regex("""https?://\S+|ftp://\S+""")
    private val secretPair =
        Regex("""(?i)(password|passwd|pwd|token|secret|authorization)\s*[:=]\s*\S+""")
    private val basicAuth = Regex("""(?i)basic\s+[A-Za-z0-9+/=]{8,}""")
    private val ciphertext = Regex("""v1:[A-Za-z0-9+/=]{8,}""")

    /**
     * One value made safe to copy out, following
     *  [TaskDiagnostics]' discipline: a URL collapses to
     *  scheme + host + path (userinfo dropped, query replaced
     *  by a marker), key=value secrets lose their value, a
     *  Basic-auth blob and a "v1:" Keystore ciphertext are
     *  removed outright. Pure and pinned by tests against
     *  secret-shaped inputs.
     */
    fun sanitizeForCopy(value: String): String {
        var s = value
        s = ciphertext.replace(s) { "（密文已隐藏）" }
        s = basicAuth.replace(s) { "（凭据已隐藏）" }
        s = secretPair.replace(s) { m ->
            m.value.substringBefore(':').substringBefore('=') + "：（已隐藏）"
        }
        s = urlToken.replace(s) { m -> TaskDiagnostics.redactedUrl(m.value) }
        return s
    }

    /**
     * The whole体检 result as plain text, one line per item:
     * 「[正常] 名称：结论」。Every value passes through
     * [sanitizeForCopy] first (see above).
     */
    fun buildCopyText(versionLabel: String, results: List<Result>): String {
        val sb = StringBuilder()
        sb.append("TuxunDown 一键体检结果\n")
        sb.append("版本：v").append(versionLabel.trim().removePrefix("v")).append('\n')
        for (r in results) {
            sb.append('[').append(r.level.label).append("] ")
                .append(r.item.label).append("：")
                .append(sanitizeForCopy(r.value)).append('\n')
        }
        sb.append("（本结果只在本机生成，没有上传到任何地方。）")
        return sb.toString()
    }
}
