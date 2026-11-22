package com.tuxundown.android.core

/**
 * Multi-completion notification merging (v2.5).
 *
 * When a batch of tasks finishes together, one completion
 * notification per file would buzz and stack up a wall of
 * near-identical cards. Instead, completions landing inside
 * a short window merge into ONE summary notification: the
 * first completion posts immediately (with its own 打开 /
 * 分享 actions); if more land inside the window, the same
 * notification is REPLACED by the summary. Once the window
 * has been quiet for [WINDOW_MS], the next completion
 * starts a fresh single notification. Pure — unit-tested;
 * the service only keeps the list and renders the verdict.
 */
object CompletionMerge {

    const val WINDOW_MS = 15_000L

    data class Done(
        val name: String,
        val uri: String,
        val atMs: Long,
        /** Finished file size in bytes (v4.1); -1 = unknown.
         *  Only used for the summary's total — see
         *  [totalSizeBytes] for the honesty rule. */
        val sizeBytes: Long = -1,
    )

    /** Append [item], dropping entries that fell out of the
     *  window. Order is completion order. */
    fun add(list: List<Done>, item: Done, nowMs: Long): List<Done> =
        list.filter { nowMs - it.atMs <= WINDOW_MS } + item

    fun isMerged(list: List<Done>): Boolean = list.size >= 2

    fun title(list: List<Done>): String = "${list.size} 个文件下载完成"

    /** One file name per line, for the expanded summary. */
    fun lines(list: List<Done>): String = list.joinToString("\n") { it.name }

    /**
     * v4.1: the single completion's detail line — finished
     * size + whole-run average speed, e.g. "12.3 MB · 平均
     * 4.5 MB/秒". Each part appears only when its number is
     * actually known (size > 0, speed > 0); both unknown
     * yields "" and the caller posts the plain notification
     * it always did. A number that could mislead is worse
     * than no number.
     */
    fun detailLine(sizeBytes: Long, avgBps: Long): String {
        val parts = ArrayList<String>(2)
        if (sizeBytes > 0) parts.add(Format.size(sizeBytes))
        if (avgBps > 0) parts.add("平均 ${Format.speed(avgBps)}")
        return parts.joinToString(" · ")
    }

    /**
     * v4.1: total size of a merged batch, or -1 when it
     * cannot be stated honestly. The total is only offered
     * when EVERY entry's size is known — a partial sum
     * would understate the batch and read as the real
     * total, so one unknown size suppresses it entirely.
     */
    fun totalSizeBytes(list: List<Done>): Long {
        if (list.isEmpty()) return -1
        var sum = 0L
        for (d in list) {
            if (d.sizeBytes <= 0) return -1
            sum += d.sizeBytes
        }
        return sum
    }

    /** Expanded summary body: one name per line, plus a
     *  "共 X" total line when [totalSizeBytes] has one. */
    fun summaryText(list: List<Done>): String {
        val total = totalSizeBytes(list)
        return if (total > 0) {
            lines(list) + "\n共 ${Format.size(total)}"
        } else {
            lines(list)
        }
    }
}
