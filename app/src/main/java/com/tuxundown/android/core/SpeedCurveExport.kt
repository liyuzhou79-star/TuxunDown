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
 * v8.2: when the task carries a full-run series
 * ([TaskRecord.fullSpeedHistory]) with at least
 * [MIN_FULL_SAMPLES] points — the same count at which the
 * detail page draws its full-run chart — the export switches
 * to that series instead: every row's speed is a recorded
 * sample itself, the time axis advances by the series' real
 * interval ([TaskRecord.fullSpeedIntervalSec]), and NO
 * cumulative column is emitted, because a cumulative figure
 * could only come from integrating the samples — exactly the
 * reconstruction this branch exists to avoid. The header says
 * the real interval and, once it has grown past the base
 * 5 seconds, discloses the decimation in [FullSpeedHistory]'s
 * own terms: adjacent pairs were averaged into single points
 * (a merged point IS the mean of the two samples it replaced;
 * points recorded after a decimation are still the real speed
 * at their — wider — spacing). Tasks without a full-run series
 * keep the window export above, byte for byte.
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

    /** v8.2: a full-run series needs at least this many points
     *  to be exported as a curve — the same threshold the
     *  detail page's full-run chart uses to appear at all. */
    const val MIN_FULL_SAMPLES = 2

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
     * v8.2: CSV text choosing between the two series. When
     * [fullPoints] reaches [MIN_FULL_SAMPLES], the full-run
     * curve is exported (see [buildFullCsv]); otherwise the
     * 90-second window export runs exactly as before, so a
     * task without full-run samples produces byte-identical
     * output to the pre-v8.2 formatter.
     */
    fun buildCsv(
        fileName: String,
        samples: List<Long>,
        fullPoints: List<Long>,
        fullIntervalSec: Int,
        nowMs: Long,
    ): String? =
        if (fullPoints.size >= MIN_FULL_SAMPLES) {
            buildFullCsv(fileName, fullPoints, fullIntervalSec, nowMs)
        } else {
            buildCsv(fileName, samples, nowMs)
        }

    /**
     * v8.2: the full-run CSV. Rows are the recorded samples
     * themselves — elapsed seconds are the point index times
     * the series' sanitised interval, and there is deliberately
     * no cumulative column (it could only be reconstructed by
     * integration, which this branch never does).
     */
    private fun buildFullCsv(
        fileName: String,
        fullPoints: List<Long>,
        fullIntervalSec: Int,
        nowMs: Long,
    ): String {
        val interval = FullSpeedHistory.sanitizeInterval(fullIntervalSec)
        val sb = StringBuilder()
        sb.append("# TuxunDown 速度曲线导出\n")
        sb.append("# 生成时间：").append(ExportStamp.format(nowMs)).append('\n')
        sb.append("# 文件：").append(commentName(fileName)).append('\n')
        sb.append("# 样本：共 ").append(fullPoints.size)
            .append(" 个，每 ").append(interval)
            .append(" 秒一个（全程采样，记录的是整个下载过程的速度）\n")
        sb.append("# 时长：共 ")
            .append(FullSpeedHistory.spanSeconds(fullPoints.size, interval))
            .append(" 秒（样本数 × 每样本 ").append(interval).append(" 秒）\n")
        sb.append(
            "# 说明：速度值就是下载时实际记录下来的采样点本身，没有做积分还原，" +
                "也不是推算出来的；秒数从第一个全程采样点算起，每行的时间是采样点序号乘以采样间隔。\n",
        )
        if (interval > FullSpeedHistory.BASE_INTERVAL_SEC) {
            sb.append(
                "# 抽稀：下载时间较长、采样点达到上限后，早期采样已经过抽稀合并——" +
                    "相邻两个采样点取平均合成一个点，采样间隔由最初的 " +
                    "${FullSpeedHistory.BASE_INTERVAL_SEC} 秒变为现在的 $interval 秒；" +
                    "合并出来的点是被合并的两个采样点的平均值，合并之后新记下的点仍是当时的实际速度。\n",
            )
        } else {
            sb.append("# 抽稀：暂未发生抽稀合并，采样间隔还是最初的 ")
                .append(FullSpeedHistory.BASE_INTERVAL_SEC).append(" 秒。\n")
        }
        sb.append("elapsed_sec,speed_bps\n")
        for ((index, rawBps) in fullPoints.withIndex()) {
            // Same clamp as the window branch: a negative sample
            // is impossible from the sampler, never export one.
            sb.append(index.toLong() * interval).append(',')
                .append(rawBps.coerceAtLeast(0)).append('\n')
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
     *  generation stamp (see the file-name overload). v8.2: the
     *  full-run series rides along and wins when it has enough
     *  points (see the five-argument overload). */
    fun buildCsv(task: TaskRecord, nowMs: Long): String? {
        val samples = synchronized(task.speedHistory) { task.speedHistory.toList() }
        val fullPoints = synchronized(task.fullSpeedHistory) { task.fullSpeedHistory.toList() }
        return buildCsv(
            task.savedName.ifBlank { task.fileName },
            samples,
            fullPoints,
            task.fullSpeedIntervalSec,
            nowMs,
        )
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
