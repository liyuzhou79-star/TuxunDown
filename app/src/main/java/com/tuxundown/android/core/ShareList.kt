package com.tuxundown.android.core

/**
 * v2.9: share the queue as a plain-text link list — the
 * counterpart of the two import doors the app already has
 * (batch paste in the add dialog, .txt file import). The list
 * is deliberately NOTHING but a header, a how-to-import line
 * and then per task an optional file-name line followed by
 * the bare link line: pasted back into the add dialog (or
 * saved as .txt and imported), the importer picks the links
 * out and ignores the name/header lines, so a list shared to
 * a friend — or to your future self — rebuilds the queue in
 * one paste. Only the primary URL travels; mirrors, headers
 * and progress do not (they are this device's business, and
 * headers can carry a login Cookie that must never be shared
 * — the same rule the backup export follows).
 *
 * Pure — unit-tested.
 */
object ShareList {

    /** Everything that is not finished yet: queued, running,
     *  paused and failed tasks are all worth re-creating. */
    fun unfinished(tasks: List<TaskRecord>): List<TaskRecord> =
        tasks.filter { it.status != TaskStatus.DONE }

    /** The two fixed lines every list starts with — shared
     *  verbatim with the sniff-session export (v3.2, SniffExport)
     *  so both doors produce byte-identical lists. */
    fun headerLine(count: Int): String = "TuxunDown 链接清单（共 $count 个）"

    const val HOW_TO_LINE: String =
        "导入方法：在 TuxunDown 里点「新建下载」，把这份清单整段粘进去" +
            "（批量粘贴），或者存成 .txt 文件再用「从文件导入」；" +
            "文件名行会被自动跳过，只认链接行。"

    fun build(tasks: List<TaskRecord>): String {
        val sb = StringBuilder()
        sb.append(headerLine(tasks.size)).append("\n")
        sb.append(HOW_TO_LINE).append("\n")
        for (t in tasks) {
            sb.append("\n")
            val name = t.fileName.ifBlank { t.savedName }
            if (name.isNotBlank()) sb.append(name).append("\n")
            sb.append(t.url).append("\n")
        }
        return sb.toString()
    }
}
