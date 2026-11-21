package com.tuxundown.android.core

/**
 * v2.4: the settings 诊断 (self-check) page's pure report
 * building. The Android-side gathering (storage, DNS, proxy
 * reachability, signing certificate, write benchmark) lives in
 * the screen; everything that turns facts into text lives here
 * so it is unit-testable and the wording stays in one place.
 *
 * The report is LOCAL ONLY: it is shown on screen and can be
 * copied by the user (e.g. to send to the developer when
 * reporting a problem). Nothing is uploaded anywhere — the app
 * has no telemetry, and this page does not change that.
 */
object Diagnostics {

    /** First 8 hex chars of a SHA-256 fingerprint, upper-cased
     *  for display; "" when the input carries no hex at all. */
    fun fingerprint8(sha256Hex: String): String =
        sha256Hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .take(8).uppercase()

    /** "共 N 个：下载中 a · 排队 b · 暂停 c · 已完成 d · 失败 e" —
     *  zero groups are named too (a missing group in a fault
     *  report is itself information). */
    fun taskCountsLine(tasks: List<TaskRecord>): String {
        fun n(s: TaskStatus) = tasks.count { it.status == s }
        return "共 ${tasks.size} 个：下载中 ${n(TaskStatus.RUNNING)} · " +
            "排队 ${n(TaskStatus.QUEUED)} · 暂停 ${n(TaskStatus.PAUSED)} · " +
            "已完成 ${n(TaskStatus.DONE)} · 失败 ${n(TaskStatus.FAILED)}"
    }

    /**
     * The write-benchmark line. [writeMs] is the pure write time,
     * [syncMs] the fsync on top; the two speeds are reported
     * separately because they answer different questions (page
     * cache throughput vs. what actually reached the flash).
     * A non-positive time reports honestly instead of dividing
     * by zero.
     */
    fun writeBenchLine(bytes: Long, writeMs: Long, syncMs: Long): String {
        if (bytes <= 0) return "写速粗测没跑成（临时目录写不进去）"
        val writePart = if (writeMs > 0) {
            "写入约 ${Format.speed(bytes * 1000 / writeMs)}"
        } else {
            "写入快到测不准"
        }
        val syncPart = if (syncMs > 0) {
            "落盘（fsync ${syncMs}ms 后）约 ${Format.speed(bytes * 1000 / (writeMs + syncMs))}"
        } else {
            "落盘耗时测不准"
        }
        return "写速粗测（${Format.size(bytes)}，App 临时目录）：$writePart；$syncPart"
    }

    /** Assemble the copyable plain-text report from labeled lines. */
    fun buildReport(versionLabel: String, lines: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        sb.append("TuxunDown 诊断报告\n")
        sb.append("版本：$versionLabel\n")
        for ((label, value) in lines) {
            sb.append(label).append("：").append(value).append('\n')
        }
        sb.append("（本报告只在本机生成，不含链接、文件名等个人信息；没有上传到任何地方。）")
        return sb.toString()
    }
}
