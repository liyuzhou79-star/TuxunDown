package com.tuxundown.android.core

/**
 * Server profile (v4.6, audit round 31 step C) — the pure
 * aggregation behind the task card's 「服务器画像」 section.
 *
 * Every fact here comes from records the app ALREADY keeps or
 * samples in the normal course of a download (the probe's
 * validators, the segmented sampler's worker count, the
 * per-attempt byte accounting in DownloadEngine.downloadActive,
 * HostMemory's learned rate-limit ceiling). The profile NEVER
 * probes, fetches or guesses: a row whose input is missing is
 * ABSENT from the result, never rendered with a placeholder.
 *
 * Pure (task + the host's remembered cap in, ordered rows out)
 * so every absence rule is unit-tested. The caller resolves the
 * cap for the host of task.url ONLY (see
 * DownloadCenter.hostThreadsCap) — a mirror's host never leaks
 * into the primary's ceiling, and per-source contribution rows
 * are grouped by HOST (never the full mirror URL: signed mirror
 * URLs carry tokens in their query, the TaskDiagnostics
 * redaction discipline applies here too).
 */
object ServerProfile {

    data class Row(val label: String, val value: String)

    /**
     * The resume story, told per protocol / per probe outcome —
     * [TaskRecord.singleThreadOnly] has three writers with
     * different meanings and the profile must not conflate them:
     * - FTP (engine sets the flag at the FTP entry): FTP has no
     *   Range chunking, but it DOES resume via REST — calling
     *   that "no resume" would be a lie;
     * - HTTP probe 200 / unusable 206 range: the server gave no
     *   usable Range, so the run is one whole-file stream;
     * - otherwise (a segmented run happened or can happen):
     *   Range chunking works.
     * Null = no honest claim yet (the engine never engaged, or
     * an HLS task — segments are not byte-range resume, the row
     * simply does not apply).
     */
    fun resumeText(task: TaskRecord): String? {
        // v6.7.0 Step B guard: an adopted record (url="") has no
        // server at all — claiming any resume story for it would
        // be fabricated, so the row stays absent.
        if (task.url.isBlank()) return null
        if (task.isHls) return null
        val engaged = task.startedAt > 0 || task.totalSize > 0
        if (!engaged) return null
        return when {
            FtpClient.isFtpUrl(task.url) ->
                "支持（FTP 单条连接，靠 REST 从断点接着下）"
            task.singleThreadOnly ->
                "没有可用的 Range 分片（这次是单条连接整文件下载）"
            else ->
                "支持（Range 分片下载，断了能从分片进度接着下）"
        }
    }

    /** peakBps / peakConns, labelled by the caller as a
     *  peak-period average. Null when either input is missing —
     *  dividing by a zero peak would fabricate a number. */
    fun peakPerConnectionBps(peakBps: Long, peakConns: Int): Long? =
        if (peakBps > 0 && peakConns > 0) peakBps / peakConns else null

    /**
     * Per-source contribution, grouped by host: (host, bytes,
     * percent) triples ordered by bytes descending (host name
     * breaks ties, so the order is deterministic). Percent is
     * each host's share of the RECORDED bytes, rounded to the
     * nearest integer; because every share is rounded
     * independently the percents can sum to 99–101 — the card
     * always shows the byte count next to the percent, and the
     * bytes are the exact figure. Hosts with no recorded bytes
     * are absent. The primary URL is a source like any other
     * (its bytes are recorded under task.url).
     */
    fun sourceShares(task: TaskRecord): List<Triple<String, Long, Int>> {
        if (task.sources.isEmpty()) return emptyList()
        val recorded = synchronized(task.sourceBytes) { task.sourceBytes.toMap() }
        if (recorded.isEmpty()) return emptyList()
        val byHost = HashMap<String, Long>()
        for ((url, bytes) in recorded) {
            if (bytes <= 0) continue
            val host = HostMemory.hostOf(url)
            if (host.isBlank()) continue
            byHost[host] = (byHost[host] ?: 0) + bytes
        }
        val total = byHost.values.sum()
        if (total <= 0) return emptyList()
        return byHost.entries
            .map { (host, bytes) ->
                Triple(host, bytes, Math.round(bytes * 100.0 / total).toInt())
            }
            .sortedWith(compareByDescending<Triple<String, Long, Int>> { it.second }.thenBy { it.first })
    }

    /**
     * The full ordered row list for the card. [hostCap] is
     * HostMemory.capOf for the host of task.url (0 = no ceiling
     * remembered / no entry / expired — the row is absent).
     */
    fun rows(task: TaskRecord, hostCap: Int): List<Row> {
        val out = ArrayList<Row>()
        resumeText(task)?.let { out.add(Row("断点续传：", it)) }
        if (task.totalSize > 0) {
            out.add(Row("服务器声明大小：", Format.size(task.totalSize)))
        }
        if (task.etag.isNotBlank()) out.add(Row("ETag：", task.etag))
        if (task.lastModified.isNotBlank()) {
            out.add(Row("最后修改：", task.lastModified))
        }
        if (task.peakConns > 0) {
            out.add(Row("实际峰值连接：", "${task.peakConns} 条"))
        }
        peakPerConnectionBps(task.peakBps, task.peakConns)?.let {
            out.add(Row("峰值时平均每条连接：", Format.speed(it)))
        }
        if (hostCap > 0) {
            out.add(Row("已知的限流上限：", "$hostCap 条连接（这个站点之前多连接时限流过）"))
        }
        for ((host, bytes, pct) in sourceShares(task)) {
            out.add(Row("来源贡献 · $host：", "${Format.size(bytes)}（$pct%）"))
        }
        return out
    }
}
