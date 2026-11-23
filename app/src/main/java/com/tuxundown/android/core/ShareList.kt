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

    /**
     * v7.2 (audit round 57): the one reminder line added when —
     * and only when — a link in the list carries a query string
     * or userinfo. The links themselves stay whole (re-import
     * needs them, that is the list's whole point), but until now
     * nothing told the user a signed link's parameter can BE a
     * login credential. The line carries no scheme, so both
     * import doors skip it like the other header lines, and the
     * list format is otherwise unchanged.
     */
    const val CREDENTIAL_HINT_LINE: String =
        "提醒：这份清单里有链接带着参数或账号信息，这类参数可能就是登录凭据，" +
            "转发给别人前请先确认。"

    /** The hint line for [urls], or null when none of them
     *  carries a query string or userinfo (see [Redact]). */
    fun credentialHint(urls: List<String>): String? =
        if (urls.any { Redact.urlCarriesCredentials(it) }) {
            CREDENTIAL_HINT_LINE
        } else {
            null
        }

    fun build(tasks: List<TaskRecord>): String {
        val sb = StringBuilder()
        sb.append(headerLine(tasks.size)).append("\n")
        sb.append(HOW_TO_LINE).append("\n")
        credentialHint(tasks.map { it.url })?.let { sb.append(it).append("\n") }
        for (t in tasks) {
            // v6.7.0 Step B guard: a record with no URL (an
            // adopted file, url="") has no link line to share —
            // emitting a blank line plus a name would read as a
            // broken entry on re-import, so it is skipped whole.
            if (t.url.isBlank()) continue
            sb.append("\n")
            val name = t.fileName.ifBlank { t.savedName }
            if (name.isNotBlank()) sb.append(name).append("\n")
            sb.append(t.url).append("\n")
        }
        return sb.toString()
    }
}
