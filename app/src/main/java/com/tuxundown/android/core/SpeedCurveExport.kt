package com.tuxundown.android.core

/**
 * v4.5: export a finished task's speed curve as CSV.
 *
 * The ONLY curve data that survives a task is
 * [TaskRecord.speedHistory]: one bytes-per-second sample per
 * second, persisted since v1.4 (the engine keeps at most the
 * newest [MAX_RETAINED_SAMPLES] samples — a rolling window,
 * see DownloadEngine.recordSpeed / TaskStore). This formatter
 * turns those samples into CSV text:
 *
 *   elapsed_sec,cumulative_bytes,speed_bps
 *
 * HONESTY RULES (pinned by SpeedCurveExportTest):
 * - Cumulative bytes were NEVER recorded separately. They are
 *   RECONSTRUCTED here by integrating the 1-second samples
 *   (cumulative += bps x 1 s), and the CSV says so in its
 *   header comments — the figure is an approximation of the
 *   bytes moved inside the retained window, not an exact
 *   counter and not the file size.
 * - elapsed_sec counts from the FIRST RETAINED sample, not
 *   from the task's start: for a download longer than the
 *   retained window, second 0 is simply where the kept curve
 *   begins.
 * - Only the file NAME and the curve numbers go in. The task's
 *   link, cookies and headers NEVER enter this file (the link
 *   can carry secret query parameters — the same redaction
 *   rule as TaskDiagnostics). [buildCsv] for a task reads
 *   nothing but its display name and its samples.
 *
 * Pure — unit-tested. Writing the text to the public Download
 * folder is PublicStorage.saveTextFile's job, exactly like the
 * sniff-list export (SniffExport).
 */
object SpeedCurveExport {

    /** One sample per second — the engine sampler's period. */
    const val SAMPLE_SECONDS = 1L

    /** The record keeps at most this many newest samples. */
    const val MAX_RETAINED_SAMPLES = 90

    /**
     * CSV text for [samples] of the file called [fileName], or
     * null when there are no samples — an empty curve is NOT
     * exportable and must never become an empty file (the UI
     * tells the user plainly instead).
     */
    fun buildCsv(fileName: String, samples: List<Long>): String? =
        buildCsv(fileName, samples, System.currentTimeMillis())

    /**
     * v6.4: same CSV with a generation-time header line. The
     * stamp is [ExportStamp]'s caliber (local time + explicit
     * zone offset at that instant), passed in as [nowMs] so
     * this formatter stays pure and testable.
     */
    fun buildCsv(fileName: String, samples: List<Long>, nowMs: Long): String? {
        if (samples.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("# TuxunDown 速度曲线导出\n")
        sb.append("# 生成时间：").append(ExportStamp.format(nowMs)).append('\n')
        sb.append("# 文件：").append(commentName(fileName)).append('\n')
        sb.append("# 样本：共 ").append(samples.size)
            .append(" 个，每 ").append(SAMPLE_SECONDS)
            .append(" 秒一个（记录只保留最近 ").append(MAX_RETAINED_SAMPLES)
            .append(" 秒的样本，这里是保留下来的全部）\n")
        sb.append("# 时长：共 ").append(samples.size * SAMPLE_SECONDS)
            .append(" 秒（样本数 × 每样本 ").append(SAMPLE_SECONDS).append(" 秒）\n")
        sb.append(
            "# 说明：累计字节不是单独记录的，是按每秒速度样本还原的" +
                "（累计 += 这一秒的速度 × 1 秒），只能算近似值，不是精确计数，" +
                "也不是文件总大小；秒数从保留下来的第一个样本算起。\n",
        )
        sb.append("elapsed_sec,cumulative_bytes,speed_bps\n")
        var cumulative = 0L
        for ((index, rawBps) in samples.withIndex()) {
            // A negative sample is impossible from the sampler;
            // clamp rather than let the cumulative run backwards.
            val bps = rawBps.coerceAtLeast(0)
            cumulative = saturatingAdd(cumulative, bps * SAMPLE_SECONDS)
            sb.append(index * SAMPLE_SECONDS).append(',')
                .append(cumulative).append(',')
                .append(bps).append('\n')
        }
        return sb.toString()
    }

    /**
     * CSV text for [task]'s retained curve, or null when it has
     * none. Reads ONLY the display name and the samples — never
     * the link or the headers (see the redaction rule above).
     */
    fun buildCsv(task: TaskRecord): String? =
        buildCsv(task, System.currentTimeMillis())

    /** v6.4: [buildCsv] for [task] with an explicit [nowMs]
     *  generation stamp (see the file-name overload). */
    fun buildCsv(task: TaskRecord, nowMs: Long): String? {
        val samples = synchronized(task.speedHistory) { task.speedHistory.toList() }
        return buildCsv(task.savedName.ifBlank { task.fileName }, samples, nowMs)
    }

    /**
     * The export's file name: "<原文件名>-speed.csv", with the
     * base name passed through the same path-separator
     * sanitising download names get (DownloadEngine
     * .sanitizeFileName). Collisions in the Download folder are
     * PublicStorage.saveTextFile's problem (it picks the first
     * free "name (n).ext", never overwrites).
     */
    fun exportFileName(fileName: String): String =
        DownloadEngine.sanitizeFileName(fileName) + "-speed.csv"

    /** Long-saturating add: a pathological sample list must
     *  saturate at Long.MAX_VALUE, never wrap negative. */
    private fun saturatingAdd(a: Long, b: Long): Long =
        if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    /** A file name goes into a '#' comment line: line breaks and
     *  control characters would break the CSV's line structure,
     *  so they are flattened to spaces. */
    private fun commentName(fileName: String): String {
        val cleaned = fileName.map { c -> if (c.code < 0x20 || c.code == 0x7F) ' ' else c }
            .joinToString("").trim()
        return cleaned.ifBlank { "download" }
    }
}
